package com.cotixgo.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.CollectionAccountJobEntity
import com.cotixgo.app.data.CollectionAccountQuoteEntity
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncPayloads
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.net.ApiClient
import com.cotixgo.app.net.SyncApi
import com.cotixgo.app.net.intOr
import com.cotixgo.app.net.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ordenación del reclamo de origen tras una cancelación offline
 * (docs/05 §179): un Trabajo/Cotización solo pertenece a una Cuenta
 * no cancelada, así que el CREATE que reclama un origen debe esperar a
 * que el servidor aplique las operaciones pendientes de cualquier otra
 * Cuenta que hoy lo vincula — en particular la cancelación que lo libera.
 * Sin esa espera, el reclamo viaja antes de la transición y el servidor
 * lo rechaza ("ya pertenece a otra cuenta de cobro no cancelada"),
 * pausándolo a él y a sus dependientes.
 *
 * `FakeSyncApi` simula la membresía server-side: registra el estado
 * aplicado por entidad y rechaza un reclamo si otra Cuenta aplicada y
 * no cancelada ya declara el origen — igual que `collectionSourceTaken`.
 * Datos exclusivamente sintéticos y el mismo aislamiento D2 que
 * SyncE2eTest/PausedOpResolutionTest.
 */
@RunWith(AndroidJUnit4::class)
class SourceClaimOrderingTest {

    private lateinit var db: AppDatabase
    private lateinit var session: Session
    private lateinit var sync: SyncRepository
    private lateinit var repo: AppRepository
    private lateinit var fake: FakeSyncApi
    private lateinit var dbPath: String
    private lateinit var prefsName: String
    private lateinit var prodBefore: Map<String, String?>
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** push/pull inyectable: scripted → respuestas deterministas; si no, delega. */
    private class FakeSyncApi(private val real: SyncApi) : SyncApi {
        var scripted = false
        val pushedOps = mutableListOf<JsonObject>()
        var respond: (JsonObject) -> JsonObject = { applied(it) }
        var pullChanges = mutableListOf<JsonObject>()

        override suspend fun push(deviceId: String, operations: List<JsonObject>): JsonObject {
            if (!scripted) return real.push(deviceId, operations)
            pushedOps += operations
            return buildJsonObject {
                put("results", JsonArray(operations.map { respond(it) }))
            }
        }

        override suspend fun pull(cursor: Long): JsonObject {
            if (!scripted) return real.pull(cursor)
            val out = pullChanges.toList()
            pullChanges.clear()
            return buildJsonObject {
                put("changes", JsonArray(out))
                put("next_cursor", cursor + out.size + 1)
            }
        }

        fun sentFor(entityType: String, entityId: String): List<JsonObject> =
            pushedOps.filter {
                it.str("entity_type") == entityType && it.str("entity_id") == entityId
            }

        companion object {
            fun applied(op: JsonObject, serverState: JsonObject? = null): JsonObject =
                buildJsonObject {
                    put("operation_id", op.str("operation_id"))
                    put("status", "APPLIED")
                    put("entity_id", op.str("entity_id"))
                    put("server_version", op.intOr("base_server_version") + 1)
                    serverState?.let { put("server_state", it) }
                }

            fun rejected(op: JsonObject, code: String, message: String): JsonObject =
                buildJsonObject {
                    put("operation_id", op.str("operation_id"))
                    put("status", "REJECTED")
                    put("code", code)
                    put("message", message)
                    put("entity_id", op.str("entity_id"))
                }
        }
    }

    private fun newDb(name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()

    @Before
    fun setUp() = runBlocking {
        prodBefore = TestIsolation.snapshotProd(ctx)
        dbPath = TestIsolation.name("e2e-claim-${Ids.new()}.db")
        prefsName = TestIsolation.name("e2e-claim-session-${Ids.new().take(8)}")
        db = newDb(dbPath)
        session = Session(ctx, db, prefsName).apply {
            apiBaseUrl = "http://10.0.2.2:8080"
            clearSession()
        }
        val real = ApiClient(session.apiBaseUrl) { session.token }
        fake = FakeSyncApi(real)
        sync = SyncRepository(db, session, apiOverride = fake)
        repo = AppRepository(db, sync)
        repo.completeOnboarding("Claim Bot", "Perfil Claim")
        val email = "e2e-${Ids.new().take(8)}@synthetic.local"
        val res = real.register("Claim Bot", email, "synthetic-pass-123",
            localUserId = db.userDao().get()!!.id)
        assertNull(sync.bindAccount(res.str("user_id")!!, email, res.str("token")!!))
    }

    @After
    fun tearDown() {
        db.close()
        TestIsolation.deleteDb(ctx, dbPath)
        TestIsolation.deletePrefs(ctx, prefsName)
        TestIsolation.assertProdUntouched(ctx, prodBefore)
    }

    /**
     * Membresía del lado servidor: por entidad aplicada guarda su payload
     * y rechaza un reclamo si otra Cuenta aplicada con `status != CANCELADA`
     * ya declara el mismo origen (espejo de `collectionSourceTaken`).
     */
    private val appliedAccounts = mutableMapOf<String, JsonObject>()

    private fun declaredSources(payload: JsonObject): Pair<Set<String>, Set<String>> =
        (payload["jobIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet()) to
            (payload["quoteIds"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet())

    private val membershipResponder: (JsonObject) -> JsonObject = { op ->
        if (op.str("entity_type") != EntityTypes.COLLECTION_ACCOUNT) {
            FakeSyncApi.applied(op)
        } else {
            val id = op.str("entity_id")!!
            val payload = op["payload"]!!.jsonObject
            val (jobs, quotes) = declaredSources(payload)
            val taken = appliedAccounts.any { (otherId, pl) ->
                if (otherId == id) return@any false
                if (pl["status"]?.jsonPrimitive?.content == "CANCELADA") return@any false
                val (oj, oq) = declaredSources(pl)
                jobs.any { it in oj } || quotes.any { it in oq }
            }
            if (taken) {
                FakeSyncApi.rejected(op, "VALIDATION_ERROR",
                    "El origen ya pertenece a otra cuenta de cobro no cancelada.")
            } else {
                appliedAccounts[id] = payload
                FakeSyncApi.applied(op)
            }
        }
    }

    private suspend fun seedClientProfile(
        name: String,
    ): Pair<com.cotixgo.app.data.ClientEntity, com.cotixgo.app.data.ProfileEntity> {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = name,
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        return client to profile
    }

    private suspend fun seedApprovedQuote(clientId: String, profileId: String): String {
        val qid = repo.saveQuote(
            com.cotixgo.app.data.QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = java.time.LocalDate.now().toEpochDay(),
                validityDays = 15, currency = "COP",
                lines = listOf(
                    com.cotixgo.app.data.QuoteLineInput(
                        conceptType = com.cotixgo.app.core.ConceptType.SERVICIO,
                        description = "Servicio", quantity = "1",
                        unit = "u", unitPrice = "100.00",
                    ),
                ),
                retentions = emptyList(),
            ),
        )!!
        repo.emitQuote(qid)
        repo.setQuoteStatus(qid, com.cotixgo.app.core.QuoteStatus.APROBADA)
        return qid
    }

    private fun accountInput(
        clientId: String,
        profileId: String,
        jobIds: List<String> = emptyList(),
        quoteIds: List<String> = emptyList(),
    ) = com.cotixgo.app.data.CollectionAccountInput(
        clientId = clientId, profileId = profileId, currency = "COP",
        items = listOf(
            com.cotixgo.app.data.CollectionItemInput(
                conceptType = com.cotixgo.app.core.ConceptType.SERVICIO,
                description = "Servicio", quantity = "1",
                unit = "u", unitPrice = "100.00",
            ),
        ),
        jobIds = jobIds, quoteIds = quoteIds,
    )

    private suspend fun lastOp(type: String, id: String) =
        db.syncDao().allOpsForEntity(type, id).last()

    /**
     * Reclamo sembrado directamente (fila + vínculo + op) sin la
     * validación del repositorio: reproduce un reclamo localmente
     * ilegítimo que el servidor debe rechazar.
     */
    private suspend fun seedClaimBypassingRepo(
        clientId: String,
        profileId: String,
        jobIds: List<String> = emptyList(),
        quoteIds: List<String> = emptyList(),
    ): String {
        val now = nowEpochMillis()
        val account = CollectionAccountEntity(
            id = Ids.new(), userId = session.accountUserId!!,
            clientId = clientId, profileId = profileId,
            provisionalNumber = "CC-PEND-${Ids.new().take(4).uppercase()}",
            status = "PENDIENTE", currency = "COP",
            net = "100.00", balance = "100.00",
            issuedAt = now, createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.collectionAccountDao().upsert(account)
            db.collectionAccountJobDao().upsertAll(
                jobIds.mapIndexed { i, j ->
                    CollectionAccountJobEntity(Ids.new(), account.id, j, i + 1, now)
                },
            )
            db.collectionAccountQuoteDao().upsertAll(
                quoteIds.mapIndexed { i, q ->
                    CollectionAccountQuoteEntity(Ids.new(), account.id, q, i + 1, now)
                },
            )
            sync.enqueue(
                EntityTypes.COLLECTION_ACCOUNT, account.id,
                SyncPayloads.collectionAccount(account, emptyList(), jobIds, quoteIds),
                baseServerVersion = 0,
            )
        }
        return account.id
    }

    // ---------- Regresión: la cancelación ordena el reclamo ----------

    @Test
    fun jobSource_cancelThenClaim_cancelAppliesFirst_noRejection() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Origen Job")
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        fake.scripted = true
        fake.respond = membershipResponder
        appliedAccounts.clear()
        sync.syncNow() // Cliente/Perfil/Trabajo aplicados (sv > 0).

        // Offline: A reclama J, se cancela y B lo reclama — todo antes del sync.
        val accountA = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        repo.cancelCollectionAccount(accountA)
        val accountB = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val aCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountA }
        val aCancelIdx = sent.indexOfLast { it.str("entity_id") == accountA }
        val bCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountB }
        // La cancelación de A viajó y aplicó ANTES del reclamo de B.
        assertTrue(aCreateIdx >= 0)
        assertTrue(aCancelIdx > aCreateIdx)
        assertTrue(bCreateIdx > aCancelIdx)
        // B se envió una sola vez y aplicó: ni rechazo ni reintento.
        assertEquals(1, fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, accountB).size)
        assertEquals("DONE", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountB).state)
        assertEquals("DONE", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountA).state)
        // Idempotencia del envío: cada op viajó exactamente una vez.
        assertEquals(2, fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, accountA).size)
    }

    @Test
    fun quoteSource_cancelThenClaim_cancelAppliesFirst_noRejection() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Origen Quote")
        val quoteId = seedApprovedQuote(client.id, profile.id)
        fake.scripted = true
        fake.respond = membershipResponder
        appliedAccounts.clear()
        sync.syncNow()

        val accountA = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, quoteIds = listOf(quoteId)),
        )!!
        repo.cancelCollectionAccount(accountA)
        val accountB = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, quoteIds = listOf(quoteId)),
        )!!
        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val aCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountA }
        val aCancelIdx = sent.indexOfLast { it.str("entity_id") == accountA }
        val bCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountB }
        assertTrue(aCreateIdx >= 0)
        assertTrue(aCancelIdx > aCreateIdx)
        assertTrue(bCreateIdx > aCancelIdx)
        assertEquals(1, fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, accountB).size)
        assertEquals("DONE", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountB).state)
    }

    @Test
    fun jobSource_stillHeldByActiveAccount_claimRejectedAsExpected() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Origen Ocupado")
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        val accountA = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        fake.scripted = true
        fake.respond = membershipResponder
        appliedAccounts.clear()
        sync.syncNow() // A aplica PENDIENTE con J: el origen sigue ocupado.

        // Reclamo fabricado mientras A (activa) retiene J: nada lo ordena
        // — no hay cancelación pendiente — así que viaja y el servidor lo
        // rechaza una sola vez; queda pausado para atención manual.
        val accountB = seedClaimBypassingRepo(client.id, profile.id, jobIds = listOf(jobId))
        sync.syncNow()

        assertEquals(1, fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, accountB).size)
        val bOp = lastOp(EntityTypes.COLLECTION_ACCOUNT, accountB)
        assertEquals("PAUSED", bOp.state)
        assertEquals("VALIDATION_ERROR", bOp.resultCode)
        assertNotEquals("CANCELADA", db.collectionAccountDao().get(accountA)!!.status)
    }

    @Test
    fun jobSource_cancelPaused_claimNeverSent() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Cancelación Falla")
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        val accountA = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        repo.cancelCollectionAccount(accountA)
        val accountB = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!

        fake.scripted = true
        appliedAccounts.clear()
        fake.respond = { op ->
            val pl = op["payload"]!!.jsonObject
            if (op.str("entity_id") == accountA &&
                pl["status"]?.jsonPrimitive?.content == "CANCELADA"
            ) {
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "cancelación imposible")
            } else {
                membershipResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        // La cancelación pausada retiene a B: nunca viajó ni viaja.
        assertTrue(fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, accountB).isEmpty())
        assertEquals("PENDING", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountB).state)
        assertEquals("PAUSED", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountA).state)
    }

    @Test
    fun jobSource_unrelatedAccount_notBlockedByPendingCancel() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Flujo Ajeno")
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        val jobId2 = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        fake.scripted = true
        fake.respond = membershipResponder
        appliedAccounts.clear()
        sync.syncNow()

        val accountA = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        repo.cancelCollectionAccount(accountA)
        val accountB = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId)),
        )!!
        val accountC = repo.saveCollectionAccount(
            accountInput(client.id, profile.id, jobIds = listOf(jobId2)),
        )!!
        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val aCancelIdx = sent.indexOfLast { it.str("entity_id") == accountA }
        val cCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountC }
        val bCreateIdx = sent.indexOfFirst { it.str("entity_id") == accountB }
        // C reclama un origen distinto: viaja sin esperar la cadena de A.
        assertTrue(cCreateIdx in 0 until aCancelIdx)
        assertTrue(bCreateIdx > aCancelIdx)
        assertEquals("DONE", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountC).state)
        assertEquals("DONE", lastOp(EntityTypes.COLLECTION_ACCOUNT, accountB).state)
    }
}

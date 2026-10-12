package com.cotixgo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.ServiceInput
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncPayloads
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.net.ApiClient
import com.cotixgo.app.net.intOr
import com.cotixgo.app.net.str
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * E2E real Android → PHP/MariaDB local con datos sintéticos (D4).
 * Requiere `php -S` en la máquina anfitriona (emulador → 10.0.2.2:8080).
 */
@RunWith(AndroidJUnit4::class)
class SyncE2eTest {

    private lateinit var db: AppDatabase
    private lateinit var session: Session
    private lateinit var sync: SyncRepository
    private lateinit var repo: AppRepository
    private lateinit var api: ApiClient
    private lateinit var dbPath: String
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun newDb(name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()

    @Before
    fun setUp() = runBlocking {
        dbPath = "cotixgo-e2e-${Ids.new()}.db"
        db = newDb(dbPath)
        session = Session(ctx, db).apply {
            apiBaseUrl = "http://10.0.2.2:8080"
            clearSession() // cada corrida usa una cuenta sintética nueva
        }
        sync = SyncRepository(db, session)
        repo = AppRepository(db, sync)
        api = ApiClient(session.apiBaseUrl) { session.token }
        // Registro sintético + vinculación con datos locales previos.
        repo.completeOnboarding("E2E Bot", "Perfil E2E")
        val email = "e2e-${Ids.new().take(8)}@synthetic.local"
        val res = api.register("E2E Bot", email, "synthetic-pass-123",
            localUserId = db.userDao().get()!!.id)
        sync.bindAccount(res.str("user_id")!!, email, res.str("token")!!)
    }

    @After
    fun tearDown() {
        db.close()
        ctx.deleteDatabase(dbPath)
        session.clearSession()
    }

    private suspend fun seedQuote(): String {
        repo.saveClient(
            ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente Sintético",
                phone = "3000000000", email = "cli@synthetic.local",
            ),
        )
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        repo.saveService(ServiceInput(profileId = profile.id, name = "Servicio E2E", suggestedPrice = "1000"))
        repo.saveRetention(RetentionInput(name = "Rete E2E", ratePercent = "10", appliesTo = setOf(ConceptType.SERVICIO)))
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        return repo.saveQuote(
            QuoteInput(
                clientId = client.id, profileId = profile.id,
                issueDateEpochDay = LocalDate.now().toEpochDay(), validityDays = 15,
                currency = "COP",
                lines = listOf(
                    QuoteLineInput(
                        conceptType = ConceptType.SERVICIO, description = "Servicio E2E",
                        quantity = "1", unit = "und", unitPrice = "1000",
                    ),
                ),
                retentions = listOf(
                    RetentionInput(name = "Rete E2E", ratePercent = "10", appliesTo = setOf(ConceptType.SERVICIO)),
                ),
            ),
        )!!
    }

    @Test
    fun endToEnd_quoteEmitted_getsOfficialNumber_andSurvivesRetry() = runBlocking {
        val quoteId = seedQuote()
        assertTrue(repo.emitQuote(quoteId))

        val s1 = sync.syncNow()
        assertTrue("push aplicó operaciones", s1.pushed > 0)
        val quote = db.quoteDao().get(quoteId)!!
        assertTrue(quote.officialNumber!!.startsWith("COT-"))
        assertTrue(quote.serverVersion >= 1)
        assertTrue(!quote.pendingSync)
        assertEquals(0, db.syncDao().pendingCount())

        // Re-push idempotente: las operaciones ya están DONE; un segundo ciclo
        // no debe reasignar número ni versiones.
        sync.syncNow()
        assertEquals(quote.officialNumber, db.quoteDao().get(quoteId)!!.officialNumber)
    }

    @Test
    fun queueSurvivesDatabaseReopen() = runBlocking {
        session.clearSession() // sin cuenta: las operaciones solo se encolan
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Durable"),
        )
        assertTrue(db.syncDao().pendingCount() > 0)
        db.close()
        db = newDb(dbPath)
        // La cola durable persiste tras cerrar/reabrir (equivalente a force-stop).
        assertTrue("la operación sobrevive al cierre", db.syncDao().pendingCount() > 0)
    }

    @Test
    fun pullRestoresOnCleanInstall() = runBlocking {
        val quoteId = seedQuote()
        repo.emitQuote(quoteId)
        sync.syncNow()
        val official = db.quoteDao().get(quoteId)!!.officialNumber

        // Instalación limpia simulada: limpiar copia local y restaurar por pull.
        sync.wipeLocalData()
        session.setCursor(0)
        val pulled = sync.pullAll()
        assertTrue(pulled > 0)
        val restored = db.quoteDao().get(quoteId)
        assertNotNull(restored)
        assertEquals(official, restored!!.officialNumber)
        assertTrue(!restored.pendingSync)
        assertTrue(db.quoteVersionDao().observeForQuote(quoteId).first().isNotEmpty())
        assertTrue(db.clientDao().observeForUser(session.accountUserId!!).first().isNotEmpty())
    }

    @Test
    fun concurrentEdit_createsConflict_bothCopiesPreserved() = runBlocking {
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Conflicto"),
        )
        sync.syncNow()
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val sv = client.serverVersion

        // Otro "dispositivo" empuja un cambio remoto sobre la misma entidad.
        val remote = api.push(
            "e2e-other-device",
            listOf(
                buildJsonObject {
                    put("operation_id", Ids.new())
                    put("entity_type", EntityTypes.CLIENT)
                    put("entity_id", client.id)
                    put("type", "upsert")
                    put("base_server_version", sv)
                    put("payload", SyncPayloads.client(client.copy(name = "Remoto gana")))
                },
            ),
        )
        assertEquals("APPLIED", remote["results"]!!.jsonArray[0].jsonObject.str("status"))

        // Edición local sobre base obsoleta → CONFLICT preserva ambas copias.
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Local gana"),
            existingId = client.id,
        )
        sync.syncNow()
        val conflicts = db.syncDao().observeConflicts().first()
        assertTrue(conflicts.any { it.entityId == client.id })
        // La copia local no fue sobrescrita silenciosamente.
        assertEquals("Local gana", db.clientDao().get(client.id)!!.name)

        // Resolución del usuario: conservar remota aplica el payload remoto.
        val conflict = conflicts.first { it.entityId == client.id }
        sync.resolveKeepRemote(conflict.id)
        assertEquals("Remoto gana", db.clientDao().get(client.id)!!.name)
        assertTrue(!db.clientDao().get(client.id)!!.pendingSync)
    }

    @Test
    fun stalePush_returnsConflict_andKeepLocalResolves() = runBlocking {
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Base obsoleta"),
        )
        sync.syncNow()
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()

        val remote = api.push(
            "e2e-other-device",
            listOf(
                buildJsonObject {
                    put("operation_id", Ids.new())
                    put("entity_type", EntityTypes.CLIENT)
                    put("entity_id", client.id)
                    put("type", "upsert")
                    put("base_server_version", client.serverVersion)
                    put("payload", SyncPayloads.client(client.copy(name = "Intermedio remoto")))
                },
            ),
        )
        assertEquals("APPLIED", remote["results"]!!.jsonArray[0].jsonObject.str("status"))

        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Local posterior"),
            existingId = client.id,
        )
        val summary = sync.syncNow()
        assertTrue(summary.paused > 0)

        val conflict = db.syncDao().observeConflicts().first().first { it.entityId == client.id }
        // Conservar local: misma operación, misma payload, base actualizada.
        sync.resolveKeepLocal(conflict.id)
        sync.syncNow()
        val final = db.clientDao().get(client.id)!!
        assertEquals("Local posterior", final.name)
        assertTrue(!final.pendingSync)
    }

    @Test
    fun quoteVersionConflict_resolvedByNewVersion() = runBlocking {
        val quoteId = seedQuote()
        repo.emitQuote(quoteId)
        sync.syncNow()
        val quote = db.quoteDao().get(quoteId)!!

        // Conflicto real: otro dispositivo envía la MISMA versión 1 con un
        // snapshot distinto (edición concurrente). El servidor conserva ambas
        // y marca el documento; la emisión/aprobación queda bloqueada.
        api.push(
            "e2e-other-device",
            listOf(
                buildJsonObject {
                    put("operation_id", Ids.new())
                    put("entity_type", EntityTypes.QUOTE_VERSION)
                    put("entity_id", Ids.new())
                    put("type", "upsert")
                    put("base_server_version", 0)
                    put("payload", buildJsonObject {
                        put("id", Ids.new()); put("quoteId", quoteId); put("version", 1)
                        put("snapshotJson", """{"version":1,"concurrente":"remota"}""")
                    })
                },
            ),
        )

        // Pull marca el conflicto de versiones sin perder ninguna copia.
        sync.pullAll()
        val flagged = db.quoteDao().get(quoteId)!!
        assertTrue(flagged.versionConflict)

        // El usuario resuelve conservando su v1 local → versión nueva 2.
        // Tras F2 la copia remota v1 también queda conservada como fila.
        val localV1 = db.quoteVersionDao().observeForQuote(quoteId).first()
            .first { it.version == 1 && !it.snapshotJson.contains("concurrente") }
        assertTrue(sync.resolveQuoteVersions(quoteId, localV1.id))
        val versions = db.quoteVersionDao().observeForQuote(quoteId).first()
        assertEquals(3, versions.size) // v1 local + v1 remota + v2 resolución
        sync.syncNow()
        val resolved = db.quoteDao().get(quoteId)!!
        assertTrue(!resolved.versionConflict)
        assertEquals(2, resolved.currentVersion)
        // El número oficial del documento emitido se conserva.
        assertEquals(quote.officialNumber, resolved.officialNumber)
    }

    @Test
    fun switchAccount_blockedWithPendingOps() = runBlocking {
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Pendiente"),
        )
        // Cola con operaciones sin sincronizar → cambio bloqueado.
        val blockers = sync.switchBlockers()
        assertTrue(blockers.isNotEmpty())
    }

    // ---------- Correcciones de revisión F3 ----------

    /** F1: la fila user lleva el id canónico, queda sincronizada, sin
     *  conflicto fantasma en pull y el cambio de cuenta queda desbloqueado. */
    @Test
    fun bindAccount_resolvesUserPending_noPhantomConflict() = runBlocking {
        val userId = session.accountUserId!!
        assertEquals("la fila local ya lleva el id canónico", userId, db.userDao().get()!!.id)

        sync.syncNow()
        val user = db.userDao().get()!!
        assertTrue("user pendiente resuelto tras push", !user.pendingSync)
        assertEquals("cero entidades pendientes", 0, sync.pendingEntityCount())
        assertTrue("sin conflicto fantasma en pull", db.syncDao().observeConflicts().first().isEmpty())
        assertTrue("cambio de cuenta desbloqueado", sync.switchBlockers().isEmpty())
        // La entidad user remota conserva el mismo id canónico (adoptado por el servidor).
        val pulled = api.pull(0)["changes"]!!.jsonArray
            .map { it.jsonObject }.firstOrNull { it.str("entity_type") == "user" }
        assertEquals(userId, pulled!!.str("entity_id"))
    }

    /** F1 (camino de reasignación): registro sin local_user_id — el servidor
     *  asigna user_id propio y la vinculación reescribe entityId/payload.id. */
    @Test
    fun bindAccount_reassignPath_rewritesUserOps() = runBlocking {
        val anon = ApiClient(session.apiBaseUrl) { null }
        val email = "e2e-re-${Ids.new().take(8)}@synthetic.local"
        val res = anon.register("Reasignado", email, "pass-re-789")
        val canonical = res.str("user_id")!!
        sync.bindAccount(canonical, email, res.str("token")!!)

        val userOp = db.syncDao().pendingOpsForEntity(EntityTypes.USER, canonical).singleOrNull()
        assertNotNull("la op user se regeneró con el id canónico", userOp)
        assertTrue(userOp!!.payloadJson.contains("\"id\":\"$canonical\""))
        assertTrue(userOp.supersedesOpId != null)

        sync.syncNow()
        val user = db.userDao().get()!!
        assertEquals(canonical, user.id)
        assertTrue(!user.pendingSync)
        assertEquals(0, sync.pendingEntityCount())
        assertTrue(db.syncDao().observeConflicts().first().isEmpty())
    }

    /** F1: cambio de cuenta permitido cuando la cola está vacía (regla D1). */
    @Test
    fun switchAccount_succeedsWhenQueueEmpty() = runBlocking {
        sync.syncNow()
        assertTrue(sync.switchBlockers().isEmpty())

        val anon = ApiClient(session.apiBaseUrl) { null }
        val emailB = "e2e-b-${Ids.new().take(8)}@synthetic.local"
        val resB = anon.register("Bot B", emailB, "pass-b-456")
        val userIdB = resB.str("user_id")!!

        val auth = AuthRepository(db, session, sync)
        val err = auth.switchAccount("synthetic-pass-123", emailB, "pass-b-456", confirmed = true)
        assertNull("cambio permitido con cola vacía: $err", err)
        assertEquals(userIdB, session.accountUserId)
        assertTrue("sin mezcla de datos de A", db.clientDao().observeForUser(userIdB).first().isEmpty())
    }

    /** F2: versión remota concurrente con el mismo número se conserva como
     *  copia local separada, elegible en la resolución y restaurable. */
    @Test
    fun concurrentVersion_remoteCopyStored_andRestored() = runBlocking {
        val quoteId = seedQuote()
        repo.emitQuote(quoteId)
        sync.syncNow()

        val remoteVersionId = Ids.new()
        api.push(
            "e2e-other-device",
            listOf(
                buildJsonObject {
                    put("operation_id", Ids.new())
                    put("entity_type", EntityTypes.QUOTE_VERSION)
                    put("entity_id", remoteVersionId)
                    put("type", "upsert")
                    put("base_server_version", 0)
                    put("payload", buildJsonObject {
                        put("id", remoteVersionId); put("quoteId", quoteId); put("version", 1)
                        put("snapshotJson", """{"version":1,"remota":true}""")
                    })
                },
            ),
        )
        sync.pullAll()
        // Ambas copias v1 conservadas como filas separadas con serverOrder.
        val v1s = db.quoteVersionDao().observeForQuote(quoteId).first().filter { it.version == 1 }
        assertEquals("ambas v1 conservadas", 2, v1s.size)
        assertTrue(v1s.any { it.id == remoteVersionId })
        assertTrue(v1s.any { it.serverOrder != null && it.serverOrder!! > 0 })

        // La resolución puede elegir la copia remota por id de fila.
        assertTrue(sync.resolveQuoteVersions(quoteId, remoteVersionId))
        val resVersion = db.quoteVersionDao().observeForQuote(quoteId).first().first { it.version == 2 }
        assertTrue(resVersion.snapshotJson.contains("remota"))
        sync.syncNow()

        // Restauración en instalación limpia: ambas v1 + resolución.
        sync.wipeLocalData()
        session.setCursor(0)
        sync.pullAll()
        val restored = db.quoteVersionDao().observeForQuote(quoteId).first()
        assertEquals(3, restored.size)
        assertEquals(2, restored.count { it.version == 1 })
        assertTrue(restored.any { it.id == remoteVersionId })
    }

    /** F7: keep-local encadena una operación NUEVA con supersedes y base
     *  corregida; la pausada queda DONE/SUPERSEDED en el historial. */
    @Test
    fun keepLocal_enqueuesSupersedingOp() = runBlocking {
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Base obsoleta"),
        )
        sync.syncNow()
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()

        api.push(
            "e2e-other-device",
            listOf(
                buildJsonObject {
                    put("operation_id", Ids.new())
                    put("entity_type", EntityTypes.CLIENT)
                    put("entity_id", client.id)
                    put("type", "upsert")
                    put("base_server_version", client.serverVersion)
                    put("payload", SyncPayloads.client(client.copy(name = "Intermedio remoto")))
                },
            ),
        )
        repo.saveClient(
            ClientInput(type = com.cotixgo.app.core.ClientType.PERSONA, name = "Local posterior"),
            existingId = client.id,
        )
        sync.syncNow()

        val conflict = db.syncDao().observeConflicts().first().first { it.entityId == client.id }
        val paused = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first()
        sync.resolveKeepLocal(conflict.id)

        val newOp = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).single()
        assertTrue("operation_id nuevo", newOp.opId != paused.opId)
        assertEquals("encadenada a la pausada", paused.opId, newOp.supersedesOpId)
        assertEquals("base corregida al servidor remoto",
            conflict.remoteServerVersion, newOp.baseServerVersion)

        sync.syncNow()
        val final = db.clientDao().get(client.id)!!
        assertEquals("Local posterior", final.name)
        assertTrue(!final.pendingSync)
    }
}

package com.cotixgo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.MaterialEntity
import com.cotixgo.app.data.PaymentAssignmentEntity
import com.cotixgo.app.data.PaymentEntity
import com.cotixgo.app.data.Session
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigDecimal

/**
 * Resolución de operaciones PAUSED rechazadas (decisiones aprobadas):
 * clasificación por código, reintento acotado de INTERNAL_ERROR con el
 * mismo operation_id, gating por dependencias, reemisión con opId nuevo
 * (los rechazos definitivos están memorizados en el servidor), restaurar
 * remoto solo con estado autoritativo propio, descarte con vista previa
 * de datos nunca sincronizados, FORBIDDEN_ENTITY sin reenvío ni fuga, y
 * eventos financieros rechazados que nunca alteran saldos confirmados.
 *
 * `FakeSyncApi` permite respuestas deterministas (INTERNAL_ERROR bajo
 * demanda no es producible por el backend real); cuando `scripted` está
 * apagado delega al backend local real. Datos exclusivamente sintéticos
 * y el mismo aislamiento D2 que SyncE2eTest.
 */
@RunWith(AndroidJUnit4::class)
class PausedOpResolutionTest {

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

            fun rejected(
                op: JsonObject,
                code: String,
                message: String,
                serverState: JsonObject? = null,
                forbiddenScope: String? = null,
            ): JsonObject = buildJsonObject {
                put("operation_id", op.str("operation_id"))
                put("status", "REJECTED")
                put("code", code)
                put("message", message)
                put("entity_id", op.str("entity_id"))
                serverState?.let { put("server_state", it) }
                forbiddenScope?.let { put("forbidden_scope", it) }
            }
        }
    }

    private fun newDb(name: String) = Room.databaseBuilder(ctx, AppDatabase::class.java, name).build()

    @Before
    fun setUp() = runBlocking {
        prodBefore = TestIsolation.snapshotProd(ctx)
        dbPath = TestIsolation.name("e2e-paused-${Ids.new()}.db")
        prefsName = TestIsolation.name("e2e-paused-session-${Ids.new().take(8)}")
        db = newDb(dbPath)
        session = Session(ctx, db, prefsName).apply {
            apiBaseUrl = "http://10.0.2.2:8080"
            clearSession()
        }
        val real = ApiClient(session.apiBaseUrl) { session.token }
        fake = FakeSyncApi(real)
        sync = SyncRepository(db, session, apiOverride = fake)
        repo = AppRepository(db, sync)
        repo.completeOnboarding("Paused Bot", "Perfil Paused")
        val email = "e2e-${Ids.new().take(8)}@synthetic.local"
        val res = real.register("Paused Bot", email, "synthetic-pass-123",
            localUserId = db.userDao().get()!!.id)
        assertNull(sync.bindAccount(res.str("user_id")!!, email, res.str("token")!!))
        // Registro y onboarding ya empujaron al backend real; a partir de
        // aquí cada prueba activa las respuestas deterministas.
    }

    @After
    fun tearDown() {
        db.close()
        TestIsolation.deleteDb(ctx, dbPath)
        TestIsolation.deletePrefs(ctx, prefsName)
        TestIsolation.assertProdUntouched(ctx, prodBefore)
    }

    /** Anula la espera de backoff para que el próximo syncNow reintente ya. */
    private suspend fun clearBackoff(opId: String) {
        val op = db.syncDao().opById(opId)!!
        if (op.state != "RETRY") return
        val rj = op.resultJson?.let {
            kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject
        }
        val edited = buildJsonObject {
            rj?.forEach { (k, v) -> if (k != "nextRetryAt") put(k, v) }
            put("nextRetryAt", 0)
        }
        db.syncDao().rescheduleOp(opId, edited.toString(), nowEpochMillis())
    }

    private suspend fun lastOp(type: String, id: String) =
        db.syncDao().allOpsForEntity(type, id).last()

    // ---------- INTERNAL_ERROR ----------

    @Test
    fun internalError_transient_retriesSameOpId_thenApplies() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Transitorio",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val opId = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first().opId

        var failures = 0
        fake.scripted = true
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.CLIENT && failures < 2) {
                failures++
                FakeSyncApi.rejected(op, "INTERNAL_ERROR", "fallo interno simulado")
            } else {
                FakeSyncApi.applied(op)
            }
        }

        sync.syncNow() // falla 1 → RETRY con backoff
        clearBackoff(opId)
        sync.syncNow() // falla 2 → RETRY
        clearBackoff(opId)
        sync.syncNow() // aplica

        val sent = fake.sentFor(EntityTypes.CLIENT, client.id)
        assertTrue("el mismo operation_id se reintentó", sent.size >= 3)
        assertTrue(sent.all { it.str("operation_id") == opId })
        val op = db.syncDao().opById(opId)!!
        assertEquals("DONE", op.state)
        assertTrue(!db.clientDao().get(client.id)!!.pendingSync)
        assertTrue(sync.switchBlockers().isEmpty())
    }

    @Test
    fun internalError_exhausted_pausesAsInternalFailure_andManualRetryRenewsBudget() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Siempre falla",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val opId = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first().opId

        fake.scripted = true
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.CLIENT) {
                FakeSyncApi.rejected(op, "INTERNAL_ERROR", "fallo interno persistente")
            } else {
                FakeSyncApi.applied(op)
            }
        }

        // 1 envío inicial + 5 reintentos automáticos = 6 envíos, mismo opId.
        repeat(6) {
            sync.syncNow()
            clearBackoff(opId)
        }
        val op = db.syncDao().opById(opId)!!
        assertEquals("PAUSED", op.state)
        assertEquals("INTERNAL_ERROR", op.resultCode)
        val sent = fake.sentFor(EntityTypes.CLIENT, client.id)
        assertEquals(6, sent.size)
        assertTrue(sent.all { it.str("operation_id") == opId })

        val detail = sync.pausedDetail(op)
        assertTrue(detail.internalExhausted)
        assertFalse(detail.canReissue) // no es un rechazo de negocio
        assertTrue(db.clientDao().get(client.id)!!.pendingSync)
        assertTrue(sync.switchBlockers().isNotEmpty())

        // Reintento manual: mismo opId, presupuesto renovado.
        assertNull(sync.retryPaused(opId))
        assertEquals("RETRY", db.syncDao().opById(opId)!!.state)
        assertEquals(0, db.syncDao().opById(opId)!!.attempts)

        fake.respond = { FakeSyncApi.applied(it) }
        sync.syncNow()
        assertEquals("DONE", db.syncDao().opById(opId)!!.state)
        assertTrue(sync.switchBlockers().isEmpty())
    }

    // ---------- Dependencias: gating, liberación y cascada ----------

    @Test
    fun dependency_parentRejected_childHeldInQueue_neverSent() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Padre rechazado",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        val jobOp = db.syncDao().pendingOpsForEntity(EntityTypes.JOB, jobId).first()

        fake.scripted = true
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.CLIENT) {
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "cliente inválido")
            } else {
                FakeSyncApi.applied(op)
            }
        }
        sync.syncNow()

        // El hijo nunca se envió: su prerequisito quedó pausado.
        assertTrue(fake.sentFor(EntityTypes.JOB, jobId).isEmpty())
        assertEquals("PENDING", db.syncDao().opById(jobOp.opId)!!.state)
        assertEquals(
            "VALIDATION_ERROR",
            db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first().resultCode,
        )
        assertTrue(sync.switchBlockers().isNotEmpty())
    }

    @Test
    fun dependency_pausedOp_reissuesWithNewOpId_whenPrereqResolved() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente OK",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!

        // El Cliente aplica pero el servidor reporta la referencia ausente
        // (DEPENDENCY): la op del Trabajo queda pausada.
        fake.scripted = true
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.JOB) {
                FakeSyncApi.rejected(op, "DEPENDENCY", "clientId no existe en el servidor")
            } else {
                FakeSyncApi.applied(op)
            }
        }
        sync.syncNow()
        val jobOp = db.syncDao().pendingOpsForEntity(EntityTypes.JOB, jobId).first()
        assertEquals("PAUSED", jobOp.state)
        assertEquals("DEPENDENCY", jobOp.resultCode)

        // Sin progreso en el prerequisito NO hay reemisión: un nuevo ciclo
        // no reintenta en bucle el mismo rechazo memorizado.
        sync.syncNow()
        assertEquals(1, db.syncDao().allOpsForEntity(EntityTypes.JOB, jobId).size)
        assertEquals("PAUSED", db.syncDao().opById(jobOp.opId)!!.state)

        // Progreso real: el prerequisito se actualiza y aplica tras la
        // pausa → la pausada se libera como op NUEVA (el rechazo DEPENDENCY
        // está memorizado; el mismo opId devolvería el resultado guardado).
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente OK v2",
            ),
            existingId = client.id,
        )
        fake.respond = { FakeSyncApi.applied(it) }
        sync.syncNow()
        val old = db.syncDao().opById(jobOp.opId)!!
        assertEquals("DONE", old.state)
        assertEquals("REISSUED", old.resultCode)
        assertTrue(old.resultJson!!.contains("DEPENDENCY")) // resultado original conservado
        val history = db.syncDao().allOpsForEntity(EntityTypes.JOB, jobId)
        assertEquals(2, history.size)
        val reissued = history.last()
        assertNotEquals(jobOp.opId, reissued.opId)
        assertEquals(jobOp.opId, reissued.supersedesOpId)
        assertEquals("DONE", reissued.state)
        assertTrue(!db.jobDao().get(jobId)!!.pendingSync)
    }

    @Test
    fun discard_neverSynced_cascadePreviewAndPrereqMarked() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente descarte",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!
        val actId = repo.saveJobActivity(
            com.cotixgo.app.data.JobActivityInput(jobId = jobId, description = "Actividad"),
        )!!

        // Un dependiente que NO es hijo de cascada: una Cuenta de Cobro
        // encolada que referencia al Trabajo por jobIds (payload fabricado
        // para la prueba, sin fila de vínculo local).
        val pendingAccountId = Ids.new()
        sync.enqueue(
            EntityTypes.COLLECTION_ACCOUNT, pendingAccountId,
            buildJsonObject {
                put("id", pendingAccountId)
                put("userId", session.accountUserId!!)
                put("clientId", client.id)
                put("profileId", profile.id)
                put("jobIds", JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(jobId))))
                put("quoteIds", JsonArray(emptyList()))
            },
            baseServerVersion = 0,
        )

        fake.scripted = true
        fake.respond = { op ->
            when (op.str("entity_type")) {
                EntityTypes.JOB -> FakeSyncApi.rejected(op, "VALIDATION_ERROR", "trabajo inválido")
                else -> FakeSyncApi.applied(op)
            }
        }
        sync.syncNow()
        val jobOp = db.syncDao().pendingOpsForEntity(EntityTypes.JOB, jobId).first()
        assertEquals("PAUSED", jobOp.state)
        // La Cuenta queda en cola esperando (nunca enviada: depende del Job).
        assertTrue(fake.sentFor(EntityTypes.COLLECTION_ACCOUNT, pendingAccountId).isEmpty())

        // El Cliente (ya sincronizado) no es descartable: regla de bloqueo.
        val clientOp = db.syncDao().allOpsForEntity(EntityTypes.CLIENT, client.id).last()
        val blockedPreview = sync.discardPreview(clientOp)
        assertTrue(blockedPreview.blockers.isNotEmpty())
        assertNotNull(sync.discardPaused(clientOp.opId, 1))

        // Vista previa del Trabajo: cascada job + job_activity.
        val preview = sync.discardPreview(jobOp)
        assertEquals(2, preview.rows.size)
        assertTrue(preview.rows.all { !it.synced })
        assertTrue(preview.waitingDependents.any { it.entityId == pendingAccountId })

        assertNull(sync.discardPaused(jobOp.opId, preview.rows.size))
        assertNull(db.jobDao().get(jobId))
        assertNull(db.jobActivityDao().get(actId))
        assertEquals("DONE", db.syncDao().opById(jobOp.opId)!!.state)
        assertEquals("DISCARDED_LOCAL", db.syncDao().opById(jobOp.opId)!!.resultCode)
        // La operación hija también quedó cerrada en cascada, historial intacto.
        val actOps = db.syncDao().allOpsForEntity(EntityTypes.JOB_ACTIVITY, actId)
        assertEquals("DISCARDED_CASCADE", actOps.last().resultCode)
        assertTrue(actOps.last().payloadJson.contains(actId))
        // El dependiente externo queda visible para atención, no esperando eternamente.
        val depOp = db.syncDao().allOpsForEntity(EntityTypes.COLLECTION_ACCOUNT, pendingAccountId).last()
        assertEquals("PAUSED", depOp.state)
        assertEquals("PREREQUISITE_DISCARDED", depOp.resultCode)

        // Queda visible para atención: la Cuenta huérfana sigue pausada.
        assertTrue(sync.switchBlockers().isNotEmpty())
    }

    // ---------- VALIDATION_ERROR: corregir + reemisión con historial ----------

    @Test
    fun validationError_correctEntity_reissuesCurrentState_preservesHistory() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Dato malo",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val original = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first()

        fake.scripted = true
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.CLIENT) {
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "nombre inválido")
            } else {
                FakeSyncApi.applied(op)
            }
        }
        sync.syncNow()
        assertEquals("PAUSED", db.syncDao().opById(original.opId)!!.state)

        // El usuario corrige la entidad: nueva op en cola tras la pausada.
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Dato corregido",
            ),
            existingId = client.id,
        )
        val detail = sync.pausedDetail(db.syncDao().opById(original.opId)!!)
        assertTrue(detail.canReissue)

        // «Reintentar» sobre VALIDATION_ERROR: la pausada se cierra REISSUED
        // y se encola una op NUEVA con el estado local vigente (corregido).
        assertNull(sync.retryPaused(original.opId))
        val closed = db.syncDao().opById(original.opId)!!
        assertEquals("DONE", closed.state)
        assertEquals("REISSUED", closed.resultCode)
        assertTrue(closed.payloadJson.contains("Dato malo")) // payload original intacto
        assertTrue(closed.resultJson!!.contains("VALIDATION_ERROR"))

        fake.respond = { FakeSyncApi.applied(it) }
        sync.syncNow()
        val final = db.clientDao().get(client.id)!!
        assertEquals("Dato corregido", final.name)
        assertTrue(!final.pendingSync)
        val history = db.syncDao().allOpsForEntity(EntityTypes.CLIENT, client.id)
        assertTrue("historial completo conservado", history.size >= 3)
        assertEquals("REISSUED", history.first().resultCode)
        assertTrue(history.all { it.state == "DONE" })
        assertTrue(sync.switchBlockers().isEmpty())
    }

    // ---------- Restaurar remoto / FORBIDDEN_ENTITY ----------

    @Test
    fun restoreRemote_appliesAuthoritativeState_closesOp() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Local divergente",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val op = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first()

        // El servidor rechaza la transición y adjunta el estado propio
        // autoritativo (la fila ES del titular autenticado).
        fake.scripted = true
        fake.respond = { o ->
            if (o.str("entity_type") == EntityTypes.CLIENT) {
                FakeSyncApi.rejected(
                    o, "INVALID_TRANSITION", "transición no aplicable",
                    serverState = buildJsonObject {
                        put("id", client.id)
                        put("userId", session.accountUserId!!)
                        put("name", "Verdad del servidor")
                        put("type", "PERSONA")
                        put("server_version", 4)
                    },
                )
            } else {
                FakeSyncApi.applied(o)
            }
        }
        sync.syncNow()
        val paused = db.syncDao().opById(op.opId)!!
        val detail = sync.pausedDetail(paused)
        assertTrue(detail.canRestoreRemote)

        assertNull(sync.restorePausedRemote(op.opId))
        val restored = db.clientDao().get(client.id)!!
        assertEquals("Verdad del servidor", restored.name)
        assertEquals(4, restored.serverVersion)
        assertFalse(restored.pendingSync)
        val closed = db.syncDao().opById(op.opId)!!
        assertEquals("DONE", closed.state)
        assertEquals("RESTORED_FROM_REMOTE", closed.resultCode)
        assertTrue(closed.resultJson!!.contains("INVALID_TRANSITION"))
    }

    @Test
    fun forbiddenEntity_noResendNoLeak_discardOnly() = runBlocking {
        val matId = Ids.new()
        db.materialDao().upsert(
            MaterialEntity(
                id = matId, userId = session.accountUserId!!,
                name = "Material residual", unit = "und",
                createdAt = nowEpochMillis(), updatedAt = nowEpochMillis(),
                pendingSync = true, serverVersion = 0,
            ),
        )
        sync.enqueue(
            EntityTypes.MATERIAL, matId,
            buildJsonObject {
                put("id", matId); put("userId", session.accountUserId!!)
                put("name", "Material residual"); put("unit", "und")
            },
            baseServerVersion = 0,
        )
        val op = db.syncDao().pendingOpsForEntity(EntityTypes.MATERIAL, matId).first()

        fake.scripted = true
        fake.respond = { o ->
            if (o.str("entity_type") == EntityTypes.MATERIAL) {
                // La fila pertenece a otra cuenta: sin server_state (sin fuga).
                FakeSyncApi.rejected(
                    o, "FORBIDDEN_ENTITY", "La entidad pertenece a otra cuenta.",
                    forbiddenScope = "entity_foreign",
                )
            } else {
                FakeSyncApi.applied(o)
            }
        }
        sync.syncNow()

        val paused = db.syncDao().opById(op.opId)!!
        val detail = sync.pausedDetail(paused)
        assertEquals("entity_foreign", detail.forbiddenEntityScope)
        assertFalse(detail.canReissue)
        assertFalse(detail.canRestoreRemote)
        assertFalse(detail.canAcknowledge)
        assertTrue(detail.canDiscard)

        // «Reintentar» nunca reenvía: el servidor no vuelve a ver la op.
        assertNotNull(sync.retryPaused(op.opId))
        assertEquals(1, fake.sentFor(EntityTypes.MATERIAL, matId).size)
        assertEquals("PAUSED", db.syncDao().opById(op.opId)!!.state)

        // Descarte seguro del residuo ajeno con vista previa.
        val preview = sync.discardPreview(paused)
        assertEquals(1, preview.rows.size)
        assertNull(sync.discardPaused(op.opId, preview.rows.size))
        assertNull(db.materialDao().get(matId))
        assertEquals("DISCARDED_LOCAL", db.syncDao().opById(op.opId)!!.resultCode)
    }

    @Test
    fun forbiddenReference_ownRow_restoresRemoteState() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente propio",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        val profile = db.profileDao().observeForUser(session.accountUserId!!).first().first()
        val jobId = repo.saveJob(
            com.cotixgo.app.data.JobInput(clientId = client.id, profileId = profile.id),
        )!!

        // El rechazo proviene de una referencia ajena; la fila ES propia y
        // el servidor adjunta su estado autoritativo.
        fake.scripted = true
        fake.respond = { o ->
            when (o.str("entity_type")) {
                EntityTypes.JOB -> FakeSyncApi.rejected(
                    o, "FORBIDDEN_ENTITY", "La referencia pertenece a otra cuenta.",
                    forbiddenScope = "foreign_reference",
                    serverState = buildJsonObject {
                        put("id", jobId)
                        put("userId", session.accountUserId!!)
                        put("clientId", client.id)
                        put("profileId", profile.id)
                        put("status", "BORRADOR")
                        put("notes", "estado autoritativo")
                        put("server_version", 2)
                    },
                )
                else -> FakeSyncApi.applied(o)
            }
        }
        sync.syncNow()

        val jobOp = db.syncDao().pendingOpsForEntity(EntityTypes.JOB, jobId).first()
        val detail = sync.pausedDetail(jobOp)
        assertEquals("foreign_reference", detail.forbiddenEntityScope)
        assertTrue(detail.canRestoreRemote)
        assertNotNull(sync.retryPaused(jobOp.opId)) // nunca reenvía
        assertEquals(1, fake.sentFor(EntityTypes.JOB, jobId).size)

        assertNull(sync.restorePausedRemote(jobOp.opId))
        val job = db.jobDao().get(jobId)!!
        assertEquals("estado autoritativo", job.notes)
        assertEquals(2, job.serverVersion)
        assertFalse(job.pendingSync)
        assertEquals("RESTORED_FROM_REMOTE", db.syncDao().opById(jobOp.opId)!!.resultCode)
    }

    // ---------- Financiero: rechazado nunca toca el saldo confirmado ----------

    @Test
    fun rejectedAssignment_confirmedBalanceUnchanged_acknowledgeKeepsHistory() = runBlocking {
        val userId = session.accountUserId!!
        val accountId = Ids.new()
        val paymentId = Ids.new()
        val assignId = Ids.new()
        val now = nowEpochMillis()
        // Cuenta ya confirmada por el servidor: espejo server* = autoritativo;
        // las columnas de trabajo reflejan el efecto optimista de la asignación.
        db.collectionAccountDao().upsert(
            CollectionAccountEntity(
                id = accountId, userId = userId, clientId = "c1", profileId = "p1",
                provisionalNumber = "CC-PEND-T1", status = "PENDIENTE", currency = "COP",
                gross = "200.00", net = "200.00",
                appliedAmount = "50.00", balance = "150.00",
                serverAppliedAmount = "0.00", serverBalance = "200.00",
                issuedAt = now, createdAt = now, updatedAt = now,
                pendingSync = false, serverVersion = 3,
            ),
        )
        db.paymentDao().upsert(
            PaymentEntity(
                id = paymentId, userId = userId, clientId = "c1",
                amount = "50.00", paidAtEpochDay = 20000,
                appliedAmount = "50.00", unassignedAmount = "0.00",
                effectiveAmount = "50.00",
                serverAppliedAmount = "0.00", serverUnassignedAmount = "50.00",
                createdAt = now, updatedAt = now,
                pendingSync = false, serverVersion = 2,
            ),
        )
        db.paymentAssignmentDao().upsert(
            PaymentAssignmentEntity(
                id = assignId, userId = userId, paymentId = paymentId,
                action = "ASSIGN", targetType = "ACCOUNT", accountId = accountId,
                amount = "50.00", reason = "aplicación",
                createdAt = now, updatedAt = now,
                pendingSync = true, serverVersion = 0,
            ),
        )
        sync.enqueue(
            EntityTypes.PAYMENT_ASSIGNMENT, assignId,
            buildJsonObject {
                put("id", assignId); put("paymentId", paymentId)
                put("action", "assign"); put("targetType", "account")
                put("accountId", accountId); put("amount", "50.00")
                put("reason", "aplicación")
            },
            baseServerVersion = 0,
        )
        val assignOp = db.syncDao().pendingOpsForEntity(EntityTypes.PAYMENT_ASSIGNMENT, assignId).first()

        fake.scripted = true
        fake.respond = { o ->
            if (o.str("entity_type") == EntityTypes.PAYMENT_ASSIGNMENT) {
                FakeSyncApi.rejected(o, "VALIDATION_ERROR", "la cuenta ya está pagada")
            } else {
                FakeSyncApi.applied(o)
            }
        }
        sync.syncNow()
        assertEquals("PAUSED", db.syncDao().opById(assignOp.opId)!!.state)

        // El saldo confirmado (espejo servidor) NO cambió; el efecto se
        // reporta como rechazado, nunca como pendiente ni aplicado.
        val account = db.collectionAccountDao().get(accountId)!!
        assertEquals("200.00", account.serverBalance)
        assertEquals("0.00", account.serverAppliedAmount)
        val effects = repo.observeDestinationEffects(userId).first()
        val eff = effects["ACCOUNT:$accountId"]!!
        assertEquals(0, eff.pending.compareTo(BigDecimal.ZERO))
        assertEquals(0, eff.rejected.compareTo(BigDecimal("50.00")))

        // Reconocimiento explícito: la fila queda registrada sin borrar ni
        // reescribir; el saldo confirmado sigue intacto.
        assertNull(sync.acknowledgeRejectedOp(assignOp.opId))
        val closed = db.syncDao().opById(assignOp.opId)!!
        assertEquals("DONE", closed.state)
        assertEquals("REJECTED_ACKNOWLEDGED", closed.resultCode)
        assertTrue(closed.payloadJson.contains("aplicación"))
        assertNotNull(db.paymentAssignmentDao().forPayment(paymentId).firstOrNull { it.id == assignId })
        assertFalse(db.paymentAssignmentDao().forPayment(paymentId).first { it.id == assignId }.pendingSync)
        assertEquals("200.00", db.collectionAccountDao().get(accountId)!!.serverBalance)
        val effAfter = repo.observeDestinationEffects(userId).first()["ACCOUNT:$accountId"]!!
        assertEquals(0, effAfter.rejected.compareTo(BigDecimal("50.00")))
    }

    // ---------- switchAccount bloqueado/desbloqueado ----------

    @Test
    fun switchBlockers_reportPausedAndPending_thenUnblockedAfterResolution() = runBlocking {
        repo.saveClient(
            com.cotixgo.app.core.ClientInput(
                type = com.cotixgo.app.core.ClientType.PERSONA, name = "Bloqueo",
            ),
        )
        val client = db.clientDao().observeForUser(session.accountUserId!!).first().first()
        fake.scripted = true
        fake.respond = { o ->
            if (o.str("entity_type") == EntityTypes.CLIENT) {
                FakeSyncApi.rejected(o, "VALIDATION_ERROR", "inválido")
            } else {
                FakeSyncApi.applied(o)
            }
        }
        sync.syncNow()
        val blockers = sync.switchBlockers()
        assertTrue(blockers.any { it.contains("rechazadas sin resolver") })
        assertTrue(blockers.any { it.contains("pendientes de sincronización") })

        val op = db.syncDao().pendingOpsForEntity(EntityTypes.CLIENT, client.id).first()
        val preview = sync.discardPreview(op)
        assertNull(sync.discardPaused(op.opId, preview.rows.size))
        assertTrue(sync.switchBlockers().isEmpty())
    }
}

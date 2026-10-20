package com.cotixgo.app

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.MaterialCondition
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.MovementOrigin
import com.cotixgo.app.core.MovementType
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.InventoryMovementEntity
import com.cotixgo.app.data.JobActivityEntity
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialEntity
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.MaterialInput
import com.cotixgo.app.data.ReportInput
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Ordenación del cierre de un Trabajo respecto de sus actividades
 * (observado en validación manual -JOBV): ops de `job_activity`
 * encoladas ANTES de la transición a estado terminal (`ENTREGADO` /
 * `CANCELADO`) deben aplicarse en el servidor antes que esa
 * transición, para que un Informe posterior no capture un snapshot
 * de ejecución obsoleto (F5 solo presenta `COMPLETADA` como
 * ejecución realizada).
 *
 * `FakeSyncApi` registra el orden en que el servidor "aplica" cada
 * operación: como `sync.php` procesa el lote en orden y audita cada
 * aplicación, `pushedOps`/`appliedLog` son el proxy determinista de
 * la secuencia de `server_seq`/`audit_events` — la auditoría real en
 * MariaDB no es aservable sin escribir en el backend productivo.
 * Datos exclusivamente sintéticos y el mismo aislamiento D2 que
 * SyncE2eTest/SourceClaimOrderingTest.
 */
@RunWith(AndroidJUnit4::class)
class TerminalJobOrderingTest {

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
        dbPath = TestIsolation.name("e2e-term-${Ids.new()}.db")
        prefsName = TestIsolation.name("e2e-term-session-${Ids.new().take(8)}")
        db = newDb(dbPath)
        session = Session(ctx, db, prefsName).apply {
            apiBaseUrl = "http://10.0.2.2:8080"
            clearSession()
        }
        val real = ApiClient(session.apiBaseUrl) { session.token }
        fake = FakeSyncApi(real)
        sync = SyncRepository(db, session, apiOverride = fake)
        repo = AppRepository(db, sync)
        repo.completeOnboarding("Term Bot", "Perfil Term")
        val email = "e2e-${Ids.new().take(8)}@synthetic.local"
        val res = real.register("Term Bot", email, "synthetic-pass-123",
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
     * Secuencia de aplicación server-side (proxy de audit_events):
     * cada op registrada en el orden en que el servidor la aplica.
     */
    private val appliedLog = mutableListOf<JsonObject>()

    private val recordingResponder: (JsonObject) -> JsonObject = { op ->
        appliedLog += op
        FakeSyncApi.applied(op)
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

    private suspend fun lastOp(type: String, id: String) =
        db.syncDao().allOpsForEntity(type, id).last()

    private fun JsonObject.payloadStatus(): String? =
        this["payload"]?.jsonObject?.get("status")?.jsonPrimitive?.content

    /** Índice de aplicación de la op `job` cuyo payload declara `status`. */
    private fun appliedIdxOfJobStatus(jobId: String, status: String): Int =
        appliedLog.indexOfFirst {
            it.str("entity_type") == EntityTypes.JOB &&
                it.str("entity_id") == jobId && it.payloadStatus() == status
        }

    private fun lastAppliedIdxOfEntity(entityId: String): Int =
        appliedLog.indexOfLast { it.str("entity_id") == entityId }

    /**
     * Actividad sembrada directamente (fila + op) sin la validación del
     * repositorio: reproduce una op hija encolada DESPUÉS del cierre —
     * localmente el repo no puede crearla sobre un Trabajo terminal.
     */
    private suspend fun seedActivityBypassingRepo(jobId: String): String {
        val now = nowEpochMillis()
        val activity = JobActivityEntity(
            id = Ids.new(), jobId = jobId, description = "Actividad tardía",
            status = JobActivityStatus.PENDIENTE.name, position = 99,
            createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.jobActivityDao().upsert(activity)
            sync.enqueue(
                EntityTypes.JOB_ACTIVITY, activity.id,
                SyncPayloads.jobActivity(activity), baseServerVersion = 0,
            )
        }
        return activity.id
    }

    // ---------- Regresión: el cierre espera a las actividades previas ----------

    @Test
    fun entregado_activityOpsEnqueuedBefore_applyFirst() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Cierre")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow() // Cliente/Perfil/Trabajo aplicados (sv > 0).

        // Cola: actividad con ciclo completo encolada ANTES del cierre.
        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Actividad previa"),
        )!!
        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.EN_PROCESO))
        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.COMPLETADA))
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        val sentBefore = fake.pushedOps.size
        val appliedBefore = appliedLog.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val entregadoIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "ENTREGADO"
        }
        val lastActivitySent = sent.indexOfLast { it.str("entity_id") == a1 }
        assertTrue("la transición terminal viajó", entregadoIdx > 0)
        assertTrue("toda op de la actividad viajó antes del cierre",
            lastActivitySent in 0 until entregadoIdx)

        // Secuencia de aplicación (proxy de audit): la última op de la
        // actividad aplicó antes de ENTREGADO — orden causal preservado.
        assertTrue(lastAppliedIdxOfEntity(a1) in appliedBefore until
            appliedIdxOfJobStatus(jobId, "ENTREGADO"))
        // Cada op viajó exactamente una vez: actividad 3 envíos, job 2.
        assertEquals(3, fake.sentFor(EntityTypes.JOB_ACTIVITY, a1).size)
        assertEquals(2, sent.count { it.str("entity_id") == jobId })
        assertEquals("DONE", lastOp(EntityTypes.JOB_ACTIVITY, a1).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun entregado_multipleActivities_allApplyBeforeTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Multi")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Primera"),
        )!!
        val a2 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Segunda"),
        )!!
        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.COMPLETADA))
        // a2 queda PENDIENTE: su CREATE también es previo al cierre.
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val entregadoIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "ENTREGADO"
        }
        assertTrue(entregadoIdx > 0)
        // TODAS las ops previas de ambas actividades preceden al cierre.
        assertTrue(sent.indexOfLast { it.str("entity_id") == a1 } in 0 until entregadoIdx)
        assertTrue(sent.indexOfLast { it.str("entity_id") == a2 } in 0 until entregadoIdx)
        assertTrue(maxOf(lastAppliedIdxOfEntity(a1), lastAppliedIdxOfEntity(a2)) <
            appliedIdxOfJobStatus(jobId, "ENTREGADO"))
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun cancelado_activityOpsEnqueuedBefore_applyFirst() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Cancela")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        // BORRADOR es editable: la actividad se encola antes de cancelar.
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Pendiente al cancelar"),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.CANCELADO))

        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val cancelIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "CANCELADO"
        }
        assertTrue(cancelIdx > 0)
        assertTrue(sent.indexOfLast { it.str("entity_id") == a1 } in 0 until cancelIdx)
        assertTrue(lastAppliedIdxOfEntity(a1) < appliedIdxOfJobStatus(jobId, "CANCELADO"))
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun pausedActivity_terminalTransitionNeverSent() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Pausa")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Rechazable"),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        // El servidor rechaza la actividad: queda PAUSED y el cierre
        // no se adelanta ni produce un estado a medias.
        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.JOB_ACTIVITY) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "actividad inválida")
            } else {
                recordingResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        assertTrue("ENTREGADO nunca viajó",
            fake.sentFor(EntityTypes.JOB, jobId)
                .none { it.payloadStatus() == "ENTREGADO" })
        assertEquals("PAUSED", lastOp(EntityTypes.JOB_ACTIVITY, a1).state)
        assertEquals("PENDING", lastOp(EntityTypes.JOB, jobId).state)
        // EN_PROCESO sí aplicó (es previo y no depende de la actividad).
        assertTrue(appliedIdxOfJobStatus(jobId, "EN_PROCESO") >= 0)
    }

    @Test
    fun laterSequencedActivityOp_doesNotBlockTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Tardía")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))
        // Hija encolada DESPUÉS del cierre (seq mayor): el gate no espera
        // por ella — quien la retiene es la regla 2 (el padre tiene una
        // op pendiente anterior), así que aplica después, sin ciclo.
        val late = seedActivityBypassingRepo(jobId)

        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val entregadoIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "ENTREGADO"
        }
        val lateIdx = sent.indexOfFirst { it.str("entity_id") == late }
        assertTrue(entregadoIdx > 0)
        assertTrue("la hija posterior aplicó después del cierre",
            lateIdx > entregadoIdx)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB_ACTIVITY, late).state)
    }

    @Test
    fun unrelatedOpsProgress_whileTerminalBlocked() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Ajeno")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val jobId2 = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "Bloqueante"),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))
        assertNull(repo.setJobStatus(jobId2, JobStatus.CANCELADO))

        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.JOB_ACTIVITY) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "actividad inválida")
            } else {
                recordingResponder(op)
            }
        }
        sync.syncNow()

        // El Trabajo ajeno progresa pese al bloqueo del primero.
        assertTrue("CANCELADO del job ajeno aplicó",
            appliedIdxOfJobStatus(jobId2, "CANCELADO") >= 0)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId2).state)
        assertTrue(fake.sentFor(EntityTypes.JOB, jobId)
            .none { it.payloadStatus() == "ENTREGADO" })
        assertEquals("PENDING", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun reportJobGateUnaffected_noDeadlockWithTerminalWait() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Informe")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val a1 = repo.saveJobActivity(
            JobActivityInput(jobId = jobId, description = "En curso"),
        )!!
        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.EN_PROCESO))
        // Vínculo Informe↔Trabajo encolado ANTES de la transición terminal:
        // solo espera ops previas de sus referidos (regla anti-bucle),
        // nunca la transición posterior ni las actividades del trabajo.
        val reportId = repo.saveReport(
            ReportInput(
                clientId = client.id, profileId = profile.id,
                title = "Informe previo al cierre", jobIds = listOf(jobId),
            ),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val linkOps = fake.pushedOps.drop(sentBefore)
            .filter { it.str("entity_type") == EntityTypes.REPORT_JOB }
        assertEquals(1, linkOps.size)
        // El vínculo aplicó SIN esperar al cierre (que aún estaba retenido
        // por la actividad EN_PROCESO pendiente… resuelta al completarse? no:
        // aquí la actividad solo tiene ops previas que sí se aplican primero).
        val entregadoIdx = appliedIdxOfJobStatus(jobId, "ENTREGADO")
        val linkAppliedIdx = appliedLog.indexOfFirst {
            it.str("entity_type") == EntityTypes.REPORT_JOB
        }
        assertTrue(linkAppliedIdx >= 0)
        assertTrue("report_job aplicó antes de ENTREGADO — sin espera circular",
            linkAppliedIdx < entregadoIdx)
        assertEquals("DONE", lastOp(EntityTypes.REPORT, reportId).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
        // Y el orden causal del Trabajo siguió íntegro.
        assertTrue(lastAppliedIdxOfEntity(a1) < entregadoIdx)
    }

    // ---------- Regresión: materiales y consumos antes del cierre ----------

    /**
     * `job_material` descriptivo sembrado directamente (fila + op):
     * localmente el repo no puede crearlo sobre un Trabajo terminal.
     */
    private suspend fun seedMaterialBypassingRepo(jobId: String): String {
        val now = nowEpochMillis()
        val material = JobMaterialEntity(
            id = Ids.new(), jobId = jobId, description = "Material tardío",
            origin = MaterialOrigin.CLIENTE.name,
            createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.jobMaterialDao().upsert(material)
            sync.enqueue(
                EntityTypes.JOB_MATERIAL, material.id,
                SyncPayloads.jobMaterial(material), baseServerVersion = 0,
            )
        }
        return material.id
    }

    /**
     * `inventory_movement` sembrado directamente (fila + op) con el
     * `jobId` dado — reproduce tanto consumos tardíos como movimientos
     * que solo REFERENCIAN el trabajo (p.ej. `SOBRANTE`).
     */
    private suspend fun seedMovementBypassingRepo(
        jobId: String,
        movementType: MovementType,
        origin: MovementOrigin,
        materialId: String,
        jobMaterialId: String? = null,
    ): String {
        val now = nowEpochMillis()
        val mov = InventoryMovementEntity(
            id = Ids.new(), userId = db.userDao().get()!!.id,
            materialId = materialId, condition = MaterialCondition.NUEVO.name,
            movementType = movementType.name, origin = origin.name,
            quantity = "1", unit = "ud",
            jobId = jobId, jobMaterialId = jobMaterialId,
            occurredAt = now, createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.inventoryMovementDao().upsert(mov)
            sync.enqueue(
                EntityTypes.INVENTORY_MOVEMENT, mov.id,
                SyncPayloads.inventoryMovement(mov), baseServerVersion = 0,
            )
        }
        return mov.id
    }

    private suspend fun consumptionIdsForJob(jobId: String): List<String> =
        db.inventoryMovementDao().observeForJob(jobId).first()
            .filter { it.movementType == MovementType.JOB_CONSUMPTION.name }
            .map { it.id }

    @Test
    fun entregado_descriptiveMaterialsEnqueuedBefore_applyFirst() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente MatDesc")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val m1 = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Material previo 1",
                origin = MaterialOrigin.CLIENTE,
            ),
        )!!
        val m2 = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Material previo 2",
                origin = MaterialOrigin.PROFESIONAL, quantity = "3", unit = "ud",
            ),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val entregadoIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "ENTREGADO"
        }
        assertTrue(entregadoIdx > 0)
        assertTrue(sent.indexOfLast { it.str("entity_id") == m1 } in 0 until entregadoIdx)
        assertTrue(sent.indexOfLast { it.str("entity_id") == m2 } in 0 until entregadoIdx)
        assertTrue(maxOf(lastAppliedIdxOfEntity(m1), lastAppliedIdxOfEntity(m2)) <
            appliedIdxOfJobStatus(jobId, "ENTREGADO"))
        assertEquals("DONE", lastOp(EntityTypes.JOB_MATERIAL, m1).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB_MATERIAL, m2).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun entregado_linkedMaterialAndConsumption_applyFirstInOrder() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente MatVinc")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val catalogId = repo.saveMaterial(MaterialInput(name = "Cable -MT", unit = "ud"))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow() // trabajo, cliente, perfil y material de catálogo aplicados

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val jm = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Consumo vinculado",
                origin = MaterialOrigin.PROFESIONAL, quantity = "2", unit = "ud",
                materialId = catalogId, condition = MaterialCondition.NUEVO,
            ),
        )!!
        val movId = consumptionIdsForJob(jobId).single()
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        sync.syncNow()

        // Orden causal completo: material → su consumo → cierre.
        val entregadoIdx = appliedIdxOfJobStatus(jobId, "ENTREGADO")
        assertTrue(lastAppliedIdxOfEntity(jm) < lastAppliedIdxOfEntity(movId))
        assertTrue(lastAppliedIdxOfEntity(movId) < entregadoIdx)
        assertTrue("ENTREGADO viajó tras material y consumo", entregadoIdx > 0)
        assertEquals(1, fake.sentFor(EntityTypes.INVENTORY_MOVEMENT, movId).size)
        assertEquals("DONE", lastOp(EntityTypes.JOB_MATERIAL, jm).state)
        assertEquals("DONE", lastOp(EntityTypes.INVENTORY_MOVEMENT, movId).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun cancelado_descriptiveMaterialEnqueuedBefore_appliesFirst() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente MatCanc")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val m1 = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Material al cancelar",
                origin = MaterialOrigin.CLIENTE,
            ),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.CANCELADO))

        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        val cancelIdx = appliedIdxOfJobStatus(jobId, "CANCELADO")
        assertTrue(cancelIdx > 0)
        assertTrue(lastAppliedIdxOfEntity(m1) in 0 until cancelIdx)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun pausedDescriptiveMaterial_holdsTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente MatPausa")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val m1 = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Rechazable",
                origin = MaterialOrigin.CLIENTE,
            ),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.JOB_MATERIAL) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "material inválido")
            } else {
                recordingResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        assertTrue("ENTREGADO nunca viajó",
            fake.sentFor(EntityTypes.JOB, jobId)
                .none { it.payloadStatus() == "ENTREGADO" })
        assertEquals("PAUSED", lastOp(EntityTypes.JOB_MATERIAL, m1).state)
        assertEquals("PENDING", lastOp(EntityTypes.JOB, jobId).state)
        assertTrue(appliedIdxOfJobStatus(jobId, "EN_PROCESO") >= 0)
    }

    @Test
    fun pausedConsumption_holdsTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente ConsPausa")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val catalogId = repo.saveMaterial(MaterialInput(name = "Cable -MT2", unit = "ud"))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val jm = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Consumo rechazable",
                origin = MaterialOrigin.PROFESIONAL, quantity = "1", unit = "ud",
                materialId = catalogId, condition = MaterialCondition.NUEVO,
            ),
        )!!
        val movId = consumptionIdsForJob(jobId).single()
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.INVENTORY_MOVEMENT) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "consumo inválido")
            } else {
                recordingResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        // El material aplicó pero el consumo quedó pausado → el cierre
        // no se adelanta ni deja un estado a medias.
        assertTrue(lastAppliedIdxOfEntity(jm) >= 0)
        assertTrue("ENTREGADO nunca viajó",
            fake.sentFor(EntityTypes.JOB, jobId)
                .none { it.payloadStatus() == "ENTREGADO" })
        assertEquals("PAUSED", lastOp(EntityTypes.INVENTORY_MOVEMENT, movId).state)
        assertEquals("PENDING", lastOp(EntityTypes.JOB, jobId).state)
    }

    @Test
    fun laterSequencedMaterialOps_applyAfterTerminal_noCycle() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente MatTarde")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val catalogId = repo.saveMaterial(MaterialInput(name = "Cable -MT3", unit = "ud"))!!
        fake.scripted = true
        fake.respond = recordingResponder
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))
        // Hijos encolados DESPUÉS del cierre (seq mayor): el gate no los
        // espera — quien los retiene es la regla 2 (el padre tiene una
        // op pendiente anterior), así que aplican después, sin ciclo.
        val lateMat = seedMaterialBypassingRepo(jobId)
        val lateMov = seedMovementBypassingRepo(
            jobId, MovementType.JOB_CONSUMPTION, MovementOrigin.TRABAJO,
            materialId = catalogId, jobMaterialId = lateMat,
        )

        val sentBefore = fake.pushedOps.size
        sync.syncNow()

        val sent = fake.pushedOps.drop(sentBefore)
        val entregadoIdx = sent.indexOfFirst {
            it.str("entity_id") == jobId && it.payloadStatus() == "ENTREGADO"
        }
        assertTrue(entregadoIdx > 0)
        assertTrue(sent.indexOfFirst { it.str("entity_id") == lateMat } > entregadoIdx)
        assertTrue(sent.indexOfFirst { it.str("entity_id") == lateMov } > entregadoIdx)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
        assertEquals("DONE", lastOp(EntityTypes.JOB_MATERIAL, lateMat).state)
        assertEquals("DONE", lastOp(EntityTypes.INVENTORY_MOVEMENT, lateMov).state)
    }

    @Test
    fun sobranteReferencingJob_doesNotBlockTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente Sobrante")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val catalogId = repo.saveMaterial(MaterialInput(name = "Cable -MT4", unit = "ud"))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        // Movimiento que REFERENCIA el trabajo pero no es hijo de su
        // ciclo (AJUSTE/SOBRANTE): encolado antes del cierre y pausado,
        // no debe retener la transición terminal.
        val sob = seedMovementBypassingRepo(
            jobId, MovementType.ADJUSTMENT_IN, MovementOrigin.SOBRANTE,
            materialId = catalogId,
        )
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        fake.respond = { op ->
            if (op.str("entity_type") == EntityTypes.INVENTORY_MOVEMENT) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "sobrante inválido")
            } else {
                recordingResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        assertTrue("ENTREGADO aplicó pese al sobrante pausado",
            appliedIdxOfJobStatus(jobId, "ENTREGADO") >= 0)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
        assertEquals("PAUSED", lastOp(EntityTypes.INVENTORY_MOVEMENT, sob).state)
    }

    @Test
    fun otherJobMaterialOps_doNotBlockTerminal() = runBlocking {
        val (client, profile) = seedClientProfile("Cliente AjenoMat")
        val jobId = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        val jobId2 = repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
        fake.scripted = true
        appliedLog.clear()
        sync.syncNow()

        assertNull(repo.setJobStatus(jobId, JobStatus.EN_PROCESO))
        val mOther = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId2, description = "Material del otro trabajo",
                origin = MaterialOrigin.CLIENTE,
            ),
        )!!
        assertNull(repo.setJobStatus(jobId, JobStatus.ENTREGADO))

        fake.respond = { op ->
            if (op.str("entity_id") == mOther) {
                appliedLog += op
                FakeSyncApi.rejected(op, "VALIDATION_ERROR", "material inválido")
            } else {
                recordingResponder(op)
            }
        }
        repeat(2) { sync.syncNow() }

        // El material pausado del OTRO trabajo no retiene este cierre.
        assertTrue(appliedIdxOfJobStatus(jobId, "ENTREGADO") >= 0)
        assertEquals("DONE", lastOp(EntityTypes.JOB, jobId).state)
        assertEquals("PAUSED", lastOp(EntityTypes.JOB_MATERIAL, mOther).state)
    }
}

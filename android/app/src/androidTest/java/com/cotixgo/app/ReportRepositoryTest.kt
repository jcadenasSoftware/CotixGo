package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.ReportDestination
import com.cotixgo.app.core.ReportStatus
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.ReportInput
import com.cotixgo.app.data.SnapshotJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Pruebas del repositorio de Informes F5 sobre Room real (BD en memoria):
 * agrupación Cliente+Perfil, título y ≥1 Trabajo, contenido solo con
 * actividades COMPLETADA, emisión con snapshot inmutable, corrección como
 * versión enlazada nueva, archivo sin borrado y vínculos en BORRADOR.
 * Datos exclusivamente sintéticos.
 */
@RunWith(AndroidJUnit4::class)
class ReportRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).build()
        repo = AppRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(): Triple<String, String, String> {
        repo.completeOnboarding("Test User", "Perfil Test")
        val user = db.userDao().observe().first()!!
        val profile = db.profileDao().observeForUser(user.id).first().first()
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente T"))
        val client = db.clientDao().observeForUser(user.id).first().first()
        return Triple(user.id, profile.id, client.id)
    }

    private fun jobInput(clientId: String, profileId: String) = JobInput(
        clientId = clientId,
        profileId = profileId,
        startDateEpochDay = LocalDate.now().toEpochDay(),
        executionAddress = "Obra 1",
        notes = "sintético",
    )

    private fun reportInput(clientId: String, profileId: String, jobIds: List<String>) = ReportInput(
        clientId = clientId,
        profileId = profileId,
        title = "Informe de avance",
        destination = ReportDestination.CLIENTE,
        jobIds = jobIds,
    )

    @Test
    fun crear_borrador_conProvisionalINF_yContenidoGenerado() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val a = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Instalación"))!!
        repo.setJobActivityStatus(a, JobActivityStatus.EN_PROCESO)
        repo.setJobActivityStatus(a, JobActivityStatus.COMPLETADA)

        val id = repo.saveReport(reportInput(clientId, profileId, listOf(jobId)))!!
        val r = db.reportDao().get(id)!!
        assertEquals(ReportStatus.BORRADOR.name, r.status)
        assertTrue(r.provisionalNumber.startsWith("INF-PEND-"))
        assertNull(r.officialNumber)
        assertEquals("Cliente T", r.clientNameSnapshot)
        assertTrue(r.content.contains("Instalación"))
        assertTrue(r.content.contains("Actividades completadas"))
        assertEquals(1, db.reportJobDao().forReport(id).size)
    }

    @Test
    fun crear_validaciones_tituloTrabajoYAgrupacion() = runTest {
        val (userId, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!

        // Título obligatorio
        assertNull(
            repo.saveReport(reportInput(clientId, profileId, listOf(jobId)).copy(title = " ")),
        )
        // Al menos un Trabajo
        assertNull(repo.saveReport(reportInput(clientId, profileId, emptyList())))
        // Trabajo inexistente
        assertNull(repo.saveReport(reportInput(clientId, profileId, listOf("no-existe"))))
        // Trabajo de otro Cliente → viola la agrupación Cliente+Perfil
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        assertNull(repo.saveReport(reportInput(clientId, profileId, listOf(jobId))
            .copy(clientId = clienteB.id)))
    }

    @Test
    fun contenido_soloActividadesCompletadasComoEjecutadas() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val done = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Terminada"))!!
        repo.setJobActivityStatus(done, JobActivityStatus.EN_PROCESO)
        repo.setJobActivityStatus(done, JobActivityStatus.COMPLETADA)
        repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Pendiente X"))
        val enProc = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "En curso Y"))!!
        repo.setJobActivityStatus(enProc, JobActivityStatus.EN_PROCESO)

        val generated = repo.buildReportContent(listOf(jobId))
        assertTrue(generated.contains("Terminada"))
        assertFalse(generated.contains("Pendiente X"))
        assertFalse(generated.contains("En curso Y"))

        // Materiales y observaciones viajan como contenido descriptivo.
        repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Tubo PVC", origin = MaterialOrigin.PROFESIONAL,
                quantity = "3", unit = "m", notes = "",
            ),
        )
        val withMaterial = repo.buildReportContent(listOf(jobId))
        assertTrue(withMaterial.contains("Tubo PVC"))
        assertTrue(withMaterial.contains("sintético"))
    }

    @Test
    fun emitir_creaVersionYSnapshotInmutable_correccionEnlaza() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val a = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Hecha"))!!
        repo.setJobActivityStatus(a, JobActivityStatus.EN_PROCESO)
        repo.setJobActivityStatus(a, JobActivityStatus.COMPLETADA)
        repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Sin hacer"))

        val id = repo.saveReport(reportInput(clientId, profileId, listOf(jobId)))!!
        assertTrue(repo.emitReport(id))

        val emitted = db.reportDao().get(id)!!
        assertEquals(ReportStatus.EMITIDO.name, emitted.status)
        assertEquals(1, emitted.currentVersion)
        assertEquals(1, emitted.emittedVersion)

        val v1 = db.reportVersionDao().getLatest(id)!!
        assertEquals(1, v1.version)
        assertNull(v1.previousVersionId)
        val snap1 = SnapshotJson.decodeReport(v1.snapshotJson)
        assertEquals("Informe de avance", snap1.title)
        assertEquals(1, snap1.jobs.size)
        // Solo COMPLETADA como ejecución en el snapshot congelado.
        assertEquals(1, snap1.jobs[0].completedActivities.size)
        assertEquals("Hecha", snap1.jobs[0].completedActivities[0].description)

        // Emitido no editable
        assertNull(
            repo.saveReport(
                reportInput(clientId, profileId, listOf(jobId)).copy(title = "Otro título"),
                existingId = id,
            ),
        )
        assertFalse(repo.emitReport(id)) // no re-emisión directa

        // Corrección: reabre el borrador; la versión emitida queda intacta.
        assertTrue(repo.correctReport(id))
        assertEquals(ReportStatus.BORRADOR.name, db.reportDao().get(id)!!.status)
        assertNotNull(db.reportVersionDao().get(v1.id)) // snapshot previo intacto

        // Editar y re-emitir → versión nueva enlazada, misma entidad.
        repo.saveReport(
            reportInput(clientId, profileId, listOf(jobId))
                .copy(title = "Informe corregido", content = "Contenido corregido"),
            existingId = id,
        )
        assertTrue(repo.emitReport(id))
        val v2 = db.reportVersionDao().getLatest(id)!!
        assertEquals(2, v2.version)
        assertEquals(v1.id, v2.previousVersionId)
        assertEquals("Informe corregido", SnapshotJson.decodeReport(v2.snapshotJson).title)
        // v1 permanece inmutable con su contenido original.
        assertEquals("Informe de avance", SnapshotJson.decodeReport(
            db.reportVersionDao().get(v1.id)!!.snapshotJson).title)
    }

    @Test
    fun emitir_sinTrabajos_rechaza_yLinkSoloEnBorradorMismaAgrupacion() = runTest {
        val (userId, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val id = repo.saveReport(reportInput(clientId, profileId, listOf(jobId)))!!

        // Segundo trabajo del mismo Cliente+Perfil → vínculo válido.
        val job2 = repo.saveJob(jobInput(clientId, profileId))!!
        assertTrue(repo.linkJobToReport(id, job2))
        assertEquals(2, db.reportJobDao().forReport(id).size)
        // Duplicado → no
        assertFalse(repo.linkJobToReport(id, job2))

        // Trabajo de otro Cliente → la regla agrupadora lo rechaza.
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        val jobB = repo.saveJob(jobInput(clienteB.id, profileId))!!
        assertFalse(repo.linkJobToReport(id, jobB))

        // Emitido: no se admiten vínculos nuevos (inmutable).
        assertTrue(repo.emitReport(id))
        val job3 = repo.saveJob(jobInput(clientId, profileId))!!
        assertFalse(repo.linkJobToReport(id, job3))
    }

    @Test
    fun archivar_desarchivar_sinBorrarHistorial() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val id = repo.saveReport(reportInput(clientId, profileId, listOf(jobId)))!!
        repo.emitReport(id)

        repo.setReportArchived(id, true)
        assertTrue(db.reportDao().get(id)!!.archived)
        repo.setReportArchived(id, false)
        assertFalse(db.reportDao().get(id)!!.archived)
        assertNotNull(db.reportVersionDao().getLatest(id)) // historial intacto
    }

    @Test
    fun editarClienteOPerfil_conVinculos_rechaza() = runTest {
        val (userId, profileId, clientId) = seed()
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val id = repo.saveReport(reportInput(clientId, profileId, listOf(jobId)))!!

        // Con vínculos activos el Cliente no se recambia (invalidaría la agrupación).
        assertNull(repo.saveReport(reportInput(clienteB.id, profileId, listOf(jobId)), existingId = id))
        assertEquals(clientId, db.reportDao().get(id)!!.clientId)
    }
}

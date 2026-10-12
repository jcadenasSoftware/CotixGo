package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
import com.cotixgo.app.data.RetentionInput
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
 * Pruebas del repositorio de Trabajos F4 sobre Room real (BD en memoria):
 * matriz aprobada, dirección al programar, entrega, terminales sin edición,
 * actividades, materiales descriptivos, vínculo con APROBADA y archivar.
 * Datos exclusivamente sintéticos.
 */
@RunWith(AndroidJUnit4::class)
class JobRepositoryTest {

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

    private fun jobInput(clientId: String, profileId: String, address: String = "") = JobInput(
        clientId = clientId,
        profileId = profileId,
        startDateEpochDay = LocalDate.now().toEpochDay(),
        estimatedDateEpochDay = LocalDate.now().plusDays(5).toEpochDay(),
        executionAddress = address,
        notes = "sintético",
    )

    @Test
    fun createJob_borrador_conNumeroProvisionalTR() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveJob(jobInput(clientId, profileId))!!
        val job = db.jobDao().get(id)!!
        assertEquals(JobStatus.BORRADOR.name, job.status)
        assertTrue(job.provisionalNumber.startsWith("TR-PEND-"))
        assertNull(job.officialNumber)
        assertEquals("Cliente T", job.clientNameSnapshot)
    }

    @Test
    fun programar_sinDireccion_rechaza_yConDireccion_aplica() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveJob(jobInput(clientId, profileId))!!
        assertNotNull(repo.setJobStatus(id, JobStatus.PROGRAMADO))
        repo.saveJob(jobInput(clientId, profileId, "Calle 1 #2-3"), id)
        assertNull(repo.setJobStatus(id, JobStatus.PROGRAMADO))
        assertEquals(JobStatus.PROGRAMADO.name, db.jobDao().get(id)!!.status)
    }

    @Test
    fun matriz_completa_yTerminalesSinEdicion() = runTest {
        val (_, profileId, clientId) = seed()

        // BORRADOR→EN_PROCESO directo sin dirección (la regla es solo PROGRAMADO)
        val j1 = repo.saveJob(jobInput(clientId, profileId))!!
        assertNull(repo.setJobStatus(j1, JobStatus.EN_PROCESO))
        // EN_PROCESO→PROGRAMADO no existe
        assertNotNull(repo.setJobStatus(j1, JobStatus.PROGRAMADO))
        // EN_PROCESO→ENTREGADO registra fecha de entrega
        assertNull(repo.setJobStatus(j1, JobStatus.ENTREGADO))
        val entregado = db.jobDao().get(j1)!!
        assertNotNull(entregado.deliveryDateEpochDay)
        // Terminal: ni transición ni edición
        assertNotNull(repo.setJobStatus(j1, JobStatus.CANCELADO))
        assertNull(repo.saveJob(jobInput(clientId, profileId, "otra dir"), j1))

        // PROGRAMADO→BORRADOR reversa y →CANCELADO
        val j2 = repo.saveJob(jobInput(clientId, profileId, "Dir"))!!
        assertNull(repo.setJobStatus(j2, JobStatus.PROGRAMADO))
        assertNull(repo.setJobStatus(j2, JobStatus.BORRADOR))
        assertNull(repo.setJobStatus(j2, JobStatus.CANCELADO))
        assertNull(repo.saveJob(jobInput(clientId, profileId), j2))

        // BORRADOR→ENTREGADO no existe
        val j3 = repo.saveJob(jobInput(clientId, profileId))!!
        assertNotNull(repo.setJobStatus(j3, JobStatus.ENTREGADO))
    }

    @Test
    fun actividades_estadosYMarcasDeTiempo() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val a1 = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Visita"))!!
        assertEquals(JobActivityStatus.PENDIENTE.name, db.jobActivityDao().get(a1)!!.status)

        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.EN_PROCESO))
        assertNotNull(db.jobActivityDao().get(a1)!!.startedAt)
        assertTrue(repo.setJobActivityStatus(a1, JobActivityStatus.COMPLETADA))
        assertNotNull(db.jobActivityDao().get(a1)!!.finishedAt)

        // Posiciones incrementales
        val a2 = repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Otra"))!!
        assertTrue(db.jobActivityDao().get(a2)!!.position > db.jobActivityDao().get(a1)!!.position)

        // Trabajo terminal bloquea actividades nuevas
        repo.saveJob(jobInput(clientId, profileId, "Dir"), jobId)
        repo.setJobStatus(jobId, JobStatus.PROGRAMADO)
        repo.setJobStatus(jobId, JobStatus.CANCELADO)
        assertNull(repo.saveJobActivity(JobActivityInput(jobId = jobId, description = "Tarde")))
    }

    @Test
    fun materiales_descriptivos_sinInventario() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val m1 = repo.saveJobMaterial(
            JobMaterialInput(
                jobId = jobId, description = "Cable 12", origin = MaterialOrigin.CLIENTE,
                quantity = "10", unit = "m", notes = "entregado en obra",
            ),
        )!!
        val m = db.jobMaterialDao().get(m1)!!
        assertEquals("CLIENTE", m.origin)
        assertEquals("10", m.quantity)
    }

    @Test
    fun crearDesdeQuoteAprobada_vinculaOrigen() = runTest {
        val (_, profileId, clientId) = seed()
        val quoteId = repo.saveQuote(
            QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = LocalDate.now().toEpochDay(), validityDays = 15,
                currency = "COP",
                lines = listOf(QuoteLineInput(ConceptType.SERVICIO, "Svc", "1", "und", "100")),
                retentions = emptyList(),
            ),
        )!!
        // No aprobada → no origina trabajo
        assertNull(repo.createJobFromQuote(quoteId))
        repo.emitQuote(quoteId)
        repo.setQuoteStatus(quoteId, QuoteStatus.APROBADA)
        val jobId = repo.createJobFromQuote(quoteId, "Obra Centro")!!
        val job = db.jobDao().get(jobId)!!
        assertEquals(clientId, job.clientId)
        assertEquals("Obra Centro", job.executionAddress)
        val links = db.jobQuoteDao().forJob(jobId)
        assertEquals(1, links.size)
        assertEquals("ORIGEN", links[0].relationType)
        assertEquals(quoteId, links[0].quoteId)
    }

    @Test
    fun vincularAdicional_soloAprobadaMismoCliente() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val q = repo.saveQuote(
            QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = LocalDate.now().toEpochDay(), validityDays = 15,
                currency = "COP",
                lines = listOf(QuoteLineInput(ConceptType.SERVICIO, "Svc", "1", "und", "100")),
                retentions = emptyList(),
            ),
        )!!
        // EMITIDA (no APROBADA) → no vinculable
        repo.emitQuote(q)
        assertEquals(false, repo.linkQuoteToJob(jobId, q))
        repo.setQuoteStatus(q, QuoteStatus.APROBADA)
        assertTrue(repo.linkQuoteToJob(jobId, q))
        assertEquals("ADICIONAL", db.jobQuoteDao().forJob(jobId)[0].relationType)
        // Duplicado → no
        assertEquals(false, repo.linkQuoteToJob(jobId, q))
    }

    /** Corrección revisión F4: recambiar el Cliente con vínculos activos los
     *  invalidaría — el repositorio lo bloquea, no solo la UI. */
    @Test
    fun editarCliente_conVinculos_rechaza_sinVinculos_permite() = runTest {
        val (userId, profileId, clientId) = seed()
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }

        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        val q = repo.saveQuote(
            QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = LocalDate.now().toEpochDay(), validityDays = 15,
                currency = "COP",
                lines = listOf(QuoteLineInput(ConceptType.SERVICIO, "Svc", "1", "und", "100")),
                retentions = emptyList(),
            ),
        )!!
        repo.emitQuote(q)
        repo.setQuoteStatus(q, QuoteStatus.APROBADA)
        assertTrue(repo.linkQuoteToJob(jobId, q))

        // Con vínculo activo: el cambio de Cliente queda bloqueado.
        assertNull(repo.saveJob(jobInput(clienteB.id, profileId), jobId))
        assertEquals(clientId, db.jobDao().get(jobId)!!.clientId)

        // Sin vínculos el cambio es válido (no invalida ninguna asociación).
        val job2 = repo.saveJob(jobInput(clientId, profileId))!!
        assertNotNull(repo.saveJob(jobInput(clienteB.id, profileId), job2))
        assertEquals(clienteB.id, db.jobDao().get(job2)!!.clientId)
    }

    @Test
    fun archivar_desarchivar_sinBorrarHistorial() = runTest {
        val (_, profileId, clientId) = seed()
        val jobId = repo.saveJob(jobInput(clientId, profileId))!!
        repo.setJobArchived(jobId, true)
        assertTrue(db.jobDao().get(jobId)!!.archived)
        repo.setJobArchived(jobId, false)
        assertEquals(false, db.jobDao().get(jobId)!!.archived)
        assertNotNull(db.jobDao().get(jobId)) // nada se borra
    }
}

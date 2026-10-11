package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.toQty
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.SnapshotJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
import java.time.LocalDate

/**
 * Pruebas del repositorio F2 sobre Room real (BD en memoria): versiones
 * append-only, snapshot histórico, transiciones, vencimiento materializado,
 * revalidación y archivar/desarchivar.
 */
@RunWith(AndroidJUnit4::class)
class QuoteRepositoryTest {

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
        repo.saveClient(com.cotixgo.app.core.ClientInput(
            type = com.cotixgo.app.core.ClientType.PERSONA, name = "Cliente X"))
        val client = db.clientDao().observeForUser(user.id).first().first()
        return Triple(user.id, profile.id, client.id)
    }

    private fun input(profileId: String, clientId: String, days: Int = 30) = QuoteInput(
        clientId = clientId,
        profileId = profileId,
        issueDateEpochDay = LocalDate.now().toEpochDay(),
        validityDays = days,
        currency = "COP",
        notes = "obs",
        lines = listOf(
            QuoteLineInput(ConceptType.SERVICIO, "Mano de obra", "1", "und", "600000"),
            QuoteLineInput(ConceptType.MATERIAL, "Cable", "1", "m", "800000",
                materialOrigin = com.cotixgo.app.core.MaterialOrigin.PROFESIONAL),
        ),
        retentions = listOf(RetentionInput("Ret", "6", setOf(ConceptType.SERVICIO))),
    )

    @Test
    fun saveQuote_createsVersionAndProvisionalNumber() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveQuote(input(profileId, clientId))!!
        val quote = repo.observeQuote(id).first()!!
        assertEquals(QuoteStatus.BORRADOR.name, quote.status)
        assertTrue(quote.provisionalNumber.matches(Regex("^COT-PEND-[A-Z0-9]{4}$")))
        assertEquals(1, quote.currentVersion)
        val versions = repo.observeQuoteVersions(id).first()
        assertEquals(1, versions.size)
        val snap = SnapshotJson.decodeQuote(versions[0].snapshotJson)
        assertEquals("Cliente X", snap.client.name)
        assertEquals("Perfil Test", snap.profile.businessName)
        assertEquals("1400000.00", snap.gross)
        assertEquals("1364000.00", snap.net)
        assertEquals("36000.00", snap.retentions[0].amount)
    }

    @Test
    fun eachSave_appendsVersionWithoutRewriting() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveQuote(input(profileId, clientId))!!
        repo.saveQuote(input(profileId, clientId).copy(notes = "v2"), id)
        repo.saveQuote(input(profileId, clientId).copy(notes = "v3"), id)
        val versions = repo.observeQuoteVersions(id).first()
        assertEquals(3, versions.size)
        assertEquals(3, repo.observeQuote(id).first()!!.currentVersion)
        val snaps = versions.map { SnapshotJson.decodeQuote(it.snapshotJson) }
        assertEquals(listOf("obs", "v2", "v3"), snaps.map { it.notes })
    }

    @Test
    fun emit_requiresLine_andMarksEmittedVersion() = runTest {
        val (_, profileId, clientId) = seed()
        // BORRADOR sin líneas: permitido guardar, no emitir.
        val emptyId = repo.saveQuote(input(profileId, clientId).copy(lines = emptyList()))!!
        assertFalse(repo.emitQuote(emptyId))
        val id = repo.saveQuote(input(profileId, clientId))!!
        assertTrue(repo.emitQuote(id))
        val quote = repo.observeQuote(id).first()!!
        assertEquals(QuoteStatus.EMITIDA.name, quote.status)
        assertEquals(quote.currentVersion, quote.emittedVersion)
        // La aprobación es manual del profesional.
        assertTrue(repo.setQuoteStatus(id, QuoteStatus.APROBADA))
        // APROBADA es terminal en F2.
        assertFalse(repo.setQuoteStatus(id, QuoteStatus.CANCELADA))
        assertFalse(repo.setQuoteStatus(id, QuoteStatus.RECHAZADA))
    }

    @Test
    fun rejectAndRevert_toEmitida() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveQuote(input(profileId, clientId))!!
        repo.emitQuote(id)
        assertTrue(repo.setQuoteStatus(id, QuoteStatus.RECHAZADA))
        assertTrue(repo.setQuoteStatus(id, QuoteStatus.EMITIDA))
        assertTrue(repo.setQuoteStatus(id, QuoteStatus.CANCELADA))
    }

    @Test
    fun expiredQuote_materializesOnNextWrite_andCannotEmit() = runTest {
        val (_, profileId, clientId) = seed()
        // Creada "ayer" con 1 día de vigencia → vencida hoy+? Fecha -10, vigencia 5.
        val past = input(profileId, clientId).copy(
            issueDateEpochDay = LocalDate.now().toEpochDay() - 10,
            validityDays = 5,
        )
        val id = repo.saveQuote(input = past)!!
        // Deriva VENCIDA al intentar emitir (fecha local > último día válido).
        assertFalse(repo.emitQuote(id))
        assertEquals(QuoteStatus.VENCIDA.name, repo.observeQuote(id).first()!!.status)
        // Tampoco puede aprobarse ni editarse.
        assertFalse(repo.setQuoteStatus(id, QuoteStatus.APROBADA))
    }

    @Test
    fun revalidate_createsNewQuote_previousIntact() = runTest {
        val (_, profileId, clientId) = seed()
        val past = input(profileId, clientId).copy(
            issueDateEpochDay = LocalDate.now().toEpochDay() - 10,
            validityDays = 5,
        )
        val id = repo.saveQuote(input = past)!!
        repo.emitQuote(id) // materializa VENCIDA
        val newId = repo.revalidateQuote(id, 15)!!
        assertNotEquals(id, newId)
        val old = repo.observeQuote(id).first()!!
        val new = repo.observeQuote(newId).first()!!
        assertEquals(QuoteStatus.VENCIDA.name, old.status) // anterior intacta
        assertEquals(QuoteStatus.BORRADOR.name, new.status)
        assertNotEquals(old.provisionalNumber, new.provisionalNumber)
        assertEquals(15, new.expiryDateEpochDay - new.issueDateEpochDay + 1)
        assertEquals(LocalDate.now().toEpochDay(), new.issueDateEpochDay)
        // El contenido se hereda de la última versión.
        val newSnap = repo.getQuoteSnapshot(newId)!!
        assertEquals("Cliente X", newSnap.client.name)
        assertEquals(2, newSnap.lines.size)
    }

    @Test
    fun archiveTogglesVisibilityWithoutDeleting() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveQuote(input(profileId, clientId))!!
        repo.setQuoteArchived(id, true)
        assertTrue(repo.observeQuote(id).first()!!.archived)
        repo.setQuoteArchived(id, false)
        assertFalse(repo.observeQuote(id).first()!!.archived)
        assertNotNull(repo.getQuoteSnapshot(id))
    }

    @Test
    fun snapshot_immuneToCatalogAndRetentionChanges() = runTest {
        val (userId, profileId, clientId) = seed()
        repo.saveRetention(RetentionInput("R6", "6", setOf(ConceptType.SERVICIO)))
        val id = repo.saveQuote(input(profileId, clientId))!!
        repo.emitQuote(id)
        // Cambiar la configuración vigente no altera el documento emitido.
        val cfg = db.retentionDao().observeForUser(userId).first().first()
        repo.saveRetention(RetentionInput("R6", "25", setOf(ConceptType.SERVICIO, ConceptType.OTRO)), cfg.id)
        val snap = repo.getQuoteSnapshot(id)!!
        assertEquals("6", snap.retentions[0].ratePercent)
        assertEquals("1364000.00", snap.net)
    }
}

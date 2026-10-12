package com.cotixgo.app

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
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
        val res = api.register("E2E Bot", email, "synthetic-pass-123")
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

        // El usuario resuelve conservando la versión 1 → versión nueva 2.
        assertTrue(sync.resolveQuoteVersions(quoteId, 1))
        val versions = db.quoteVersionDao().observeForQuote(quoteId).first()
        assertEquals(2, versions.size) // v1 original + v2 resolución
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
}

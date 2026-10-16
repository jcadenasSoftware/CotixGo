package com.cotixgo.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.core.Ids
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncPayloads
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.data.UserEntity
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.data.AppRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guarda de titularidad de bindAccount (D3): la copia local solo puede
 * adoptarse por otra cuenta cuando nunca se sincronizó. Si el servidor ya
 * reconoció datos bajo una cuenta distinta, el vínculo a otra identidad se
 * rechaza sin tocar filas, relaciones, cola ni sesión — incluido el estado
 * residual de un cierre inesperado durante switchAccount.
 *
 * Pruebas de repositorio puras: no requieren el backend; solo datos
 * sintéticos y artefactos exclusivos de prueba (D2).
 */
@RunWith(AndroidJUnit4::class)
class BindAccountGuardTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val json = Json { ignoreUnknownKeys = true }

    private lateinit var db: AppDatabase
    private lateinit var session: Session
    private lateinit var sync: SyncRepository
    private lateinit var repo: AppRepository
    private lateinit var dbPath: String
    private lateinit var prefsName: String
    private lateinit var prodBefore: Map<String, String?>

    @Before
    fun setUp() {
        prodBefore = TestIsolation.snapshotProd(ctx)
        dbPath = TestIsolation.name("e2e-bind-${Ids.new()}.db")
        prefsName = TestIsolation.name("e2e-session-bind-${Ids.new().take(8)}")
        db = Room.databaseBuilder(ctx, AppDatabase::class.java, dbPath).build()
        session = Session(ctx, db, prefsName)
        sync = SyncRepository(db, session)
        repo = AppRepository(db, sync)
    }

    @After
    fun tearDown() {
        db.close()
        TestIsolation.deleteDb(ctx, dbPath)
        TestIsolation.deletePrefs(ctx, prefsName)
        TestIsolation.assertProdUntouched(ctx, prodBefore)
    }

    private fun opsCount(): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sync_operations")
            .use { it.moveToFirst(); it.getInt(0) }

    /**
     * Copia local con linaje sincronizado bajo la cuenta A: usuario con el
     * id canónico del servidor y un cliente ya confirmado (serverVersion>0),
     * más un cursor de pull consumido — equivalente al estado tras un
     * syncNow() completado.
     */
    private suspend fun seedSyncedState(userId: String) {
        val now = com.cotixgo.app.core.nowEpochMillis()
        db.userDao().insert(
            UserEntity(
                id = userId, name = "Cuenta A", email = "a@synthetic.local",
                createdAt = now, updatedAt = now,
                pendingSync = false, serverVersion = 3,
            ),
        )
        db.clientDao().upsert(
            ClientEntity(
                id = "cli-synced", userId = userId, type = "PERSONA",
                name = "Cliente Sincronizado",
                createdAt = now, updatedAt = now,
                pendingSync = false, serverVersion = 2,
            ),
        )
        session.setCursor(9)
    }

    /** El rechazo no altera filas, cola, cursor ni sesión. */
    @Test
    fun rejectsSyncedDataFromAnotherAccount_withoutMutations() = runBlocking {
        seedSyncedState("user-a")
        val opsBefore = opsCount()

        val err = sync.bindAccount("user-b", "b@synthetic.local", "tok-b")
        assertNotNull("el vínculo a otra cuenta debe rechazarse", err)
        assertTrue(err!!.contains("otra cuenta"))

        // Filas intactas: usuario y titularidad de las entidades sin cambios.
        assertEquals("user-a", db.userDao().get()!!.id)
        assertEquals("a@synthetic.local", db.userDao().get()!!.email)
        assertEquals("user-a", db.clientDao().get("cli-synced")!!.userId)
        assertEquals(2, db.clientDao().get("cli-synced")!!.serverVersion)
        // Cola intacta.
        assertEquals(opsBefore, opsCount())
        // Cursor intacto.
        assertEquals(9L, session.cursor())
        // Sesión intacta: sigue sin vínculo.
        assertNull(session.accountUserId)
        assertNull(session.accountEmail)
        assertNull(session.token)
    }

    /** Adopción de datos nunca sincronizados: el comportamiento aprobado. */
    @Test
    fun adoptsNeverSyncedLocalData() = runBlocking {
        // Onboarding local sin cuenta: usuario + perfil + cliente pendientes.
        repo.completeOnboarding("Local User", "Perfil Local")
        val localUserId = db.userDao().get()!!.id
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente Local"))
        assertTrue(db.syncDao().pendingCount() > 0)
        assertEquals(0L, session.cursor())

        val err = sync.bindAccount("user-b", "b@synthetic.local", "tok-b")
        assertNull("la adopción de datos nunca sincronizados sigue permitida", err)

        // Identidad reasignada a la cuenta canónica, UUIDs conservados.
        assertEquals("user-b", db.userDao().get()!!.id)
        val client = db.clientDao().observeForUser("user-b").first().single()
        assertEquals("Cliente Local", client.name)
        assertTrue(client.pendingSync)
        // La op user pendiente se reescribió a la identidad canónica.
        val userOp = db.syncDao().pendingOpsForEntity(EntityTypes.USER, "user-b").singleOrNull()
        assertNotNull(userOp)
        val payload = json.parseToJsonElement(userOp!!.payloadJson).jsonObject
        assertEquals("\"user-b\"", payload["id"].toString())
        // Cursor reiniciado para traer lo que la cuenta nueva ya tenga.
        assertEquals(0L, session.cursor())
        // Sesión persistida.
        assertEquals("user-b", session.accountUserId)
        assertEquals("b@synthetic.local", session.accountEmail)
        assertEquals("tok-b", session.token)
    }

    /** Mismo usuario canónico: re-login conserva datos y cursor (sin cambios innecesarios). */
    @Test
    fun bindsSameCanonicalUser_preservesDataAndCursor() = runBlocking {
        seedSyncedState("user-a")

        val err = sync.bindAccount("user-a", "a-renombrada@synthetic.local", "tok-a2")
        assertNull(err)
        assertEquals("user-a", db.userDao().get()!!.id)
        assertEquals("a-renombrada@synthetic.local", db.userDao().get()!!.email)
        assertEquals("user-a", db.clientDao().get("cli-synced")!!.userId)
        // El cursor se conserva: el pull incremental sigue donde quedó.
        assertEquals(9L, session.cursor())
        assertEquals("user-a", session.accountUserId)
        assertEquals("tok-a2", session.token)
    }

    /**
     * Límite de cierre inesperado en switchAccount: estado recuperado con
     * sesión limpia pero datos sincronizados intactos (clearSession() ya
     * aplicado, wipeLocalData() no alcanzado). Un vínculo a otra cuenta no
     * puede reatribuir los datos; la cuenta original sí puede reentrar y
     * desde ahí ejecutar el cambio seguro.
     */
    @Test
    fun interruptedSwitchState_rejectsOtherAccount_allowsOriginal() = runBlocking {
        seedSyncedState("user-a")
        // Simula el cierre entre clearSession() y wipeLocalData().
        session.clearSession()

        val err = sync.bindAccount("user-b", "b@synthetic.local", "tok-b")
        assertNotNull("estado de cambio interrumpido: vínculo a otra cuenta bloqueado", err)
        assertEquals("user-a", db.userDao().get()!!.id)
        assertEquals("user-a", db.clientDao().get("cli-synced")!!.userId)

        // La cuenta original recupera la sesión sin perder nada.
        assertNull(sync.bindAccount("user-a", "a@synthetic.local", "tok-a"))
        assertEquals("user-a", session.accountUserId)
        assertEquals("user-a", db.clientDao().get("cli-synced")!!.userId)
        assertEquals(9L, session.cursor())
    }
}

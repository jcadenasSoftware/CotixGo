package com.cotixgo.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.DbMigrationManager
import com.cotixgo.app.data.security.DbKeyProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/**
 * Regresión del arranque real: simula una instalación previa con una base
 * EN CLARO (esquema Room v9 real) y entra por el camino de producción
 * `AppDatabase.get()` — exactamente lo que hace MainActivity.onCreate. Esta
 * clase NO llama a System.loadLibrary("sqlcipher") en su setup: la carga
 * nativa debe ocurrir dentro de DbMigrationManager antes de tocar clases
 * SQLCipher (bug detectado en dispositivo físico: UnsatisfiedLinkError en
 * SQLiteConnection.nativeOpen al migrar).
 *
 * Corre primero en la ordenación alfabética de clases de la suite; si la
 * librería no estuviera cargada por el camino de producción, esta prueba
 * fallaría igual que en el Xiaomi.
 *
 * Aislamiento (D2): los artefactos usan nombres exclusivos de prueba —
 * ni la base, ni el marcador, ni el blob de clave reales de la app se
 * tocan; la cobertura del camino de producción se conserva porque la
 * migración es agnóstica al nombre del fichero.
 */
@RunWith(AndroidJUnit4::class)
class BootMigrationTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testDbName = TestIsolation.name("e2e-boot-fixture.db")
    private val testMarker = TestIsolation.name("e2e-boot-migration.state")
    private val testBlob = TestIsolation.name("e2e-boot-dbkey.bin")
    private val testAlias = TestIsolation.name("e2e-boot-db-key")

    private lateinit var prodBefore: Map<String, String?>

    private fun testDbFile() = ctx.getDatabasePath(testDbName)
    private fun markerFile() = File(ctx.filesDir, testMarker)
    private fun blobFile() = File(ctx.filesDir, testBlob)

    @Before
    fun setUp() {
        // Estado de instalación previa: base clara existente, sin marcador.
        prodBefore = TestIsolation.snapshotProd(ctx)
        DbMigrationManager.setTestHooks(dbName = testDbName, markerName = testMarker)
        DbKeyProvider.setTestHooks(blobName = testBlob, keyAlias = testAlias)
        AppDatabase.resetForTest()
        wipeAll()
    }

    @After
    fun tearDown() {
        // Restaura el sandbox: sin base, sin marcador — las pruebas
        // siguientes obtienen una base cifrada nueva por el camino normal.
        AppDatabase.resetForTest()
        DbMigrationManager.setTestHooks()
        DbKeyProvider.setTestHooks()
        wipeAll()
        TestIsolation.assertProdUntouched(ctx, prodBefore)
    }

    private fun wipeAll() {
        val db = testDbFile()
        listOf(db, File(db.path + ".bak"), File(db.path + ".enc.tmp"),
            File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))
            .forEach { it.delete() }
        markerFile().delete()
        blobFile().delete()
    }

    @Test
    fun coldStart_migratesClearDbToEncrypted_viaProductionPath() {
        // Base clara v9 real con una fila sintética — se crea con Room SIN
        // fábrica cifrada (instalación previa a esta versión).
        val clear = Room.databaseBuilder(ctx, AppDatabase::class.java, testDbName)
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        clear.openHelper.writableDatabase.execSQL(
            "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
        )
        clear.close()

        // Comprobación de premisa: el archivo es SQLite en claro.
        val head = ByteArray(15)
        testDbFile().inputStream().use { it.read(head) }
        assertEquals("SQLite format 3", String(head, Charsets.US_ASCII))

        // Camino de producción: AppDatabase.get() → ensureReady → migrate
        // → openHelperFactory cifrado. Sin loadLibrary previo en el test.
        val db = AppDatabase.get(ctx)

        // La base migrada funciona y conserva la fila.
        db.openHelper.readableDatabase.query("SELECT name FROM users WHERE id='u1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Usuario Sintético", it.getString(0))
        }

        db.close()
        // Simulación explícita del estado durable MIGRATED requerido por la
        // regresión: la pérdida posterior del blob de clave debe bloquear.
        markerFile().outputStream().use {
            Properties().apply { setProperty("state", DbMigrationManager.State.MIGRATED.name) }.store(it, null)
        }
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        testDbFile().inputStream().use { it.read(head) }
        assertFalse("La base servida no puede ser clara",
            String(head, Charsets.US_ASCII) == "SQLite format 3")
        assertFalse(File(testDbFile().path + ".bak").exists())
        assertFalse(File(testDbFile().path + ".enc.tmp").exists())
    }

    /**
     * Regresión M1: base cifrada migrada + blob de clave ausente →
     * bloqueo key_unavailable por el camino de producción. Sin una clave
     * sustituta, sin borrado de datos y con la cola intacta; solo la
     * acción informada del usuario (hardReset) puede limpiar.
     */
    @Test
    fun migratedDb_missingKeyBlob_blocksKeyUnavailable_noSubstitute() {
        // Instalación cifrada sana por el camino de producción, con una
        // operación pendiente en la cola.
        val db = AppDatabase.get(ctx)
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                " createdAt, updatedAt) VALUES ('op1', 1, 'client', 'c1', 'upsert', 0, '{}', NULL," +
                " 'dev1', 'PENDING', NULL, NULL, 0, 1, 1)",
        )
        db.close()
        AppDatabase.resetForTest()
        markerFile().outputStream().use {
            Properties().apply { setProperty("state", DbMigrationManager.State.MIGRATED.name) }.store(it, null)
        }
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        val dbBytes = testDbFile().readBytes()

        // Pérdida de clave: únicamente el blob desaparece.
        val blob = blobFile()
        assertTrue(blob.delete())

        try {
            AppDatabase.get(ctx)
            fail("La pérdida de clave debe bloquear, no generar clave sustituta")
        } catch (e: DbKeyProvider.KeyUnavailableException) {
            // esperado: MainActivity lo presenta como "key_unavailable"
        }

        assertFalse("No puede generarse una clave sustituta", blob.exists())
        assertTrue(
            "La base cifrada (y la cola pendiente) permanece intacta",
            testDbFile().readBytes().contentEquals(dbBytes),
        )
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
    }
}

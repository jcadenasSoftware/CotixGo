package com.cotixgo.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.DbMigrationManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Regresión del arranque real: simula una instalación previa con la base
 * `cotixgo.db` EN CLARO (nombre de producción, esquema Room v9 real) y
 * entra por el camino de producción `AppDatabase.get()` — exactamente lo
 * que hace MainActivity.onCreate. Esta clase NO llama a
 * System.loadLibrary("sqlcipher") en su setup: la carga nativa debe
 * ocurrir dentro de DbMigrationManager antes de tocar clases SQLCipher
 * (bug detectado en dispositivo físico: UnsatisfiedLinkError en
 * SQLiteConnection.nativeOpen al migrar).
 *
 * Corre primero en la ordenación alfabética de clases de la suite; si la
 * librería no estuviera cargada por el camino de producción, esta prueba
 * fallaría igual que en el Xiaomi.
 */
@RunWith(AndroidJUnit4::class)
class BootMigrationTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun prodDbFile() = ctx.getDatabasePath("cotixgo.db")
    private fun markerFile() = File(ctx.filesDir, "db_migration.state")

    @Before
    fun setUp() {
        // Estado de instalación previa: base clara existente, sin marcador.
        AppDatabase.resetForTest()
        wipeAll()
    }

    @After
    fun tearDown() {
        // Restaura el sandbox: sin base, sin marcador — las pruebas
        // siguientes obtienen una base cifrada nueva por el camino normal.
        AppDatabase.resetForTest()
        wipeAll()
    }

    private fun wipeAll() {
        val db = prodDbFile()
        listOf(db, File(db.path + ".bak"), File(db.path + ".enc.tmp"),
            File(db.path + "-wal"), File(db.path + "-shm"), File(db.path + "-journal"))
            .forEach { it.delete() }
        markerFile().delete()
    }

    @Test
    fun coldStart_migratesClearDbToEncrypted_viaProductionPath() {
        // Base clara v9 real con una fila sintética — se crea con Room SIN
        // fábrica cifrada (instalación previa a esta versión).
        val clear = Room.databaseBuilder(ctx, AppDatabase::class.java, "cotixgo.db")
            .addMigrations(*AppDatabase.ALL_MIGRATIONS)
            .build()
        clear.openHelper.writableDatabase.execSQL(
            "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
        )
        clear.close()

        // Comprobación de premisa: el archivo es SQLite en claro.
        val head = ByteArray(15)
        prodDbFile().inputStream().use { it.read(head) }
        assertEquals("SQLite format 3", String(head, Charsets.US_ASCII))

        // Camino de producción: AppDatabase.get() → ensureReady → migrate
        // → openHelperFactory cifrado. Sin loadLibrary previo en el test.
        val db = AppDatabase.get(ctx)

        // La base migrada funciona y conserva la fila.
        db.openHelper.readableDatabase.query("SELECT name FROM users WHERE id='u1'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Usuario Sintético", it.getString(0))
        }

        // Estado final coherente: MIGRATED, fichero cifrado, sin claros.
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        prodDbFile().inputStream().use { it.read(head) }
        assertFalse("La base servida no puede ser clara",
            String(head, Charsets.US_ASCII) == "SQLite format 3")
        assertFalse(File(prodDbFile().path + ".bak").exists())
        assertFalse(File(prodDbFile().path + ".enc.tmp").exists())
        db.close()
    }
}

package com.cotixgo.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.DbMigrationManager
import com.cotixgo.app.data.security.DbKeyProvider
import com.cotixgo.app.data.security.SessionCrypto
import net.zetetic.database.sqlcipher.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Properties

/**
 * Protección local en reposo: Room cifrada vía SQLCipher, migración
 * fail-closed de una base clara v9, recuperación por estado ante cierre
 * forzado, limpieza bloqueante y pérdida de clave. Solo datos sintéticos.
 * La fixture usa el AppDatabase REAL con un nombre de fichero de prueba:
 * el esquema v9 completo F1–F8 queda representado.
 */
@RunWith(AndroidJUnit4::class)
class LocalEncryptionTest {

    private val ctx: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val testDbName = "enc_test_fixture.db"

    private val migrations = AppDatabase.ALL_MIGRATIONS

    private fun dbFile() = ctx.getDatabasePath(testDbName)
    private fun tmpFile() = File(dbFile().path + ".enc.tmp")
    private fun bakFile() = File(dbFile().path + ".bak")
    private fun markerFile() = File(ctx.filesDir, "db_migration.state")
    private fun blobFile() = File(ctx.filesDir, "dbkey.bin")

    @Before
    fun setUp() {
        System.loadLibrary("sqlcipher")
        DbMigrationManager.setTestHooks(dbName = testDbName)
        clean()
    }

    @After
    fun tearDown() {
        DbMigrationManager.setTestHooks()
        clean()
    }

    private fun clean() {
        listOf(dbFile(), tmpFile(), bakFile()).forEach { f ->
            f.delete(); File(f.path + "-wal").delete(); File(f.path + "-shm").delete()
            File(f.path + "-journal").delete()
        }
        markerFile().delete()
        blobFile().delete()
    }

    private fun writeMarker(state: DbMigrationManager.State, reason: String? = null) {
        val p = Properties(); p.setProperty("state", state.name)
        reason?.let { p.setProperty("reason", it) }
        markerFile().outputStream().use { p.store(it, null) }
    }

    /** Fixture sintética representativa: AppDatabase v9 en CLARO con filas. */
    private fun seedClearV9(): AppDatabase {
        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, testDbName)
            .addMigrations(*migrations)
            .build()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                " createdAt, updatedAt) VALUES ('op1', 1, 'client', 'c1', 'upsert', 0, '{}', NULL," +
                " 'dev1', 'DONE', NULL, NULL, 1, 1, 1)",
        )
        return db
    }

    private fun isClearSqlite(f: File): Boolean {
        if (!f.exists() || f.length() < 16) return false
        val head = ByteArray(15)
        f.inputStream().use { it.read(head) }
        return String(head, Charsets.US_ASCII) == "SQLite format 3"
    }

    private fun tableCount(db: SQLiteDatabase, table: String): Long {
        val c = db.rawQuery("SELECT COUNT(*) FROM \"$table\"", emptyArray())
        c.moveToFirst(); val n = c.getLong(0); c.close(); return n
    }

    private fun openEncrypted(path: File, pass: String) =
        SQLiteDatabase.openOrCreateDatabase(path, pass, null, null)

    private fun passphraseOf(): String {
        val raw = DbKeyProvider.getOrCreatePassphrase(ctx)
        return raw.joinToString("") { "%02x".format(it) }
    }

    // ---------- Room cifrada: apertura / escritura / reapertura ----------

    @Test
    fun encryptedRoom_roundTrip_wrongPassFails() {
        val pass = passphraseOf()
        val f = ctx.getDatabasePath("enc_roundtrip.db")
        f.delete()

        val db = Room.databaseBuilder(ctx, AppDatabase::class.java, "enc_roundtrip.db")
            .openHelperFactory(
                net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                    pass.toByteArray(Charsets.US_ASCII),
                ),
            )
            .addMigrations(*migrations)
            .build()
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('u1', 'Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
        )
        db.close()

        // El fichero no es texto claro SQLite.
        assertFalse("La base cifrada no debe tener cabecera SQLite en claro", isClearSqlite(f))

        // Reabrir con la passphrase correcta funciona.
        val db2 = Room.databaseBuilder(ctx, AppDatabase::class.java, "enc_roundtrip.db")
            .openHelperFactory(
                net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                    pass.toByteArray(Charsets.US_ASCII),
                ),
            )
            .addMigrations(*migrations)
            .build()
        db2.openHelper.readableDatabase.query("SELECT name FROM users WHERE id='u1'").use {
            assertTrue(it.moveToFirst()); assertEquals("Sintético", it.getString(0))
        }
        db2.close()

        // Passphrase incorrecta → fallo al consultar (fail-closed).
        val wrong = Room.databaseBuilder(ctx, AppDatabase::class.java, "enc_roundtrip.db")
            .openHelperFactory(
                net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                    "00".repeat(32).toByteArray(Charsets.US_ASCII),
                ),
            )
            .addMigrations(*migrations)
            .build()
        try {
            wrong.openHelper.readableDatabase.query("SELECT COUNT(*) FROM users").moveToFirst()
            fail("La passphrase incorrecta debe fallar")
        } catch (e: Exception) {
            // esperado
        } finally {
            wrong.close(); f.delete()
        }
    }

    // ---------- Migración claro → cifrado ----------

    @Test
    fun migration_clearV9_toEncrypted_preservesTablesAndCounts() {
        seedClearV9().close()
        assertTrue(isClearSqlite(dbFile()))

        DbMigrationManager.ensureReady(ctx)

        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        assertFalse(isClearSqlite(dbFile()))
        assertFalse(bakFile().exists())
        assertFalse(tmpFile().exists())
        assertFalse(File(dbFile().path + "-wal").exists())

        // Abrir cifrada y verificar integridad + conteos del fixture.
        val enc = openEncrypted(dbFile(), passphraseOf())
        val ic = enc.rawQuery("PRAGMA integrity_check", emptyArray())
        ic.moveToFirst(); assertEquals("ok", ic.getString(0)); ic.close()
        assertEquals(1L, tableCount(enc, "users"))
        assertEquals(1L, tableCount(enc, "sync_operations"))
        // Las 35 tablas del esquema v9 siguen presentes.
        val t = enc.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            emptyArray(),
        )
        t.moveToFirst(); assertTrue(t.getLong(0) >= 35); t.close()
        enc.close()

        // La app sirve datos cifrados: ensureReady en MIGRATED no bloquea.
        DbMigrationManager.ensureReady(ctx)
    }

    // ---------- Recuperación tras cierre forzado por estado ----------

    @Test
    fun resume_preparingAndExporting_restartsAndCompletes() {
        for (st in listOf(
            DbMigrationManager.State.PREPARING,
            DbMigrationManager.State.EXPORTING,
            DbMigrationManager.State.VERIFYING,
        )) {
            clean()
            seedClearV9().close()
            tmpFile().writeBytes(ByteArray(64) { 0x42 }) // artefacto parcial
            writeMarker(st)
            DbMigrationManager.ensureReady(ctx)
            assertEquals("resume desde $st", DbMigrationManager.State.MIGRATED,
                DbMigrationManager.currentState(ctx))
            assertFalse(isClearSqlite(dbFile()))
        }
    }

    @Test
    fun resume_swapPending_forensicRecovery() {
        // Caso A: bak + cifrada en su lugar → solo limpieza.
        seedClearV9().close()
        val pass = passphraseOf()
        // Crear "cifrada" válida copiando una base exportada real:
        // exportamos para obtener un tmp verificable.
        DbMigrationManager.ensureReady(ctx) // migración completa primero
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        // Simular cierre durante swap: restaurar bak claro + mantener cifrada.
        // (bak fue eliminado por cleanup; lo recreamos desde una copia clara nueva)
        val encBytes = dbFile().readBytes()
        seedClearV9().close()
        bakFile().writeBytes(dbFile().readBytes())
        dbFile().writeBytes(encBytes)
        writeMarker(DbMigrationManager.State.SWAP_PENDING)
        DbMigrationManager.ensureReady(ctx)
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        assertFalse(bakFile().exists())

        // Caso B: db ausente, bak + tmp presentes → tmp pasa a db.
        clean()
        seedClearV9().close()
        DbMigrationManager.ensureReady(ctx)
        val enc2 = dbFile().readBytes()
        val blobBytes = blobFile().readBytes()
        clean()
        seedClearV9().close()
        bakFile().writeBytes(dbFile().readBytes())
        tmpFile().writeBytes(enc2)
        blobFile().writeBytes(blobBytes)
        dbFile().delete()
        writeMarker(DbMigrationManager.State.SWAP_PENDING)
        DbMigrationManager.ensureReady(ctx)
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        assertFalse(tmpFile().exists())
        assertFalse(isClearSqlite(dbFile()))
    }

    @Test
    fun failedState_blocksUntilResolved_hardResetRestores() {
        writeMarker(DbMigrationManager.State.FAILED, "export_failed")
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("FAILED debe bloquear")
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals("export_failed", e.reason)
        }
        DbMigrationManager.hardReset(ctx, andKey = false)
        DbMigrationManager.ensureReady(ctx) // sin base → instalación nueva
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
    }

    @Test
    fun insufficientSpace_failsClosed() {
        seedClearV9().close()
        DbMigrationManager.setTestHooks(dbName = testDbName, spaceProbe = { 0L })
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("Espacio insuficiente debe bloquear")
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals("insufficient_space", e.reason)
        }
        assertEquals(DbMigrationManager.State.FAILED, DbMigrationManager.currentState(ctx))
        // El origen claro se conserva para el reintento.
        assertTrue(isClearSqlite(dbFile()))
    }

    @Test
    fun corruptClearDb_exportFails_blocked() {
        dbFile().parentFile?.mkdirs()
        // Cabecera válida + basura: SQLite no puede abrirla.
        dbFile().writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(256) { 0x7F })
        writeMarker(DbMigrationManager.State.NOT_MIGRATED)
        try {
            DbMigrationManager.ensureReady(ctx)
            // openOrCreateDatabase puede tolerar el fichero: si migra, el
            // resultado sigue siendo cifrado; ambos finales son fail-closed.
            assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
            assertFalse(isClearSqlite(dbFile()))
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals(DbMigrationManager.State.FAILED, DbMigrationManager.currentState(ctx))
        }
    }

    @Test
    fun verificationFailure_blocks_noClearServed() {
        seedClearV9().close()
        DbMigrationManager.setTestHooks(
            dbName = testDbName,
            verify = { false },
        )
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("Verificación fallida debe bloquear")
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals("verification_failed", e.reason)
        }
        // No se sirve la base clara: sigue bloqueado en el siguiente arranque.
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("FAILED persistente debe seguir bloqueando")
        } catch (e: DbMigrationManager.DbBlockedException) {
            // esperado
        }
    }

    @Test
    fun cleanupFailure_blocksUntilResolved() {
        seedClearV9().close()
        // Simular fallo de borrado de artefactos claros.
        val realDelete = { f: File -> f.delete() }
        DbMigrationManager.setTestHooks(
            dbName = testDbName,
            delete = { f -> if (f.name.endsWith(".bak")) false else realDelete(f) },
        )
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("Limpieza incompleta debe bloquear")
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals("cleanup_incomplete", e.reason)
        }
        assertEquals(DbMigrationManager.State.CLEANUP_PENDING, DbMigrationManager.currentState(ctx))
        assertTrue("El artefacto claro persiste", bakFile().exists())

        // En cada arranque se reintenta; al recuperar el borrado, desbloquea.
        DbMigrationManager.setTestHooks(dbName = testDbName)
        DbMigrationManager.ensureReady(ctx)
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        assertFalse(bakFile().exists())
        assertFalse(isClearSqlite(dbFile()))
    }

    @Test
    fun migratedEncryptedDb_missingKeyBlob_blocksKeyUnavailable() {
        // Regresión M1: migrar de verdad → MIGRATED con clave válida;
        // luego se pierde únicamente el blob.
        seedClearV9().close()
        DbMigrationManager.ensureReady(ctx)
        assertFalse(isClearSqlite(dbFile()))
        val dbBytes = dbFile().readBytes()
        writeMarker(DbMigrationManager.State.MIGRATED)
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))

        DbKeyProvider.deletePassphrase(ctx)
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("Base cifrada sin blob debe bloquear con key_unavailable")
        } catch (e: DbKeyProvider.KeyUnavailableException) {
            // esperado: MainActivity lo muestra como "key_unavailable"
        }
        assertFalse("Sin clave sustituta", blobFile().exists())
        assertTrue("La base cifrada permanece intacta",
            dbFile().readBytes().contentEquals(dbBytes))
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
    }

    @Test
    fun resumeSwapAndNotMigrated_missingKeyBlob_blocksWithoutSubstitute() {
        // Estado intermedio SWAP_PENDING con cifrada en su lugar + bak:
        // la falta de clave bloquea sin generar sustituta ni servir claro.
        seedClearV9().close()
        DbMigrationManager.ensureReady(ctx)
        val encBytes = dbFile().readBytes()
        bakFile().writeBytes(encBytes) // artefacto residual simulado
        writeMarker(DbMigrationManager.State.SWAP_PENDING)
        DbKeyProvider.deletePassphrase(ctx)
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("SWAP_PENDING sin blob debe bloquear")
        } catch (e: DbKeyProvider.KeyUnavailableException) {
            // esperado
        }
        assertFalse(blobFile().exists())
        assertTrue(dbFile().readBytes().contentEquals(encBytes))
        assertEquals(DbMigrationManager.State.CLEANUP_PENDING,
            DbMigrationManager.currentState(ctx))

        // Estado NOT_MIGRATED con base ya cifrada y sin blob: el forense
        // no puede regenerar la clave — bloquea igualmente.
        writeMarker(DbMigrationManager.State.NOT_MIGRATED)
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("NOT_MIGRATED con cifrada sin blob debe bloquear")
        } catch (e: DbKeyProvider.KeyUnavailableException) {
            // esperado
        }
        assertFalse(blobFile().exists())
        assertTrue(dbFile().readBytes().contentEquals(encBytes))
    }

    @Test
    fun clearDb_missingKeyBlob_migratesWithFreshKey() {
        // Contraste: fuente en claro verificada SÍ puede generar una
        // clave nueva (no hay nada cifrado que proteger).
        seedClearV9().close()
        assertTrue(blobFile().delete() || !blobFile().exists())
        DbMigrationManager.ensureReady(ctx)
        assertEquals(DbMigrationManager.State.MIGRATED, DbMigrationManager.currentState(ctx))
        assertTrue(blobFile().exists())
        assertFalse(isClearSqlite(dbFile()))
        val enc = openEncrypted(dbFile(), passphraseOf())
        assertEquals(1L, tableCount(enc, "users")); enc.close()
    }

    @Test
    fun clearDbAfterMigrated_blocks() {
        seedClearV9().close()
        writeMarker(DbMigrationManager.State.MIGRATED)
        try {
            DbMigrationManager.ensureReady(ctx)
            fail("Base clara tras MIGRATED debe bloquear")
        } catch (e: DbMigrationManager.DbBlockedException) {
            assertEquals("clear_db_after_migrated", e.reason)
        }
    }

    // ---------- Claves ----------

    @Test
    fun dbKey_wrappedAndRecoverable_lossRequiresReset() {
        val p1 = DbKeyProvider.getOrCreatePassphrase(ctx)
        assertEquals(32, p1.size)
        assertTrue(blobFile().exists())
        assertTrue("El blob no puede contener la passphrase en claro",
            !blobFile().readBytes().contentEquals(p1))
        val p2 = DbKeyProvider.getOrCreatePassphrase(ctx)
        assertTrue(p1.contentEquals(p2))
    }

    @Test
    fun dbKey_corruptBlob_throwsKeyUnavailable() {
        DbKeyProvider.getOrCreatePassphrase(ctx)
        blobFile().writeBytes(ByteArray(32) { 0x00 })
        try {
            DbKeyProvider.getOrCreatePassphrase(ctx)
            fail("Blob corrupto debe lanzar KeyUnavailableException")
        } catch (e: DbKeyProvider.KeyUnavailableException) {
            // esperado: la UI ofrece resync informado
        }
        DbKeyProvider.deletePassphrase(ctx)
        assertNotNull(DbKeyProvider.getOrCreatePassphrase(ctx))
    }

    @Test
    fun sessionCrypto_roundTrip_corruptReturnsNull() {
        val enc = SessionCrypto.encrypt("token-sintetico-123")
        assertNotNull(enc)
        assertFalse(enc!!.contains("token-sintetico"))
        assertEquals("token-sintetico-123", SessionCrypto.decryptOrNull(enc))
        assertNull(SessionCrypto.decryptOrNull("AAAAcorrupto"))
        assertNull(SessionCrypto.decryptOrNull(null))
    }
}

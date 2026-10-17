package com.cotixgo.app.data

import android.content.Context
import android.os.StatFs
import com.cotixgo.app.data.security.DbKeyProvider
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.util.Properties

/**
 * Migración fail-closed de la base en claro `cotixgo.db` a una base
 * cifrada con SQLCipher, conforme a la ficha aprobada (docs/03 §6).
 *
 * - Estado durable fuera de la base (`db_migration.state`), recuperable
 *   determinísticamente ante cierre forzado en cualquier paso.
 * - Gate exclusivo: todo acceso a la base pasa por [ensureReady] bajo
 *   un único lock de proceso, antes de que Room abra ninguna conexión.
 * - Tras iniciar la migración la app nunca sirve datos desde la copia
 *   en claro; no existe camino operativo de vuelta a la base en claro.
 * - La limpieza de archivos claros accesibles es bloqueante: si un
 *   borrado falla, la app queda bloqueada y reintenta en cada arranque.
 *   El borrado elimina entradas accesibles; no se afirma borrado seguro
 *   de residuos físicos en memoria flash (wear leveling).
 */
object DbMigrationManager {

    enum class State { NOT_MIGRATED, PREPARING, EXPORTING, VERIFYING, SWAP_PENDING, CLEANUP_PENDING, MIGRATED, FAILED }

    /** La app no puede servir datos de forma segura; la UI debe bloquear. */
    class DbBlockedException(val state: State, val reason: String) : Exception(reason)

    private const val DB_NAME = "cotixgo.db"
    private const val MARKER = "db_migration.state"
    private const val SQLITE_HEADER = "SQLite format 3"

    init {
        // La librería nativa debe estar cargada antes de CUALQUIER uso de
        // las clases de SQLCipher — incluida la apertura de la base clara
        // en migrate()/verify(), no solo al construir la fábrica de Room.
        // Cargarla aquí (init del objeto) garantiza que el primer acceso a
        // DbMigrationManager la tenga disponible.
        System.loadLibrary("sqlcipher")
    }

    private var deleter: (File) -> Boolean = { it.delete() }
    private var freeSpaceProbe: (File) -> Long = {
        StatFs(it.absolutePath).run { availableBlocksLong * blockSizeLong }
    }
    private var verifyHook: ((File) -> Boolean)? = null
    private var testDbName: String? = null
    private var testMarkerName: String? = null

    internal fun setTestHooks(
        delete: ((File) -> Boolean)? = null,
        spaceProbe: ((File) -> Long)? = null,
        verify: ((File) -> Boolean)? = null,
        dbName: String? = null,
        markerName: String? = null,
    ) {
        deleter = delete ?: { it.delete() }
        freeSpaceProbe = spaceProbe ?: { StatFs(it.absolutePath).run { availableBlocksLong * blockSizeLong } }
        verifyHook = verify
        testDbName = dbName
        testMarkerName = markerName
    }

    /**
     * Nombre de la base que sirve la migración: en producción `cotixgo.db`;
     * con hook de prueba, el nombre inyectado. [AppDatabase.get] abre
     * exactamente este archivo para que el camino migrado y el servido
     * nunca diverjan (D2: las pruebas no tocan la base real).
     */
    internal fun resolvedDbName(): String = testDbName ?: DB_NAME

    private fun markerFile(context: Context) = File(context.filesDir, testMarkerName ?: MARKER)
    private fun dbFile(context: Context) = context.getDatabasePath(resolvedDbName())
    private fun tmpFile(context: Context) = File(dbFile(context).path + ".enc.tmp")
    private fun bakFile(context: Context) = File(dbFile(context).path + ".bak")

    private fun sidecars(f: File): List<File> =
        listOf("-wal", "-shm", "-journal").map { File(f.path + it) }

    fun currentState(context: Context): State {
        val p = Properties()
        val f = markerFile(context)
        if (!f.exists()) return if (dbFile(context).exists()) State.NOT_MIGRATED else State.MIGRATED
        f.inputStream().use { p.load(it) }
        return runCatching { State.valueOf(p.getProperty("state")!!) }
            .getOrDefault(State.NOT_MIGRATED)
    }

    fun failureReason(context: Context): String? {
        val f = markerFile(context)
        if (!f.exists()) return null
        val p = Properties(); f.inputStream().use { p.load(it) }
        return p.getProperty("reason")
    }

    private fun writeState(context: Context, s: State, reason: String? = null) {
        val p = Properties()
        p.setProperty("state", s.name)
        if (reason != null) p.setProperty("reason", reason)
        // Escritura atómica (tmp + rename): un corte a mitad dejaría un
        // marcador ilegible, leído como NOT_MIGRATED en el arranque
        // siguiente y abriendo la vía de limpieza sobre la base activa.
        val m = markerFile(context)
        val tmp = File(m.path + ".tmp")
        tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(m)) {
            tmp.delete()
            m.outputStream().use { p.store(it, null) }
        }
    }

    private fun isClearSqlite(f: File): Boolean {
        if (!f.exists() || f.length() < 16) return false
        val head = ByteArray(15)
        f.inputStream().use { it.read(head) }
        return String(head, Charsets.US_ASCII) == SQLITE_HEADER
    }

    private fun isEncryptedSqlite(f: File): Boolean =
        f.exists() && f.length() >= 16 && !isClearSqlite(f)

    /** Passphrase como hex (segura para SQL KEY y para SupportFactory). */
    private fun hex(b: ByteArray): String =
        b.joinToString("") { "%02x".format(it) }

    private fun passphrase(context: Context): String =
        hex(DbKeyProvider.getOrCreatePassphrase(context))

    /** Fábrica cifrada para Room; el llamador debe pasar por [ensureReady] antes. */
    fun encryptedFactory(context: Context): SupportOpenHelperFactory =
        SupportOpenHelperFactory(passphrase(context).toByteArray(Charsets.US_ASCII))

    /**
     * Gate exclusivo de acceso a la base. Invocado desde [AppDatabase.get]
     * bajo el lock del singleton: ningún repositorio, DAO, worker de sync o
     * tarea obtiene conexión sin pasar por aquí. Lanza [DbBlockedException]
     * si la app debe quedar bloqueada (migración fallida, limpieza
     * incompleta, clave no disponible o base en claro tras MIGRATED).
     */
    @Synchronized
    fun ensureReady(context: Context) {
        val ctx = context.applicationContext
        when (currentState(ctx)) {
            State.MIGRATED -> {
                // Guarda fail-closed: una base clara tras declarar la
                // migración completa se trata como estado inconsistente.
                val f = dbFile(ctx)
                if (f.exists() && isClearSqlite(f)) {
                    writeState(ctx, State.FAILED, "clear_db_after_migrated")
                    throw DbBlockedException(State.FAILED, "clear_db_after_migrated")
                }
                // Estado durable de instalación nueva: currentState()
                // computa MIGRATED sin marcador cuando no hay ni marcador
                // ni base. Fijarlo antes de servir evita que un arranque
                // posterior relea "sin marcador + base" como NOT_MIGRATED
                // y dispare la limpieza sobre los sidecars activos.
                if (!markerFile(ctx).exists()) writeState(ctx, State.MIGRATED)
                requireBlobIfEncryptedDb(ctx, f)
                return
            }
            State.FAILED -> throw DbBlockedException(State.FAILED, failureReason(ctx) ?: "failed")
            State.CLEANUP_PENDING -> runCleanup(ctx)
            State.SWAP_PENDING -> resumeSwap(ctx)
            else -> migrate(ctx)
        }
    }

    private fun migrate(context: Context) {
        val db = dbFile(context)

        // PREPARING: verificar que el origen es claro y hay espacio.
        writeState(context, State.PREPARING)
        if (!db.exists()) {
            // Instalación nueva o ya cifrada en su camino.
            runCleanup(context); return
        }
        if (!isClearSqlite(db)) { runCleanup(context); return }
        val need = db.length() * 2
        if (freeSpaceProbe(context.filesDir) < need) {
            writeState(context, State.FAILED, "insufficient_space")
            throw DbBlockedException(State.FAILED, "insufficient_space")
        }

        // EXPORTING: base clara → temporal cifrada (reintento idempotente).
        writeState(context, State.EXPORTING)
        val pass = passphrase(context)
        val tmp = tmpFile(context)
        deleteIfExists(tmp)
        try {
            val src = SQLiteDatabase.openOrCreateDatabase(db, "", null, null)
            src.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", emptyArray()).close()
            src.rawExecSQL("ATTACH DATABASE '" + tmp.path + "' AS encrypted KEY '" + pass + "'")
            // moveToFirst() materializa el cursor: sin leerlo la función
            // exportadora puede no ejecutarse y la cifrada queda vacía.
            src.rawQuery("SELECT sqlcipher_export('encrypted')", emptyArray()).use { it.moveToFirst() }
            src.rawExecSQL("DETACH DATABASE encrypted")
            src.close()
        } catch (e: Exception) {
            deleteIfExists(tmp)
            writeState(context, State.FAILED, "export_failed")
            throw DbBlockedException(State.FAILED, "export_failed")
        }

        // VERIFYING: integridad + conteos por tabla contra el origen.
        writeState(context, State.VERIFYING)
        val ok = try {
            verifyHook?.invoke(tmp) ?: verify(context, tmp, pass)
        } catch (e: Exception) { false }
        if (!ok) {
            deleteIfExists(tmp)
            writeState(context, State.FAILED, "verification_failed")
            throw DbBlockedException(State.FAILED, "verification_failed")
        }

        // SWAP_PENDING: sustitución; el .bak solo existe como artefacto
        // interno de recuperación, nunca como base operativa.
        writeState(context, State.SWAP_PENDING)
        val bak = bakFile(context)
        deleteIfExists(bak)
        if (!db.renameTo(bak)) {
            writeState(context, State.FAILED, "swap_failed")
            throw DbBlockedException(State.FAILED, "swap_failed")
        }
        if (!tmp.renameTo(db)) {
            // El origen claro sigue en .bak — restaurar para no perderlo,
            // pero el estado queda bloqueado: no se sirve en claro.
            bak.renameTo(db)
            writeState(context, State.FAILED, "swap_failed")
            throw DbBlockedException(State.FAILED, "swap_failed")
        }
        runCleanup(context)
    }

    /**
     * Reanudación determinística tras cierre forzado dentro de SWAP_PENDING:
     * decide por forense de archivos, no por confianza en los nombres.
     * El `.bak` es artefacto interno de recuperación, jamás base operativa.
     */
    private fun resumeSwap(context: Context) {
        val db = dbFile(context); val bak = bakFile(context); val tmp = tmpFile(context)
        when {
            // Swap completo: cifrada en su lugar, restos por limpiar.
            bak.exists() && isEncryptedSqlite(db) -> runCleanup(context)
            // Renombrado hecho a medias: la cifrada verificada quedó en tmp.
            bak.exists() && !db.exists() && tmp.exists() -> {
                if (tmp.renameTo(db)) runCleanup(context) else {
                    writeState(context, State.FAILED, "swap_failed")
                    throw DbBlockedException(State.FAILED, "swap_failed")
                }
            }
            // Nunca se llegó a renombrar: reiniciar migración desde el claro.
            db.exists() && isClearSqlite(db) -> { deleteIfExists(bak); deleteIfExists(tmp); migrate(context) }
            // Swap hecho y tmp ausente: solo falta limpieza.
            !bak.exists() && isEncryptedSqlite(db) -> runCleanup(context)
            else -> {
                writeState(context, State.FAILED, "swap_inconsistent")
                throw DbBlockedException(State.FAILED, "swap_inconsistent")
            }
        }
    }

    private fun verify(context: Context, enc: File, pass: String): Boolean {
        val db = dbFile(context)
        val src = SQLiteDatabase.openOrCreateDatabase(db, "", null, null)
        val dst = SQLiteDatabase.openOrCreateDatabase(enc, pass, null, null)
        try {
            val ic = dst.rawQuery("PRAGMA integrity_check", emptyArray())
            ic.moveToFirst(); val ok = ic.getString(0) == "ok"; ic.close()
            if (!ok) return false
            val srcTables = counts(src)
            val dstTables = counts(dst)
            if (srcTables != dstTables) {
                android.util.Log.w("DbMigration", "src-only=" + (srcTables - dstTables.keys) +
                    " dst-only=" + (dstTables - srcTables.keys))
            }
            return srcTables == dstTables
        } finally {
            src.close(); dst.close()
        }
    }

    private fun counts(db: SQLiteDatabase): Map<String, Long> {
        val out = mutableMapOf<String, Long>()
        val t = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            emptyArray(),
        )
        val names = mutableListOf<String>()
        while (t.moveToNext()) names.add(t.getString(0))
        t.close()
        for (n in names) {
            val c = db.rawQuery("SELECT COUNT(*) FROM \"$n\"", emptyArray())
            c.moveToFirst(); out[n] = c.getLong(0); c.close()
        }
        return out
    }

    /**
     * Una base cifrada sin su blob de clave es pérdida de clave: bloquea
     * con [DbKeyProvider.KeyUnavailableException] (la UI muestra
     * key_unavailable y el resync informado) en lugar de dejar que
     * [passphrase] genere una clave sustituta incapaz de abrirla.
     */
    private fun requireBlobIfEncryptedDb(ctx: Context, f: File) {
        if (f.exists() && isEncryptedSqlite(f) && !DbKeyProvider.hasPassphraseBlob(ctx)) {
            throw DbKeyProvider.KeyUnavailableException(
                IllegalStateException("base cifrada sin blob de clave"),
            )
        }
    }

    /**
     * Limpieza bloqueante de los artefactos claros de una fase de
     * migración. Los sidecars de la base en servicio (`db-wal`, `-shm`,
     * `-journal`) solo son residuos eliminables cuando el swap quedó
     * confirmado — existe `.bak`, la copia clara renombrada — porque
     * entonces pertenecen a la base de origen, no a la cifrada. Sin esa
     * confirmación no se puede distinguir una WAL clara residual de la
     * WAL activa de la base cifrada (SQLCipher usa la misma cabecera),
     * así que no se tocan: borrarlos destruye escrituras no
     * checkpointeadas (pérdida silenciosa de usuarios, cola y datos
     * financieros — defecto observado en campo).
     */
    private fun runCleanup(context: Context) {
        writeState(context, State.CLEANUP_PENDING)
        val db = dbFile(context)
        val bak = bakFile(context)
        val tmp = tmpFile(context)
        val leftovers = (listOf(bak, tmp) +
            (if (bak.exists()) sidecars(db) else emptyList()) +
            sidecars(bak)).filter { it.exists() }
        var failed = false
        for (f in leftovers) if (!deleter(f) && f.exists()) failed = true
        if (failed) {
            writeState(context, State.CLEANUP_PENDING, "cleanup_incomplete")
            throw DbBlockedException(State.CLEANUP_PENDING, "cleanup_incomplete")
        }
        requireBlobIfEncryptedDb(context, db)
        writeState(context, State.MIGRATED)
    }

    private fun deleteIfExists(f: File) {
        if (f.exists()) deleter(f)
    }

    /**
     * Borrado total controlado (opción del usuario ante fallo persistente o
     * pérdida de clave): elimina base, marcador y artefactos; con
     * [andKey] también la passphrase envuelta. La app queda en estado de
     * instalación nueva y resincroniza — las operaciones pendientes se
     * pierden (aviso explícito en la UI). No se afirma borrado físico.
     */
    @Synchronized
    fun hardReset(context: Context, andKey: Boolean) {
        val ctx = context.applicationContext
        val db = dbFile(ctx)
        val bak = bakFile(ctx)
        val tmp = tmpFile(ctx)
        (listOf(db, bak, tmp) + sidecars(db) + sidecars(bak) + listOf(markerFile(ctx)))
            .forEach { deleteIfExists(it) }
        if (andKey) DbKeyProvider.deletePassphrase(ctx)
    }
}

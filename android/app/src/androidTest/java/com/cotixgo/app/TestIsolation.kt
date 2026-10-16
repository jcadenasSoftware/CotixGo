package com.cotixgo.app

import android.content.Context
import org.junit.Assert.assertEquals
import java.io.File
import java.security.MessageDigest

/**
 * Aislamiento de la suite instrumentada (D2): ningún test puede leer,
 * modificar ni borrar los artefactos reales de la app — la sesión
 * (`cotixgo.session`), la base Room (`cotixgo.db`), la clave cifrada
 * (`dbkey.bin`) ni el marcador de migración (`db_migration.state`).
 *
 * Convención: todo nombre de artefacto de prueba se declara con [name],
 * que exige el prefijo `e2e-` y rechaza nombres de producción; las
 * suites capturan [snapshotProd] en setUp y llaman
 * [assertProdUntouched] en tearDown, de modo que si una ruta de
 * producción se usa por accidente la huella cambia y el test falla.
 */
object TestIsolation {

    const val PREFIX = "e2e-"

    /** Nombres de producción prohibidos como artefactos de prueba. */
    private val PROD_NAMES = setOf(
        "cotixgo.session", "cotixgo.secure", "cotixgo.db",
        "db_migration.state", "dbkey.bin",
    )

    /** Falla si [candidate] no es un nombre de artefacto de prueba. */
    fun name(candidate: String): String {
        require(candidate.startsWith(PREFIX) && candidate !in PROD_NAMES) {
            "Las pruebas solo pueden usar artefactos con prefijo '$PREFIX' " +
                "y nunca un nombre de producción (recibido: '$candidate')"
        }
        return candidate
    }

    /** Borra una base de datos de prueba; el nombre debe venir de [name]. */
    fun deleteDb(ctx: Context, dbName: String) {
        ctx.deleteDatabase(name(dbName))
    }

    /** Borra unas preferencias de prueba; el nombre debe venir de [name]. */
    fun deletePrefs(ctx: Context, prefsName: String) {
        ctx.deleteSharedPreferences(name(prefsName))
    }

    /** Artefactos reales bajo vigilancia: existencia + contenido. */
    private fun prodArtifacts(ctx: Context): Map<String, File> {
        val dataDir = File(ctx.applicationInfo.dataDir)
        return mapOf(
            "databases/cotixgo.db" to ctx.getDatabasePath("cotixgo.db"),
            "files/dbkey.bin" to File(ctx.filesDir, "dbkey.bin"),
            "files/db_migration.state" to File(ctx.filesDir, "db_migration.state"),
            "shared_prefs/cotixgo.session.xml" to
                File(dataDir, "shared_prefs/cotixgo.session.xml"),
            "shared_prefs/cotixgo.secure.xml" to
                File(dataDir, "shared_prefs/cotixgo.secure.xml"),
        )
    }

    /** Huella de un artefacto: null si no existe, sha256:tamaño si existe. */
    private fun fingerprint(f: File): String? {
        if (!f.exists() || !f.isFile) return null
        val bytes = f.readBytes()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) } + ":" + bytes.size
    }

    fun snapshotProd(ctx: Context): Map<String, String?> =
        prodArtifacts(ctx).mapValues { fingerprint(it.value) }

    /** Falla el test si algún artefacto de producción cambió desde [before]. */
    fun assertProdUntouched(ctx: Context, before: Map<String, String?>) {
        val after = snapshotProd(ctx)
        for ((path, expected) in before) {
            assertEquals(
                "La prueba tocó un artefacto de producción: $path",
                expected, after[path],
            )
        }
    }
}

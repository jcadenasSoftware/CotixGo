package com.cotixgo.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.data.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migración real v4→v5 (F4): crea una base v4 con datos preexistentes,
 * migra y verifica que las filas sobreviven y que las tablas e índices
 * nuevos de Trabajos quedan creados. No abre una base v5 fresca.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-v4-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
    )

    @Test
    fun migrate4To5_preservesRowsAndCreatesJobTables() {
        // Semilla sobre la base v4 real (columnas del esquema exportado 4.json).
        helper.createDatabase(testDb, 4).apply {
            execSQL(
                "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
            )
            execSQL(
                "INSERT INTO clients (id, userId, type, name, phone, email, address, notes, archived," +
                    " createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('c1', 'u1', 'PERSONA', 'Cliente Sintético', '', '', '', '', 0, 1, 1, 1, 1)",
            )
            execSQL(
                "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                    " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                    " createdAt, updatedAt) VALUES ('op1', 1, 'client', 'c1', 'upsert', 0, '{}', NULL," +
                    " 'dev1', 'DONE', NULL, NULL, 1, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 5, true, AppDatabase.MIGRATION_4_5)

        // Filas preexistentes sobreviven intactas.
        db.query("SELECT name FROM clients WHERE id = 'c1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Cliente Sintético", c.getString(0))
        }
        db.query("SELECT COUNT(*) FROM sync_operations WHERE opId = 'op1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        // Tablas F4 creadas con sus columnas mínimas.
        for (table in listOf("jobs", "job_quotes", "job_activities", "job_materials")) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { c -> assertTrue("tabla $table creada", c.moveToFirst()) }
        }

        // Índice único de numeración provisional presente y operativo.
        db.execSQL(
            "INSERT INTO jobs (id, userId, clientId, profileId, provisionalNumber, status," +
                " executionAddress, notes, clientNameSnapshot, archived, createdAt, updatedAt," +
                " pendingSync, serverVersion) VALUES ('j1', 'u1', 'c1', 'p1', 'TR-PEND-TEST'," +
                " 'BORRADOR', '', '', '', 0, 1, 1, 1, 0)",
        )
        var dupFailed = false
        try {
            db.execSQL(
                "INSERT INTO jobs (id, userId, clientId, profileId, provisionalNumber, status," +
                    " executionAddress, notes, clientNameSnapshot, archived, createdAt, updatedAt," +
                    " pendingSync, serverVersion) VALUES ('j2', 'u1', 'c1', 'p1', 'TR-PEND-TEST'," +
                    " 'BORRADOR', '', '', '', 0, 1, 1, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dupFailed = true
        }
        assertTrue("índice único provisionalNumber operativo", dupFailed)

        // Los hijos aceptan filas (sin FK, mismo patrón del esquema F3).
        db.execSQL(
            "INSERT INTO job_activities (id, jobId, description, status, position, notes," +
                " createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('a1', 'j1', 'Actividad', 'PENDIENTE', 1, '', 1, 1, 1, 0)",
        )
        db.query("SELECT COUNT(*) FROM job_activities WHERE jobId = 'j1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }

    /**
     * Migración real v5→v6 (F5): crea una base v5 con datos preexistentes
     * (usuario, cliente, trabajo y operación de cola), migra y verifica que
     * las filas sobreviven y que las tablas e índices de Informes quedan
     * creados. No abre una base v6 fresca.
     */
    @Test
    fun migrate5To6_preservesRowsAndCreatesReportTables() {
        // Semilla sobre la base v5 real (columnas del esquema exportado 5.json).
        helper.createDatabase(testDb, 5).apply {
            execSQL(
                "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
            )
            execSQL(
                "INSERT INTO clients (id, userId, type, name, phone, email, address, notes, archived," +
                    " createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('c1', 'u1', 'PERSONA', 'Cliente Sintético', '', '', '', '', 0, 1, 1, 1, 1)",
            )
            execSQL(
                "INSERT INTO jobs (id, userId, clientId, profileId, provisionalNumber, status," +
                    " executionAddress, notes, clientNameSnapshot, archived, createdAt, updatedAt," +
                    " pendingSync, serverVersion) VALUES ('j1', 'u1', 'c1', 'p1', 'TR-PEND-0001'," +
                    " 'EN_PROCESO', 'Obra', '', 'Cliente Sintético', 0, 1, 1, 1, 3)",
            )
            execSQL(
                "INSERT INTO job_activities (id, jobId, description, status, position, notes," +
                    " createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('a1', 'j1', 'Actividad previa', 'COMPLETADA', 1, '', 1, 1, 1, 2)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 6, true, AppDatabase.MIGRATION_5_6)

        // Filas preexistentes F1–F4 sobreviven intactas tras la migración.
        db.query("SELECT provisionalNumber, status FROM jobs WHERE id = 'j1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("TR-PEND-0001", c.getString(0))
            assertEquals("EN_PROCESO", c.getString(1))
        }
        db.query("SELECT COUNT(*) FROM job_activities WHERE jobId = 'j1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }

        // Tablas F5 creadas.
        for (table in listOf("reports", "report_jobs", "report_versions")) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { c -> assertTrue("tabla $table creada", c.moveToFirst()) }
        }

        // Índice único de numeración provisional INF operativo.
        db.execSQL(
            "INSERT INTO reports (id, userId, clientId, profileId, provisionalNumber, status," +
                " title, destination, content, currentVersion, emittedVersion, clientNameSnapshot," +
                " archived, officialNumber, versionConflict, createdAt, updatedAt, pendingSync," +
                " serverVersion) VALUES ('r1', 'u1', 'c1', 'p1', 'INF-PEND-TEST', 'BORRADOR'," +
                " 'Informe', 'CLIENTE', '', 0, 0, 'Cliente Sintético', 0, NULL, 0, 1, 1, 1, 0)",
        )
        var dupFailed = false
        try {
            db.execSQL(
                "INSERT INTO reports (id, userId, clientId, profileId, provisionalNumber, status," +
                    " title, destination, content, currentVersion, emittedVersion, clientNameSnapshot," +
                    " archived, officialNumber, versionConflict, createdAt, updatedAt, pendingSync," +
                    " serverVersion) VALUES ('r2', 'u1', 'c1', 'p1', 'INF-PEND-TEST', 'BORRADOR'," +
                    " 'Informe 2', 'INTERNO', '', 0, 0, 'Cliente Sintético', 0, NULL, 0, 1, 1, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dupFailed = true
        }
        assertTrue("índice único provisionalNumber operativo", dupFailed)

        // Vínculo y snapshot aceptan filas.
        db.execSQL(
            "INSERT INTO report_jobs (id, reportId, jobId, position, createdAt, pendingSync, serverVersion)" +
                " VALUES ('rj1', 'r1', 'j1', 1, 1, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO report_versions (id, reportId, version, snapshotJson, previousVersionId," +
                " serverOrder, createdAt) VALUES ('rv1', 'r1', 1, '{}', NULL, NULL, 1)",
        )
        db.query("SELECT COUNT(*) FROM report_jobs WHERE reportId = 'r1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM report_versions WHERE reportId = 'r1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }

    /**
     * Migración real v6→v7 (F6): crea una base v6 con datos preexistentes
     * (usuario, cliente, trabajo, informe y operaciones de cola), migra y
     * verifica que las filas F1–F5 sobreviven y que las tablas e índices de
     * Cuentas de Cobro, Métodos y Pagos quedan creados. No abre una base v7
     * fresca.
     */
    @Test
    fun migrate6To7_preservesRowsAndCreatesCollectionTables() {
        // Semilla sobre la base v6 real (columnas del esquema exportado 6.json).
        helper.createDatabase(testDb, 6).apply {
            execSQL(
                "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
            )
            execSQL(
                "INSERT INTO clients (id, userId, type, name, phone, email, address, notes, archived," +
                    " createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('c1', 'u1', 'PERSONA', 'Cliente Sintético', '', '', '', '', 0, 1, 1, 1, 1)",
            )
            execSQL(
                "INSERT INTO jobs (id, userId, clientId, profileId, provisionalNumber, status," +
                    " executionAddress, notes, clientNameSnapshot, archived, createdAt, updatedAt," +
                    " pendingSync, serverVersion) VALUES ('j1', 'u1', 'c1', 'p1', 'TR-PEND-0001'," +
                    " 'ENTREGADO', 'Obra', '', 'Cliente Sintético', 0, 1, 1, 1, 3)",
            )
            execSQL(
                "INSERT INTO reports (id, userId, clientId, profileId, provisionalNumber, status," +
                    " title, destination, content, currentVersion, emittedVersion, clientNameSnapshot," +
                    " archived, officialNumber, versionConflict, createdAt, updatedAt, pendingSync," +
                    " serverVersion) VALUES ('r1', 'u1', 'c1', 'p1', 'INF-PEND-0001', 'EMITIDO'," +
                    " 'Informe previo', 'CLIENTE', '', 1, 1, 'Cliente Sintético', 0, 'INF-202601-001'," +
                    " 0, 1, 1, 1, 4)",
            )
            // Operación de cola pendiente: debe sobrevivir a la migración.
            execSQL(
                "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                    " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                    " createdAt, updatedAt) VALUES ('op-pend', 7, 'report', 'r1', 'upsert', 4, '{}'," +
                    " NULL, 'dev1', 'PENDING', NULL, NULL, 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 7, true, AppDatabase.MIGRATION_6_7)

        // Filas preexistentes F1–F5 sobreviven intactas tras la migración.
        db.query("SELECT status, officialNumber FROM reports WHERE id = 'r1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("EMITIDO", c.getString(0))
            assertEquals("INF-202601-001", c.getString(1))
        }
        db.query("SELECT state FROM sync_operations WHERE opId = 'op-pend'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("PENDING", c.getString(0))
        }

        // Tablas F6 creadas.
        for (table in listOf(
            "collection_accounts", "collection_account_items",
            "collection_account_jobs", "collection_account_quotes",
            "collection_methods", "payments", "payment_assignments",
        )) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { c -> assertTrue("tabla $table creada", c.moveToFirst()) }
        }

        // Índice único de numeración provisional CC operativo.
        db.execSQL(
            "INSERT INTO collection_accounts (id, userId, clientId, profileId, provisionalNumber," +
                " status, currency, gross, totalRetained, net, appliedAmount, balance, issuedAt," +
                " dueDateEpochDay, conditions, clientSnapshotJson, profileSnapshotJson," +
                " retentionsSnapshotJson, clientNameSnapshot, archived, officialNumber, createdAt," +
                " updatedAt, pendingSync, serverVersion) VALUES ('cc1', 'u1', 'c1', 'p1'," +
                " 'CC-PEND-TEST', 'PENDIENTE', 'COP', '100.00', '0.00', '100.00', '0.00', '100.00'," +
                " 1, NULL, '', '', '', '', 'Cliente Sintético', 0, NULL, 1, 1, 1, 0)",
        )
        var dupFailed = false
        try {
            db.execSQL(
                "INSERT INTO collection_accounts (id, userId, clientId, profileId, provisionalNumber," +
                    " status, currency, gross, totalRetained, net, appliedAmount, balance, issuedAt," +
                    " dueDateEpochDay, conditions, clientSnapshotJson, profileSnapshotJson," +
                    " retentionsSnapshotJson, clientNameSnapshot, archived, officialNumber, createdAt," +
                    " updatedAt, pendingSync, serverVersion) VALUES ('cc2', 'u1', 'c1', 'p1'," +
                    " 'CC-PEND-TEST', 'PENDIENTE', 'COP', '50.00', '0.00', '50.00', '0.00', '50.00'," +
                    " 1, NULL, '', '', '', '', 'Cliente Sintético', 0, NULL, 1, 1, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dupFailed = true
        }
        assertTrue("índice único provisionalNumber operativo", dupFailed)

        // Hijas y financieras aceptan filas.
        db.execSQL(
            "INSERT INTO collection_account_items (id, accountId, position, conceptType, description," +
                " quantity, unit, unitPrice, lineTotal, createdAt)" +
                " VALUES ('i1', 'cc1', 1, 'SERVICIO', 'Servicio', '1', 'u', '100.00', '100.00', 1)",
        )
        db.execSQL(
            "INSERT INTO collection_account_jobs (id, accountId, jobId, position, createdAt)" +
                " VALUES ('cj1', 'cc1', 'j1', 1, 1)",
        )
        db.execSQL(
            "INSERT INTO payments (id, userId, clientId, collectionMethodId, amount, paidAtEpochDay," +
                " reference, notes, appliedAmount, unassignedAmount, assignedAccountId," +
                " assignmentStatus, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('pay1', 'u1', 'c1', NULL, '40.00', 1, '', '', '0.00', '40.00', NULL," +
                " 'UNASSIGNED', 1, 1, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO payment_assignments (id, userId, paymentId, action, accountId, fromAccountId," +
                " amount, releasedAmount, reason, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('pa1', 'u1', 'pay1', 'ASSIGN', 'cc1', NULL, '40.00', NULL, 'prueba', 1, 1, 1, 0)",
        )
        db.query("SELECT COUNT(*) FROM collection_account_items WHERE accountId = 'cc1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM payment_assignments WHERE paymentId = 'pay1'").use { c ->
            c.moveToFirst()
            assertEquals(1, c.getInt(0))
        }
    }
}

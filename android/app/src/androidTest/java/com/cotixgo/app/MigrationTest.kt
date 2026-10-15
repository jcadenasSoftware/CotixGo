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

    /**
     * Migración real v7→v8 (F7): crea una base v7 con datos preexistentes
     * (usuario, cliente, cuenta de cobro, pago con asignación y operación
     * de cola pendiente), migra y verifica que:
     *  - las filas F1–F6 sobreviven (pagos, eventos de asignación, cola);
     *  - el Pago migra con `effectiveAmount` = `amount`, `refundedAmount`
     *    = 0, `annulled` = 0 y `assignedTargetType` = 'ACCOUNT';
     *  - el evento de asignación conserva `accountId` y gana
     *    `targetType` = 'ACCOUNT';
     *  - las tablas F7 (consolidated_collections, membresía, ajustes y
     *    reembolsos) quedan creadas con sus índices.
     */
    @Test
    fun migrate7To8_preservesRowsAndCreatesF7Tables() {
        // Semilla sobre la base v7 real (columnas del esquema exportado 7.json).
        helper.createDatabase(testDb, 7).apply {
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
                "INSERT INTO collection_accounts (id, userId, clientId, profileId, provisionalNumber," +
                    " status, currency, gross, totalRetained, net, appliedAmount, balance, issuedAt," +
                    " dueDateEpochDay, conditions, clientSnapshotJson, profileSnapshotJson," +
                    " retentionsSnapshotJson, clientNameSnapshot, archived, officialNumber, createdAt," +
                    " updatedAt, pendingSync, serverVersion) VALUES ('cc1', 'u1', 'c1', 'p1'," +
                    " 'CC-PEND-0001', 'PARCIAL', 'COP', '100.00', '0.00', '100.00', '40.00', '60.00'," +
                    " 1, NULL, '', '', '', '', 'Cliente Sintético', 0, 'CC-202601-001', 1, 1, 1, 5)",
            )
            execSQL(
                "INSERT INTO payments (id, userId, clientId, collectionMethodId, amount, paidAtEpochDay," +
                    " reference, notes, appliedAmount, unassignedAmount, assignedAccountId," +
                    " assignmentStatus, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('pay1', 'u1', 'c1', NULL, '100.00', 21000, 'REF-1', 'pago previo'," +
                    " '40.00', '60.00', 'cc1', 'PARTIAL_ASSIGNED', 1, 1, 1, 7)",
            )
            execSQL(
                "INSERT INTO payment_assignments (id, userId, paymentId, action, accountId, fromAccountId," +
                    " amount, releasedAmount, reason, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('pa1', 'u1', 'pay1', 'ASSIGN', 'cc1', NULL, '40.00', NULL," +
                    " 'Aplicación inicial', 1, 1, 1, 3)",
            )
            // Operación de cola pendiente: debe sobrevivir a la migración.
            execSQL(
                "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                    " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                    " createdAt, updatedAt) VALUES ('op-pend', 9, 'payment', 'pay1', 'upsert', 7, '{}'," +
                    " NULL, 'dev1', 'PENDING', NULL, NULL, 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 8, true, AppDatabase.MIGRATION_7_8)

        // Filas preexistentes F1–F6 sobreviven intactas tras la migración.
        db.query(
            "SELECT amount, effectiveAmount, refundedAmount, annulled, assignedAccountId," +
                " assignedConsolidatedId, assignedTargetType, assignmentStatus" +
                " FROM payments WHERE id = 'pay1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("100.00", c.getString(0))
            assertEquals("100.00", c.getString(1)) // efectivo = amount recibido
            assertEquals("0.00", c.getString(2))
            assertEquals(0, c.getInt(3))
            assertEquals("cc1", c.getString(4))
            assertTrue(c.isNull(5))
            assertEquals("ACCOUNT", c.getString(6))
            assertEquals("PARTIAL_ASSIGNED", c.getString(7)) // se conserva
        }
        db.query(
            "SELECT accountId, targetType, consolidatedId, amount FROM payment_assignments WHERE id = 'pa1'",
        ).use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("cc1", c.getString(0))
            assertEquals("ACCOUNT", c.getString(1))
            assertTrue(c.isNull(2))
            assertEquals("40.00", c.getString(3))
        }
        db.query("SELECT state FROM sync_operations WHERE opId = 'op-pend'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("PENDING", c.getString(0))
        }

        // Tablas F7 creadas.
        for (table in listOf(
            "consolidated_collections", "consolidated_collection_accounts",
            "payment_adjustments", "payment_refunds",
        )) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { c -> assertTrue("tabla $table creada", c.moveToFirst()) }
        }

        // Índice único de numeración provisional CONS operativo.
        db.execSQL(
            "INSERT INTO consolidated_collections (id, userId, clientId, provisionalNumber, status," +
                " currency, totalAmount, appliedAmount, balance, clientNameSnapshot, archived," +
                " officialNumber, createdAt, updatedAt, pendingSync, serverVersion) VALUES ('cons1'," +
                " 'u1', 'c1', 'CONS-PEND-TEST', 'PENDIENTE', 'COP', '60.00', '0.00', '60.00'," +
                " 'Cliente Sintético', 0, NULL, 1, 1, 1, 0)",
        )
        var dupFailed = false
        try {
            db.execSQL(
                "INSERT INTO consolidated_collections (id, userId, clientId, provisionalNumber, status," +
                    " currency, totalAmount, appliedAmount, balance, clientNameSnapshot, archived," +
                    " officialNumber, createdAt, updatedAt, pendingSync, serverVersion) VALUES ('cons2'," +
                    " 'u1', 'c1', 'CONS-PEND-TEST', 'PENDIENTE', 'COP', '30.00', '0.00', '30.00'," +
                    " 'Cliente Sintético', 0, NULL, 1, 1, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dupFailed = true
        }
        assertTrue("índice único provisionalNumber operativo", dupFailed)

        // Membresía y eventos append-only aceptan filas.
        db.execSQL(
            "INSERT INTO consolidated_collection_accounts (id, consolidatedId, accountId, position," +
                " createdAt) VALUES ('lm1', 'cons1', 'cc1', 1, 1)",
        )
        db.execSQL(
            "INSERT INTO payment_adjustments (id, userId, paymentId, kind, reason, newEffectiveAmount," +
                " effectiveBefore, effectiveAfter, releasedAmount, releasedFromType, releasedFromId," +
                " createdAt, updatedAt, pendingSync, serverVersion) VALUES ('adj1', 'u1', 'pay1'," +
                " 'CORRECTION', 'Ajuste', '80.00', '100.00', '80.00', '20.00', 'ACCOUNT', 'cc1'," +
                " 1, 1, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO payment_refunds (id, userId, paymentId, amount, refundedAtEpochDay," +
                " collectionMethodId, reason, createdAt, updatedAt, pendingSync, serverVersion)" +
                " VALUES ('rf1', 'u1', 'pay1', '10.00', 21001, NULL, 'Devolución', 1, 1, 1, 0)",
        )
        db.query("SELECT COUNT(*) FROM consolidated_collection_accounts WHERE consolidatedId = 'cons1'")
            .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM payment_adjustments WHERE paymentId = 'pay1'")
            .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.query("SELECT COUNT(*) FROM payment_refunds WHERE paymentId = 'pay1'")
            .use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
    }

    /**
     * Migración real v8→v9 (F8): crea una base v8 con datos preexistentes
     * (usuario, trabajo con material descriptivo histórico y operación de
     * cola pendiente), migra y verifica que:
     *  - las filas F1–F7 sobreviven y la cola conserva la operación;
     *  - el `job_material` histórico gana `materialId`/`condition` nulos
     *    (sigue siendo descriptivo: nunca se reinterpreta);
     *  - las tablas F8 (materials, purchases, purchase_items,
     *    inventory_movements, material_stocks) quedan creadas con sus
     *    índices, incluido el único de `provisionalNumber` en Compras.
     */
    @Test
    fun migrate8To9_preservesRowsAndCreatesF8Tables() {
        // Semilla sobre la base v8 real (columnas del esquema exportado 8.json).
        helper.createDatabase(testDb, 8).apply {
            execSQL(
                "INSERT INTO users (id, name, mainCurrency, email, createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('u1', 'Usuario Sintético', 'COP', 'u@synthetic.local', 1, 1, 0, 1)",
            )
            execSQL(
                "INSERT INTO jobs (id, userId, clientId, profileId, provisionalNumber, status," +
                    " executionAddress, notes, clientNameSnapshot, archived, createdAt, updatedAt," +
                    " pendingSync, serverVersion) VALUES ('j1', 'u1', 'c1', 'p1', 'TR-PEND-0001'," +
                    " 'EN_PROCESO', 'Obra', '', 'Cliente Sintético', 0, 1, 1, 1, 3)",
            )
            // Material descriptivo histórico F4: sin materialId ni condition.
            execSQL(
                "INSERT INTO job_materials (id, jobId, description, origin, quantity, unit, notes," +
                    " createdAt, updatedAt, pendingSync, serverVersion)" +
                    " VALUES ('jm1', 'j1', 'Cemento histórico', 'PROFESIONAL', '2', 'kg', ''," +
                    " 1, 1, 0, 2)",
            )
            // Operación de cola pendiente: debe sobrevivir a la migración.
            execSQL(
                "INSERT INTO sync_operations (opId, seq, entityType, entityId, opType, baseServerVersion," +
                    " payloadJson, supersedesOpId, deviceId, state, resultJson, resultCode, attempts," +
                    " createdAt, updatedAt) VALUES ('op-pend', 9, 'job_material', 'jm1', 'upsert', 2," +
                    " '{}', NULL, 'dev1', 'PENDING', NULL, NULL, 0, 1, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(testDb, 9, true, AppDatabase.MIGRATION_8_9)

        // El registro histórico sigue descriptivo: vínculo nulo, datos intactos.
        db.query("SELECT description, materialId, condition FROM job_materials WHERE id = 'jm1'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Cemento histórico", c.getString(0))
            assertTrue("materialId histórico debe quedar NULL", c.isNull(1))
            assertTrue("condition histórica debe quedar NULL", c.isNull(2))
        }
        db.query("SELECT state FROM sync_operations WHERE opId = 'op-pend'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("PENDING", c.getString(0))
        }

        // Tablas F8 creadas.
        for (table in listOf(
            "materials", "purchases", "purchase_items", "inventory_movements", "material_stocks",
        )) {
            db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table))
                .use { c -> assertTrue("tabla $table creada", c.moveToFirst()) }
        }

        // Índice único de numeración provisional COM operativo.
        db.execSQL(
            "INSERT INTO purchases (id, userId, provisionalNumber, status, purchaseDateEpochDay," +
                " supplierPlace, reference, notes, currency, totalAmount, archived, officialNumber," +
                " createdAt, updatedAt, pendingSync, serverVersion) VALUES ('pu1', 'u1'," +
                " 'COM-PEND-TEST', 'BORRADOR', 21005, '', '', '', 'COP', '0.00', 0, NULL, 1, 1, 1, 0)",
        )
        var dupFailed = false
        try {
            db.execSQL(
                "INSERT INTO purchases (id, userId, provisionalNumber, status, purchaseDateEpochDay," +
                    " supplierPlace, reference, notes, currency, totalAmount, archived, officialNumber," +
                    " createdAt, updatedAt, pendingSync, serverVersion) VALUES ('pu2', 'u1'," +
                    " 'COM-PEND-TEST', 'BORRADOR', 21005, '', '', '', 'COP', '0.00', 0, NULL, 1, 1, 1, 0)",
            )
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            dupFailed = true
        }
        assertTrue("índice único provisionalNumber operativo", dupFailed)

        // Las tablas F8 aceptan filas (líneas, movimientos y espejo de stock).
        db.execSQL(
            "INSERT INTO materials (id, userId, name, unit, description, archived, createdAt," +
                " updatedAt, pendingSync, serverVersion) VALUES ('m1', 'u1', 'Cemento', 'kg', ''," +
                " 0, 1, 1, 1, 0)",
        )
        db.execSQL(
            "INSERT INTO purchase_items (id, purchaseId, position, materialId, materialNameSnapshot," +
                " quantity, unit, unitPrice, lineTotal, condition, createdAt) VALUES ('pi1', 'pu1'," +
                " 1, 'm1', 'Cemento', '2', 'kg', '100.00', '200.00', 'NUEVO', 1)",
        )
        db.execSQL(
            "INSERT INTO inventory_movements (id, userId, materialId, condition, movementType, origin," +
                " quantity, unit, unitCost, purchaseId, jobId, jobMaterialId, note, occurredAt," +
                " createdAt, updatedAt, pendingSync, serverVersion) VALUES ('mv1', 'u1', 'm1'," +
                " 'NUEVO', 'PURCHASE_IN', 'COMPRA', '2', 'kg', '100.00', 'pu1', NULL, NULL," +
                " 'Entrada por compra', 1, 1, 1, 0, 1)",
        )
        db.execSQL(
            "INSERT INTO material_stocks (materialId, stockNew, stockUsed, updatedAt, serverVersion)" +
                " VALUES ('m1', '2', '0', 1, 1)",
        )
        db.query("SELECT COUNT(*) FROM purchase_items WHERE purchaseId = 'pu1'").use { c ->
            c.moveToFirst(); assertEquals(1, c.getInt(0))
        }
        db.query("SELECT stockNew FROM material_stocks WHERE materialId = 'm1'").use { c ->
            c.moveToFirst(); assertEquals("2", c.getString(0))
        }
    }
}

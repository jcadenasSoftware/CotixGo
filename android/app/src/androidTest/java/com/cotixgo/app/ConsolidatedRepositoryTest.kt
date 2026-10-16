package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.PaymentAssignmentStatus
import com.cotixgo.app.core.PaymentTargetKind
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.CollectionAccountInput
import com.cotixgo.app.data.CollectionItemInput
import com.cotixgo.app.data.ConsolidatedInput
import com.cotixgo.app.data.PaymentInput
import com.cotixgo.app.data.SyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Pruebas del repositorio F7 sobre Room real (BD en memoria): Consolidado
 * de Cuentas (elegibilidad, mismo Cliente/moneda, membresía única activa,
 * cancelación liberadora), asignación de Pagos a destino Cuenta o
 * Consolidado, corrección al alza/a la baja con liberación LIFO, piso de
 * Reembolsos sobre la reserva compartida, anulación terminal y encolado
 * durable de los eventos append-only. Datos exclusivamente sintéticos.
 */
@RunWith(AndroidJUnit4::class)
class ConsolidatedRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncRepository
    private lateinit var repo: AppRepository
    private lateinit var prodBefore: Map<String, String?>

    // Preferencias exclusivas de prueba (D2): la sesión nunca toca
    // cotixgo.session ni los artefactos reales de la app.
    private val prefsName = TestIsolation.name("e2e-session-consolidated")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        prodBefore = TestIsolation.snapshotProd(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val session = com.cotixgo.app.data.Session(ctx, db, prefsName).apply {
            apiBaseUrl = "http://127.0.0.1:1"
        }
        sync = SyncRepository(db, session)
        repo = AppRepository(db, sync)
    }

    @After
    fun tearDown() {
        db.close()
        TestIsolation.deletePrefs(
            ApplicationProvider.getApplicationContext(), prefsName)
        TestIsolation.assertProdUntouched(
            ApplicationProvider.getApplicationContext(), prodBefore)
    }

    private suspend fun seed(): Triple<String, String, String> {
        repo.completeOnboarding("Test User", "Perfil Test")
        val user = db.userDao().observe().first()!!
        val profile = db.profileDao().observeForUser(user.id).first().first()
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente T"))
        val client = db.clientDao().observeForUser(user.id).first().first()
        return Triple(user.id, profile.id, client.id)
    }

    private fun accountInput(clientId: String, profileId: String) = CollectionAccountInput(
        clientId = clientId,
        profileId = profileId,
        currency = "COP",
        items = listOf(
            CollectionItemInput(
                conceptType = ConceptType.SERVICIO,
                description = "Servicio ejecutado",
                quantity = "2", unit = "u", unitPrice = "50.00",
            ),
        ),
    )

    private suspend fun newAccount(clientId: String, profileId: String): String =
        repo.saveCollectionAccount(accountInput(clientId, profileId))!!

    @Test
    fun consolidado_elegibilidad_composicion_yMembresiaUnica() = runTest {
        val (userId, profileId, clientId) = seed()
        val acc1 = newAccount(clientId, profileId)
        val acc2 = newAccount(clientId, profileId)
        val acc3 = newAccount(clientId, profileId)

        // Al menos dos miembros.
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1), "COP")))
        // Sin duplicados.
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc1), "COP")))

        // Otro cliente: rechazado por mismo Cliente.
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        val accB = newAccount(clienteB.id, profileId)
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, accB), "COP")))

        // Otra moneda: rechazado.
        val accUsd = repo.saveCollectionAccount(
            accountInput(clientId, profileId).copy(currency = "USD"),
        )!!
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, accUsd), "COP")))

        // Cuenta cancelada o sin saldo: no elegible.
        val accCancel = newAccount(clientId, profileId)
        assertTrue(repo.cancelCollectionAccount(accCancel))
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, accCancel), "COP")))

        // Válido: dos Cuentas del mismo Cliente y moneda.
        val consId = repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc2), "COP"),
        )!!
        val cons = db.consolidatedCollectionDao().get(consId)!!
        assertEquals("PENDIENTE", cons.status)
        assertTrue(cons.provisionalNumber.startsWith("CONS-PEND-"))
        assertEquals("200.00", cons.totalAmount)
        assertEquals("200.00", cons.balance)
        assertEquals("Cliente T", cons.clientNameSnapshot)
        assertEquals(
            listOf(acc1, acc2),
            db.consolidatedCollectionAccountDao().forConsolidated(consId).map { it.accountId },
        )
        val ops = db.syncDao().pendingOps()
        assertTrue(ops.any { it.entityType == "consolidated_collection" && it.entityId == consId })

        // Doble vínculo: acc1 ya pertenece a un Consolidado activo.
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc3), "COP")))

        // Cancelación: libera la membresía y acc1 puede reagruparse.
        assertTrue(repo.cancelConsolidatedCollection(consId))
        assertEquals("CANCELADA", db.consolidatedCollectionDao().get(consId)!!.status)
        // La membresía histórica se conserva.
        assertEquals(2, db.consolidatedCollectionAccountDao().forConsolidated(consId).size)
        assertNotNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc3), "COP")))
    }

    @Test
    fun consolidado_miembroCongelaCuenta_yPagoAConsolidado() = runTest {
        val (_, profileId, clientId) = seed()
        val acc1 = newAccount(clientId, profileId)
        val acc2 = newAccount(clientId, profileId)
        val consId = repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc2), "COP"),
        )!!

        // Miembro de consolidado activo: ni edición ni cancelación ni
        // asignación directa.
        assertNull(repo.saveCollectionAccount(
            accountInput(clientId, profileId).copy(conditions = "x"), existingId = acc1))
        assertFalse(repo.cancelCollectionAccount(acc1))
        val payBlocked = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "10.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(), accountId = acc1),
        )
        assertNull(payBlocked)

        // Pago al Consolidado: aplica contra su saldo agregado.
        val payId = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "150.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                consolidatedId = consId, initialAssignReason = "Cobro agrupado"),
        )!!
        val cons = db.consolidatedCollectionDao().get(consId)!!
        assertEquals("150.00", cons.appliedAmount)
        assertEquals("50.00", cons.balance)
        assertEquals("PARCIAL", cons.status)
        val p = db.paymentDao().get(payId)!!
        assertEquals("150.00", p.appliedAmount)
        assertEquals(consId, p.assignedConsolidatedId)
        assertEquals("CONSOLIDATED", p.assignedTargetType)
        // Las Cuentas miembro NO se tocan: no hay distribución interna.
        assertEquals("0.00", db.collectionAccountDao().get(acc1)!!.appliedAmount)
        assertEquals("100.00", db.collectionAccountDao().get(acc1)!!.balance)

        // Con pagos aplicados: ni editar miembros ni cancelar.
        assertNull(repo.saveConsolidatedCollection(
            ConsolidatedInput(clientId, listOf(acc1, acc2, newAccount(clientId, profileId)), "COP"),
            existingId = consId))
        assertFalse(repo.cancelConsolidatedCollection(consId))

        // El evento de asignación apunta al Consolidado.
        val events = db.paymentAssignmentDao().forPayment(payId)
        assertEquals(1, events.size)
        assertEquals("CONSOLIDATED", events[0].targetType)
        assertEquals(consId, events[0].consolidatedId)
        assertNull(events[0].accountId)
    }

    @Test
    fun correccion_alza_quedaUnassigned_baja_lifoPisoReembolsado() = runTest {
        val (_, profileId, clientId) = seed()
        val acc1 = newAccount(clientId, profileId)
        val payId = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "100.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                accountId = acc1, initialAssignReason = "Aplicación inicial"),
        )!!
        // estado inicial: efectivo 100, aplicado 100, sin asignar 0
        assertNull(repo.correctPayment(payId, "130.00", "El cliente pagó de más"))
        var p = db.paymentDao().get(payId)!!
        assertEquals("130.00", p.effectiveAmount)
        assertEquals("100.00", p.appliedAmount)
        assertEquals("30.00", p.unassignedAmount) // el incremento queda UNASSIGNED
        assertEquals(PaymentAssignmentStatus.PARTIAL_ASSIGNED.name, p.assignmentStatus)

        // Reembolso sobre lo disponible: 130 − 100 − 0 = 30.
        assertNull(repo.refundPayment(payId, "30.00",
            LocalDate.now().toEpochDay(), null, "Devolución del excedente"))
        p = db.paymentDao().get(payId)!!
        assertEquals("30.00", p.refundedAmount)
        assertEquals("0.00", p.unassignedAmount)

        // Corrección a la baja por debajo del reembolsado: rechazada.
        assertNotNull(repo.correctPayment(payId, "20.00", "Intento inválido"))
        // A la baja válida: 130 → 90 libera 40 de la cuenta (sin remanente).
        assertNull(repo.correctPayment(payId, "90.00", "Importe rectificado"))
        p = db.paymentDao().get(payId)!!
        assertEquals("90.00", p.effectiveAmount)
        assertEquals("60.00", p.appliedAmount) // 100 − 40 liberados
        assertEquals("0.00", p.unassignedAmount)
        // La liberación devuelve saldo a la Cuenta: neto 100 − aplicado 60.
        assertEquals("40.00", db.collectionAccountDao().get(acc1)!!.balance)
        assertEquals("PARCIAL", db.collectionAccountDao().get(acc1)!!.status)
        // Invariante: aplicado + reembolsado = 60 + 30 = 90 = efectivo.

        // Los eventos de ajuste son append-only con trazabilidad.
        val adjs = db.paymentAdjustmentDao().forPayment(payId)
        assertEquals(2, adjs.size)
        assertEquals(listOf("CORRECTION", "CORRECTION"), adjs.map { it.kind })
        assertEquals("40.00", adjs[1].releasedAmount)
        assertEquals("ACCOUNT", adjs[1].releasedFromType)
        assertEquals(acc1, adjs[1].releasedFromId)
        val ops = db.syncDao().pendingOps()
        assertTrue(ops.any { it.entityType == "payment_adjustment" })
        assertTrue(ops.any { it.entityType == "payment_refund" })
    }

    @Test
    fun reembolso_respetaReservaCompartida_yAnulacionTerminal() = runTest {
        val (_, profileId, clientId) = seed()
        val acc1 = newAccount(clientId, profileId)
        val payId = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "100.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                accountId = acc1, initialAssignReason = "Aplicación inicial"),
        )!!

        // El dinero aplicado no es reembolsable: disponible = 0.
        assertNotNull(repo.refundPayment(payId, "1.00",
            LocalDate.now().toEpochDay(), null, "Intento sobre lo aplicado"))

        // Se libera por operación auditada y entonces sí es reembolsable.
        assertNull(repo.unassignPayment(payId, "Liberación para devolver"))
        assertNull(repo.refundPayment(payId, "60.00",
            LocalDate.now().toEpochDay(), null, "Devolución parcial"))
        var p = db.paymentDao().get(payId)!!
        assertEquals("60.00", p.refundedAmount)
        assertEquals("40.00", p.unassignedAmount)
        // No puede exceder el remanente.
        assertNotNull(repo.refundPayment(payId, "60.00",
            LocalDate.now().toEpochDay(), null, "Excede remanente"))

        // Anulación: terminal, una sola vez, efectivo = reembolsado.
        assertNotNull(repo.annulPayment(payId, "  "))
        assertNull(repo.annulPayment(payId, "Pago registrado por error"))
        p = db.paymentDao().get(payId)!!
        assertTrue(p.annulled)
        assertEquals("60.00", p.effectiveAmount)
        assertEquals("0.00", p.appliedAmount)
        assertEquals("0.00", p.unassignedAmount)

        // Tras la anulación se rechaza todo: asignar, corregir, reembolsar.
        assertNotNull(repo.assignPaymentToTarget(payId, PaymentTargetKind.ACCOUNT, acc1, "10.00", "x"))
        assertNotNull(repo.correctPayment(payId, "80.00", "x"))
        assertNotNull(repo.refundPayment(payId, "10.00",
            LocalDate.now().toEpochDay(), null, "x"))
        // Una segunda anulación (operación nueva, no replay) se rechaza.
        assertNotNull(repo.annulPayment(payId, "Duplicada"))

        val adjs = db.paymentAdjustmentDao().forPayment(payId)
        assertEquals(listOf("ANNULMENT"), adjs.map { it.kind })
        assertEquals("60.00", adjs[0].effectiveAfter) // efectivo fijado al reembolsado
    }
}

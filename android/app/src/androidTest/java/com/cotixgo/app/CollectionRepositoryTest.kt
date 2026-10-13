package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.PaymentAssignmentStatus
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.data.CollectionAccountInput
import com.cotixgo.app.data.CollectionItemInput
import com.cotixgo.app.data.CollectionMethodInput
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.PaymentInput
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
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
 * Pruebas del repositorio F6 sobre Room real (BD en memoria): Cuenta de
 * Cobro con líneas y motor F2, asociaciones Trabajo/Cotización (sin mezcla,
 * mismo Cliente+Perfil, doble vínculo, solo Cotización APROBADA), edición
 * solo en PENDIENTE sin pagos, cancelación que libera orígenes, Métodos de
 * Cobro y Pagos append-only con asignar/retirar/reasignar auditados.
 * Datos exclusivamente sintéticos.
 */
@RunWith(AndroidJUnit4::class)
class CollectionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var sync: SyncRepository
    private lateinit var repo: AppRepository

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        // SyncRepository sin red: solo para verificar el encolado durable en
        // la misma transacción; nunca se llama push/pull en estas pruebas.
        val session = com.cotixgo.app.data.Session(ctx, db).apply {
            apiBaseUrl = "http://127.0.0.1:1"
        }
        sync = SyncRepository(db, session)
        repo = AppRepository(db, sync)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(): Triple<String, String, String> {
        repo.completeOnboarding("Test User", "Perfil Test")
        val user = db.userDao().observe().first()!!
        val profile = db.profileDao().observeForUser(user.id).first().first()
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente T"))
        val client = db.clientDao().observeForUser(user.id).first().first()
        return Triple(user.id, profile.id, client.id)
    }

    private suspend fun seedApprovedQuote(clientId: String, profileId: String): String {
        val qid = repo.saveQuote(
            QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = LocalDate.now().toEpochDay(),
                validityDays = 15, currency = "COP",
                lines = listOf(
                    QuoteLineInput(
                        conceptType = ConceptType.SERVICIO,
                        description = "Servicio cotizado",
                        quantity = "1", unit = "", unitPrice = "100.00",
                    ),
                ),
                retentions = emptyList(),
            ),
        )!!
        assertTrue(repo.emitQuote(qid))
        assertTrue(repo.setQuoteStatus(qid, QuoteStatus.APROBADA))
        return qid
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
            CollectionItemInput(
                conceptType = ConceptType.MATERIAL,
                description = "Material usado",
                quantity = "1", unitPrice = "20.00",
            ),
        ),
    )

    @Test
    fun crear_pendienteConProvisionalCC_totalesYItems() = runTest {
        val (_, profileId, clientId) = seed()
        val id = repo.saveCollectionAccount(accountInput(clientId, profileId))!!
        val a = db.collectionAccountDao().get(id)!!
        assertEquals(CollectionAccountStatus.PENDIENTE.name, a.status)
        assertTrue(a.provisionalNumber.startsWith("CC-PEND-"))
        assertNull(a.officialNumber)
        assertEquals("120.00", a.gross)
        assertEquals("120.00", a.net)
        assertEquals("0.00", a.appliedAmount)
        assertEquals("120.00", a.balance)
        assertEquals("Cliente T", a.clientNameSnapshot)
        assertTrue(a.clientSnapshotJson.contains("Cliente T"))
        assertEquals(2, db.collectionAccountItemDao().forAccount(id).size)
        // Operación encolada en la misma transacción.
        val ops = db.syncDao().pendingOps()
        assertTrue(ops.any { it.entityType == "collection_account" && it.entityId == id })
    }

    @Test
    fun asociaciones_sinMezcla_mismoClientePerfil_dobleVinculo_ySoloAprobada() = runTest {
        val (userId, profileId, clientId) = seed()
        val jobId = repo.saveJob(
            JobInput(clientId = clientId, profileId = profileId,
                startDateEpochDay = LocalDate.now().toEpochDay()),
        )!!
        val quoteId = seedApprovedQuote(clientId, profileId)

        // Nunca mezcla Trabajos y Cotizaciones.
        assertNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(jobIds = listOf(jobId), quoteIds = listOf(quoteId)),
            ),
        )
        // Trabajo de otro Cliente → rechaza.
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        val jobB = repo.saveJob(
            JobInput(clientId = clienteB.id, profileId = profileId,
                startDateEpochDay = LocalDate.now().toEpochDay()),
        )!!
        assertNull(
            repo.saveCollectionAccount(accountInput(clientId, profileId).copy(jobIds = listOf(jobB))),
        )

        // Cotización no aprobada → no elegible.
        val draftQuote = repo.saveQuote(
            QuoteInput(
                clientId = clientId, profileId = profileId,
                issueDateEpochDay = LocalDate.now().toEpochDay(),
                validityDays = 15, currency = "COP",
                lines = listOf(
                    QuoteLineInput(
                        conceptType = ConceptType.SERVICIO, description = "X",
                        quantity = "1", unit = "", unitPrice = "10.00",
                    ),
                ),
                retentions = emptyList(),
            ),
        )!!
        assertNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(quoteIds = listOf(draftQuote)),
            ),
        )

        // Varios orígenes del mismo tipo sí; el doble vínculo se bloquea.
        val acc1 = repo.saveCollectionAccount(
            accountInput(clientId, profileId).copy(jobIds = listOf(jobId)),
        )!!
        assertNull(
            repo.saveCollectionAccount(accountInput(clientId, profileId).copy(jobIds = listOf(jobId))),
        )
        val acc2 = repo.saveCollectionAccount(
            accountInput(clientId, profileId).copy(quoteIds = listOf(quoteId)),
        )!!
        assertNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(quoteIds = listOf(quoteId)),
            ),
        )
        // La cancelación libera la asociación.
        assertTrue(repo.cancelCollectionAccount(acc2))
        assertNotNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(quoteIds = listOf(quoteId)),
            ),
        )
        // Edición en PENDIENTE sin pagos sigue permitida.
        assertNotNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(conditions = "30 días"),
                existingId = acc1,
            ),
        )
        assertEquals("30 días", db.collectionAccountDao().get(acc1)!!.conditions)
    }

    @Test
    fun pago_aplicacionDirecta_derivaParcialYPagada_eInmutable() = runTest {
        val (_, profileId, clientId) = seed()
        val accId = repo.saveCollectionAccount(accountInput(clientId, profileId))!!

        // Pago parcial directo: aplica lo necesario dentro del saldo.
        val p1 = repo.savePayment(
            PaymentInput(
                clientId = clientId, amount = "40.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                accountId = accId,
            ),
        )!!
        var a = db.collectionAccountDao().get(accId)!!
        assertEquals("40.00", a.appliedAmount)
        assertEquals("80.00", a.balance)
        assertEquals(CollectionAccountStatus.PARCIAL.name, a.status)
        var p = db.paymentDao().get(p1)!!
        assertEquals("40.00", p.appliedAmount)
        assertEquals("0.00", p.unassignedAmount)
        assertEquals(PaymentAssignmentStatus.ASSIGNED.name, p.assignmentStatus)
        assertEquals(accId, p.assignedAccountId)

        // Con pago aplicado la cuenta es inmutable.
        assertNull(
            repo.saveCollectionAccount(
                accountInput(clientId, profileId).copy(conditions = "cambio"),
                existingId = accId,
            ),
        )
        // Ni se cancela.
        assertFalse(repo.cancelCollectionAccount(accId))

        // Segundo pago que supera el saldo: aplica solo lo necesario.
        val p2 = repo.savePayment(
            PaymentInput(
                clientId = clientId, amount = "200.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                accountId = accId,
            ),
        )!!
        a = db.collectionAccountDao().get(accId)!!
        assertEquals("120.00", a.appliedAmount)
        assertEquals("0.00", a.balance)
        assertEquals(CollectionAccountStatus.PAGADA.name, a.status)
        p = db.paymentDao().get(p2)!!
        assertEquals("80.00", p.appliedAmount)
        assertEquals("120.00", p.unassignedAmount) // el excedente queda UNASSIGNED
        assertEquals(PaymentAssignmentStatus.PARTIAL_ASSIGNED.name, p.assignmentStatus)
    }

    @Test
    fun pago_sinAsignar_assignUnassignReassign_auditados() = runTest {
        val (_, profileId, clientId) = seed()
        val acc1 = repo.saveCollectionAccount(accountInput(clientId, profileId))!!
        val acc2 = repo.saveCollectionAccount(accountInput(clientId, profileId))!!

        // Pago sin asignar vinculado al Cliente.
        val payId = repo.savePayment(
            PaymentInput(
                clientId = clientId, amount = "150.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
            ),
        )!!
        var p = db.paymentDao().get(payId)!!
        assertEquals(PaymentAssignmentStatus.UNASSIGNED.name, p.assignmentStatus)
        assertEquals("150.00", p.unassignedAmount)

        // Asignar 100 a la Cuenta 1 (saldo 120): aplica 100.
        assertNull(repo.assignPayment(payId, acc1, "100.00", "Abono acordado"))
        p = db.paymentDao().get(payId)!!
        assertEquals("100.00", p.appliedAmount)
        assertEquals("50.00", p.unassignedAmount)
        assertEquals(PaymentAssignmentStatus.PARTIAL_ASSIGNED.name, p.assignmentStatus)
        assertEquals(acc1, p.assignedAccountId)
        assertEquals("20.00", db.collectionAccountDao().get(acc1)!!.balance)
        assertEquals(CollectionAccountStatus.PARCIAL.name, db.collectionAccountDao().get(acc1)!!.status)

        // Reasignar a la Cuenta 2: libera 100 de acc1, aplica min(100, saldo acc2=120).
        assertNull(repo.reassignPayment(payId, acc2, "Corrección de destino"))
        p = db.paymentDao().get(payId)!!
        assertEquals(acc2, p.assignedAccountId)
        assertEquals("100.00", p.appliedAmount)
        assertEquals("120.00", db.collectionAccountDao().get(acc1)!!.balance)
        assertEquals(CollectionAccountStatus.PENDIENTE.name, db.collectionAccountDao().get(acc1)!!.status)
        assertEquals("20.00", db.collectionAccountDao().get(acc2)!!.balance)

        // Retirar la aplicación vigente con motivo.
        assertNull(repo.unassignPayment(payId, "Devolución de asignación"))
        p = db.paymentDao().get(payId)!!
        assertEquals("0.00", p.appliedAmount)
        assertEquals("150.00", p.unassignedAmount)
        assertEquals(PaymentAssignmentStatus.UNASSIGNED.name, p.assignmentStatus)
        assertNull(p.assignedAccountId)
        assertEquals(CollectionAccountStatus.PENDIENTE.name, db.collectionAccountDao().get(acc2)!!.status)

        // Historial append-only: 3 eventos auditados en orden.
        val events = db.paymentAssignmentDao().forPayment(payId)
        assertEquals(3, events.size)
        assertEquals(listOf("ASSIGN", "REASSIGN", "UNASSIGN"), events.map { it.action })
        assertEquals("Corrección de destino", events[1].reason)
        // El evento reasignar conserva lo liberado y lo aplicado.
        assertEquals("100.00", events[1].releasedAmount)
        assertEquals("100.00", events[1].amount)
        // Motivo obligatorio.
        assertNotNull(repo.assignPayment(payId, acc1, "10.00", " "))
    }

    @Test
    fun pago_validaciones_yMetodoDeCobro() = runTest {
        val (userId, profileId, clientId) = seed()
        val accId = repo.saveCollectionAccount(accountInput(clientId, profileId))!!

        // Importe no positivo y cliente inexistente → rechazados.
        assertNull(repo.savePayment(PaymentInput(clientId = clientId, amount = "0",
            paidAtEpochDay = LocalDate.now().toEpochDay())))
        assertNull(repo.savePayment(PaymentInput(clientId = "no-existe", amount = "10.00",
            paidAtEpochDay = LocalDate.now().toEpochDay())))
        // Cuenta de otro cliente → rechazada.
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente B"))
        val clienteB = db.clientDao().observeForUser(userId).first().first { it.name == "Cliente B" }
        assertNull(
            repo.savePayment(
                PaymentInput(clientId = clienteB.id, amount = "10.00",
                    paidAtEpochDay = LocalDate.now().toEpochDay(), accountId = accId),
            ),
        )
        // Método inexistente → rechazado.
        assertNull(
            repo.savePayment(
                PaymentInput(clientId = clientId, amount = "10.00",
                    paidAtEpochDay = LocalDate.now().toEpochDay(),
                    collectionMethodId = "no-existe"),
            ),
        )

        // Método de Cobro: nombre obligatorio, archivable.
        assertNull(repo.saveCollectionMethod(CollectionMethodInput(name = "  ")))
        val mid = repo.saveCollectionMethod(CollectionMethodInput(name = "Transferencia", detail = "Banco X"))!!
        repo.setCollectionMethodArchived(mid, true)
        assertTrue(db.collectionMethodDao().get(mid)!!.archived)
        repo.setCollectionMethodArchived(mid, false)
        assertFalse(db.collectionMethodDao().get(mid)!!.archived)

        // Pago con método válido.
        val pid = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "10.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(), collectionMethodId = mid),
        )
        assertNotNull(pid)
    }

    /**
     * Regresión de la revisión PR #13: `bindAccount` debe reasignar el
     * userId de TODAS las tablas F6 al pasar del usuario local anónimo a la
     * identidad canónica. Crea datos F6 sintéticos antes de vincular,
     * ejecuta la vinculación y exige que Cuentas, Métodos y Pagos sigan
     * visibles por `observeForUser(canonical)` conservando sus relaciones
     * (líneas, vínculos y evento de asignación) y que la cola encolada
     * quede reescrita con la identidad canónica.
     */
    @Test
    fun bindAccount_reasignaDatosF6AlUsuarioCanonico() = runTest {
        val (anonUserId, profileId, clientId) = seed()
        val jobId = repo.saveJob(
            JobInput(clientId = clientId, profileId = profileId,
                startDateEpochDay = LocalDate.now().toEpochDay()),
        )!!
        val accountId = repo.saveCollectionAccount(
            accountInput(clientId, profileId).copy(jobIds = listOf(jobId)),
        )!!
        val methodId = repo.saveCollectionMethod(CollectionMethodInput(name = "Nequi"))!!
        val paymentId = repo.savePayment(
            PaymentInput(clientId = clientId, amount = "40.00",
                paidAtEpochDay = LocalDate.now().toEpochDay(),
                collectionMethodId = methodId, accountId = accountId,
                initialAssignReason = "Aplicación inicial"),
        )!!

        // Vinculación: el usuario local anónimo queda sustituido.
        val canonical = "canonical-user-id"
        sync.bindAccount(canonical, "sintetico@example.test", "tok")

        // Las entidades F6 siguen visibles para el usuario canónico.
        val accounts = repo.observeCollectionAccounts(canonical).first()
        assertEquals(listOf(accountId), accounts.map { it.id })
        val methods = repo.observeCollectionMethods(canonical).first()
        assertEquals(listOf(methodId), methods.map { it.id })
        val payments = repo.observePayments(canonical).first()
        assertEquals(listOf(paymentId), payments.map { it.id })
        assertEquals(accountId, payments[0].assignedAccountId)

        // Relaciones conservadas: líneas, vínculo con el Trabajo y el
        // evento de asignación auditado — ya bajo el titular canónico.
        assertEquals(2, db.collectionAccountItemDao().forAccount(accountId).size)
        assertEquals(listOf(jobId), db.collectionAccountJobDao().forAccount(accountId).map { it.jobId })
        val assignments = db.paymentAssignmentDao().forPayment(paymentId)
        assertEquals(1, assignments.size)
        assertEquals(canonical, assignments[0].userId)

        // La cola durable se regeneró con la identidad canónica en el
        // payload: ninguna operación conserva el userId anónimo.
        val pending = db.syncDao().pendingOps()
        assertTrue(pending.isNotEmpty())
        assertFalse(pending.any { it.payloadJson.contains(anonUserId) })
        assertTrue(pending.any { it.entityType == "collection_account" && it.entityId == accountId })
        assertTrue(pending.any { it.entityType == "payment" && it.entityId == paymentId })
        assertTrue(pending.any { it.entityType == "payment_assignment" })
    }
}

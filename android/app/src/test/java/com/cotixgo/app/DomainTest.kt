package com.cotixgo.app

import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.CollectionAccountTransitions
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.PaymentAssignmentStatus
import com.cotixgo.app.core.ReportStatus
import com.cotixgo.app.core.ReportTransitions
import com.cotixgo.app.core.deriveAssignmentStatus
import com.cotixgo.app.core.deriveCollectionStatus
import com.cotixgo.app.core.validateClient
import com.cotixgo.app.core.validateCollectionMethod
import com.cotixgo.app.core.validateOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DomainTest {

    @Test
    fun `new ids are unique UUIDv4`() {
        val ids = (1..200).map { Ids.new() }.toSet()
        assertEquals(200, ids.size)
        ids.forEach {
            val uuid = UUID.fromString(it)
            assertEquals(it, uuid.toString())
            assertEquals(4, uuid.version())
            assertEquals(2, uuid.variant())
        }
    }

    @Test
    fun `client name is required`() {
        val input = ClientInput(type = ClientType.PERSONA, name = "  ")
        assertEquals("El nombre es obligatorio.", validateClient(input))
    }

    @Test
    fun `client with only name passes`() {
        val input = ClientInput(type = ClientType.EMPRESA, name = "Constructora Norte")
        assertNull(validateClient(input))
    }

    @Test
    fun `optional email must look like an address when present`() {
        val bad = ClientInput(type = ClientType.PERSONA, name = "Ana", email = "sin-arroba")
        val good = ClientInput(type = ClientType.PERSONA, name = "Ana", email = "ana@example.com")
        val empty = ClientInput(type = ClientType.PERSONA, name = "Ana", email = "")
        assertNotEquals(null, validateClient(bad))
        assertNull(validateClient(good))
        assertNull(validateClient(empty))
    }

    @Test
    fun `onboarding requires user and profile names`() {
        assertTrue(validateOnboarding("", "Perfil") != null)
        assertTrue(validateOnboarding("Joel", "  ") != null)
        assertNull(validateOnboarding("Joel", "Electricista Joel"))
    }

    // ---------- Matriz de Trabajo aprobada (F4, docs/03 §6) ----------

    @Test
    fun `job transition matrix matches the approved F4 decision`() {
        // BORRADOR → PROGRAMADO | EN_PROCESO | CANCELADO
        assertTrue(JobTransitions.canTransition(JobStatus.BORRADOR, JobStatus.PROGRAMADO))
        assertTrue(JobTransitions.canTransition(JobStatus.BORRADOR, JobStatus.EN_PROCESO))
        assertTrue(JobTransitions.canTransition(JobStatus.BORRADOR, JobStatus.CANCELADO))
        assertFalse(JobTransitions.canTransition(JobStatus.BORRADOR, JobStatus.ENTREGADO))
        // PROGRAMADO → BORRADOR | EN_PROCESO | CANCELADO
        assertTrue(JobTransitions.canTransition(JobStatus.PROGRAMADO, JobStatus.BORRADOR))
        assertTrue(JobTransitions.canTransition(JobStatus.PROGRAMADO, JobStatus.EN_PROCESO))
        assertTrue(JobTransitions.canTransition(JobStatus.PROGRAMADO, JobStatus.CANCELADO))
        assertFalse(JobTransitions.canTransition(JobStatus.PROGRAMADO, JobStatus.ENTREGADO))
        // EN_PROCESO → ENTREGADO | CANCELADO
        assertTrue(JobTransitions.canTransition(JobStatus.EN_PROCESO, JobStatus.ENTREGADO))
        assertTrue(JobTransitions.canTransition(JobStatus.EN_PROCESO, JobStatus.CANCELADO))
        assertFalse(JobTransitions.canTransition(JobStatus.EN_PROCESO, JobStatus.PROGRAMADO))
        assertFalse(JobTransitions.canTransition(JobStatus.EN_PROCESO, JobStatus.BORRADOR))
        // Terminales: sin salida
        JobStatus.entries.forEach { to ->
            assertFalse(JobTransitions.canTransition(JobStatus.ENTREGADO, to))
            assertFalse(JobTransitions.canTransition(JobStatus.CANCELADO, to))
        }
    }

    @Test
    fun `job terminals are not editable`() {
        assertTrue(JobTransitions.isEditable(JobStatus.BORRADOR))
        assertTrue(JobTransitions.isEditable(JobStatus.PROGRAMADO))
        assertTrue(JobTransitions.isEditable(JobStatus.EN_PROCESO))
        assertFalse(JobTransitions.isEditable(JobStatus.ENTREGADO))
        assertFalse(JobTransitions.isEditable(JobStatus.CANCELADO))
    }

    @Test
    fun `programado requires execution address`() {
        assertNotNull(
            JobTransitions.canTransitionWith(JobStatus.BORRADOR, JobStatus.PROGRAMADO, ""),
        )
        assertNull(
            JobTransitions.canTransitionWith(JobStatus.BORRADOR, JobStatus.PROGRAMADO, "Calle 1"),
        )
        // EN_PROCESO directo no la exige (la regla es solo al PROGRAMADO)
        assertNull(
            JobTransitions.canTransitionWith(JobStatus.BORRADOR, JobStatus.EN_PROCESO, ""),
        )
    }

    // ---------- Matriz de Informe aprobada (F5, docs/03 §6) ----------

    @Test
    fun `report transition matrix matches the approved F5 decision`() {
        // BORRADOR → EMITIDO (emisión)
        assertTrue(ReportTransitions.canTransition(ReportStatus.BORRADOR, ReportStatus.EMITIDO))
        // EMITIDO → BORRADOR (corrección: reabre el borrador; la re-emisión
        // crea una versión nueva enlazada)
        assertTrue(ReportTransitions.canTransition(ReportStatus.EMITIDO, ReportStatus.BORRADOR))
        // Mismo estado permitido (archivar/actualizar sin transición)
        assertTrue(ReportTransitions.canTransition(ReportStatus.BORRADOR, ReportStatus.BORRADOR))
        assertTrue(ReportTransitions.canTransition(ReportStatus.EMITIDO, ReportStatus.EMITIDO))
    }

    @Test
    fun `report is editable only in BORRADOR`() {
        assertTrue(ReportTransitions.isEditable(ReportStatus.BORRADOR))
        assertFalse(ReportTransitions.isEditable(ReportStatus.EMITIDO))
    }

    // ---------- Reglas de Cuenta de Cobro y Pago (F6, docs/03 §6) ----------

    @Test
    fun `collection account editable only when PENDIENTE without applied payments`() {
        val zero = "0.00".toBigDecimal()
        val some = "50.00".toBigDecimal()
        assertTrue(CollectionAccountTransitions.isEditable(CollectionAccountStatus.PENDIENTE, zero))
        assertFalse(CollectionAccountTransitions.isEditable(CollectionAccountStatus.PENDIENTE, some))
        assertFalse(CollectionAccountTransitions.isEditable(CollectionAccountStatus.PARCIAL, zero))
        assertFalse(CollectionAccountTransitions.isEditable(CollectionAccountStatus.PAGADA, zero))
        assertFalse(CollectionAccountTransitions.isEditable(CollectionAccountStatus.CANCELADA, zero))
    }

    @Test
    fun `collection account cancellable only without applied payments`() {
        val zero = "0.00".toBigDecimal()
        val some = "10.00".toBigDecimal()
        assertTrue(CollectionAccountTransitions.canCancel(CollectionAccountStatus.PENDIENTE, zero))
        assertFalse(CollectionAccountTransitions.canCancel(CollectionAccountStatus.PENDIENTE, some))
        assertFalse(CollectionAccountTransitions.canCancel(CollectionAccountStatus.PARCIAL, zero))
        assertFalse(CollectionAccountTransitions.canCancel(CollectionAccountStatus.CANCELADA, zero))
    }

    @Test
    fun `collection account receives payments only while alive`() {
        assertTrue(CollectionAccountTransitions.canReceivePayments(CollectionAccountStatus.PENDIENTE))
        assertTrue(CollectionAccountTransitions.canReceivePayments(CollectionAccountStatus.PARCIAL))
        assertFalse(CollectionAccountTransitions.canReceivePayments(CollectionAccountStatus.PAGADA))
        assertFalse(CollectionAccountTransitions.canReceivePayments(CollectionAccountStatus.CANCELADA))
    }

    @Test
    fun `collection status derives from applied vs net`() {
        val net = "100.00".toBigDecimal()
        assertEquals(
            CollectionAccountStatus.PENDIENTE,
            deriveCollectionStatus(false, "0.00".toBigDecimal(), net),
        )
        assertEquals(
            CollectionAccountStatus.PARCIAL,
            deriveCollectionStatus(false, "40.00".toBigDecimal(), net),
        )
        assertEquals(
            CollectionAccountStatus.PAGADA,
            deriveCollectionStatus(false, "100.00".toBigDecimal(), net),
        )
        // Cancelada es terminal: nunca deriva de importes.
        assertEquals(
            CollectionAccountStatus.CANCELADA,
            deriveCollectionStatus(true, "0.00".toBigDecimal(), net),
        )
        assertEquals(
            CollectionAccountStatus.CANCELADA,
            deriveCollectionStatus(true, "50.00".toBigDecimal(), net),
        )
    }

    @Test
    fun `payment assignment status derives from applied and unassigned`() {
        assertEquals(
            PaymentAssignmentStatus.UNASSIGNED,
            deriveAssignmentStatus("0.00".toBigDecimal(), "100.00".toBigDecimal()),
        )
        assertEquals(
            PaymentAssignmentStatus.PARTIAL_ASSIGNED,
            deriveAssignmentStatus("40.00".toBigDecimal(), "60.00".toBigDecimal()),
        )
        assertEquals(
            PaymentAssignmentStatus.ASSIGNED,
            deriveAssignmentStatus("100.00".toBigDecimal(), "0.00".toBigDecimal()),
        )
    }

    @Test
    fun `collection method requires name`() {
        assertNotNull(validateCollectionMethod("  "))
        assertNull(validateCollectionMethod("Transferencia"))
    }
}

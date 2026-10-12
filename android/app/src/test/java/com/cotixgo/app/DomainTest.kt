package com.cotixgo.app

import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.validateClient
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
}

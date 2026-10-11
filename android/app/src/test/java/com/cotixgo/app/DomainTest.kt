package com.cotixgo.app

import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.validateClient
import com.cotixgo.app.core.validateOnboarding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
}

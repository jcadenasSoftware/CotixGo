package com.cotixgo.app

import com.cotixgo.app.net.parseApiError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parseo del envelope de error del contrato F3: {"error":{"code","message"}}.
 * Cubre los casos visibles en la UI: correo duplicado al registrar y fallo
 * del endpoint de recuperación.
 */
class ApiErrorParseTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(http: Int, body: String) =
        parseApiError(http, json.parseToJsonElement(body).jsonObject)

    @Test
    fun duplicateEmail_returnsAccountExists() {
        val e = parse(409, """{"error":{"code":"ACCOUNT_EXISTS","message":"Ya existe una cuenta con ese correo."}}""")
        assertEquals("ACCOUNT_EXISTS", e.errorCode)
        assertEquals("Ya existe una cuenta con ese correo.", e.message)
    }

    @Test
    fun recoverFailure_returnsServerMessage() {
        val e = parse(500, """{"error":{"code":"INTERNAL_ERROR","message":"Fallo al registrar la recuperación."}}""")
        assertEquals("INTERNAL_ERROR", e.errorCode)
        assertEquals("Fallo al registrar la recuperación.", e.message)
    }

    @Test
    fun validationError_keepsCode() {
        val e = parse(400, """{"error":{"code":"VALIDATION_ERROR","message":"email inválido","details":{"field":"email"}}}""")
        assertEquals("VALIDATION_ERROR", e.errorCode)
        assertEquals("email inválido", e.message)
    }

    @Test
    fun bodyWithoutEnvelope_fallsBackToHttpCode() {
        val e = parse(503, """{"status":"down"}""")
        assertNull(e.errorCode)
        assertEquals("Error de API 503", e.message)
    }
}

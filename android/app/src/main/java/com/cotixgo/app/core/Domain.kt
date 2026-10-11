package com.cotixgo.app.core

import java.time.Instant
import java.util.UUID

/** IDs globales: UUIDv4 aleatorio generado en el dispositivo (decisión F1). */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

/** Marcas de tiempo: Instant UTC para creación, actualización y sincronización. */
fun nowEpochMillis(): Long = Instant.now().toEpochMilli()

enum class ClientType { PERSONA, EMPRESA }

data class ClientInput(
    val type: ClientType,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
)

/** Validación de campos mínimos aprobados: nombre obligatorio; correo opcional. */
fun validateClient(input: ClientInput): String? {
    if (input.name.isBlank()) return "El nombre es obligatorio."
    if (input.email.isNotBlank() && !input.email.contains('@')) return "El correo no es válido."
    return null
}

fun validateOnboarding(userName: String, profileName: String): String? {
    if (userName.isBlank()) return "El nombre de usuario es obligatorio."
    if (profileName.isBlank()) return "El nombre comercial del perfil es obligatorio."
    return null
}

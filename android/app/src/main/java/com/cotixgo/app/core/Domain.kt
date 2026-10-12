package com.cotixgo.app.core

import java.time.Instant
import java.util.UUID

/** IDs globales: UUIDv4 aleatorio generado en el dispositivo (decisión F1). */
object Ids {
    fun new(): String = UUID.randomUUID().toString()
}

/**
 * Marcas de tiempo: `Instant` UTC para creación, actualización y sincronización.
 *
 * Se almacenan en Room como `Long` epoch milliseconds (mismo instante, forma
 * compacta). En el límite de sincronización/API (F3) se serializarán como
 * ISO-8601 UTC mediante `Instant.ofEpochMilli(ms).toString()` — sin cambiar
 * el valor almacenado ni depender de la zona horaria del dispositivo.
 */
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

// ---------- Cotizaciones (F2) ----------

enum class QuoteStatus { BORRADOR, EMITIDA, APROBADA, RECHAZADA, VENCIDA, CANCELADA }
enum class ConceptType { SERVICIO, MATERIAL, OTRO }
enum class MaterialOrigin { CLIENTE, PROFESIONAL }

/**
 * Transiciones aprobadas para acciones del usuario. `VENCIDA` no figura como
 * destino: la produce el paso del tiempo (ver [effectiveQuoteStatus]) y se
 * sincroniza como materialización, aceptada por el servidor desde los mismos
 * estados no terminales. Editar no es una transición: conserva el estado y
 * crea versión interna.
 */
object QuoteTransitions {
    private val allowed: Map<QuoteStatus, Set<QuoteStatus>> = mapOf(
        QuoteStatus.BORRADOR to setOf(QuoteStatus.EMITIDA, QuoteStatus.CANCELADA),
        QuoteStatus.EMITIDA to setOf(QuoteStatus.APROBADA, QuoteStatus.RECHAZADA, QuoteStatus.CANCELADA),
        QuoteStatus.RECHAZADA to setOf(QuoteStatus.EMITIDA),
        QuoteStatus.APROBADA to emptySet(),
        QuoteStatus.VENCIDA to emptySet(),
        QuoteStatus.CANCELADA to emptySet(),
    )

    fun canTransition(from: QuoteStatus, to: QuoteStatus): Boolean =
        (allowed[from] ?: emptySet()).contains(to)

    fun isEditable(status: QuoteStatus): Boolean =
        status == QuoteStatus.BORRADOR || status == QuoteStatus.EMITIDA
}

/**
 * Estado efectivo: la vigencia corre desde la creación (incluso en BORRADOR)
 * y `fecha_vencimiento` es el último día válido — la Cotización pasa a
 * VENCIDA cuando la fecha local del dispositivo es posterior a ese día
 * (decisión DP2-F2, alcance aclarado en F3). Solo vencen los estados NO
 * terminales: BORRADOR, EMITIDA y RECHAZADA. APROBADA y CANCELADA son
 * terminales y conservan su estado (la aprobada puede originar Trabajos).
 * Se deriva al leer; se materializa en la siguiente escritura (sin trabajo
 * en segundo plano) y se sincroniza como transición →VENCIDA.
 */
fun effectiveQuoteStatus(
    status: QuoteStatus,
    expiryEpochDay: Long,
    todayEpochDay: Long,
): QuoteStatus =
    when (status) {
        QuoteStatus.BORRADOR, QuoteStatus.EMITIDA, QuoteStatus.RECHAZADA ->
            if (todayEpochDay > expiryEpochDay) QuoteStatus.VENCIDA else status
        else -> status
    }

/** `fecha_emision + vigencia - 1` = último día válido (el día de emisión cuenta). */
fun expiryEpochDay(issueEpochDay: Long, validityDays: Int): Long =
    issueEpochDay + validityDays - 1

private val PROVISIONAL_ALPHABET = ('A'..'Z') + ('0'..'9')
private val provisionalRandom = java.security.SecureRandom()

/** Sufijo aleatorio de 4 caracteres [A-Z0-9] para `TIPO-PEND-XXXX`. */
fun newProvisionalNumber(prefix: String): String {
    val suffix = (1..4).joinToString("") {
        PROVISIONAL_ALPHABET[provisionalRandom.nextInt(PROVISIONAL_ALPHABET.size)].toString()
    }
    return "$prefix-PEND-$suffix"
}

val PROVISIONAL_NUMBER_REGEX = Regex("^[A-Z]+-PEND-[A-Z0-9]{4}$")

/** Emitir exige al menos una línea; BORRADOR puede guardarse sin líneas. */
fun validateQuoteForEmit(lineCount: Int): String? =
    if (lineCount < 1) "La cotización necesita al menos una línea para emitirse." else null

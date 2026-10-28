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

/** Tipos de documento de identidad sugeridos por tipo de cliente; "Otro" siempre disponible. */
object ClientDocTypes {
    const val OTRO = "Otro"
    fun forType(type: ClientType): List<String> = when (type) {
        ClientType.PERSONA -> listOf("CC", "CE", "Pasaporte", OTRO)
        ClientType.EMPRESA -> listOf("NIT", OTRO)
    }
}

data class ClientInput(
    val type: ClientType,
    val name: String,
    val docType: String = "",
    val docNumber: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val notes: String = "",
)

/** Validación de campos mínimos: nombre y documento obligatorios; correo opcional. */
fun validateClient(input: ClientInput): String? {
    if (input.name.isBlank()) return "El nombre es obligatorio."
    if (input.docType.isBlank()) return "El tipo de documento es obligatorio."
    if (input.docNumber.isBlank()) return "El número de documento es obligatorio."
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

// ---------- Trabajos (F4) ----------

enum class JobStatus { BORRADOR, PROGRAMADO, EN_PROCESO, ENTREGADO, CANCELADO }
enum class JobActivityStatus { PENDIENTE, EN_PROCESO, COMPLETADA }

/**
 * Matriz aprobada (docs/03 §6): ENTREGADO y CANCELADO son terminales sin
 * edición ni transición posterior; no existe "Pausar". Editar no es una
 * transición: conserva el estado mientras no sea terminal.
 */
object JobTransitions {
    private val allowed: Map<JobStatus, Set<JobStatus>> = mapOf(
        JobStatus.BORRADOR to setOf(JobStatus.PROGRAMADO, JobStatus.EN_PROCESO, JobStatus.CANCELADO),
        JobStatus.PROGRAMADO to setOf(JobStatus.BORRADOR, JobStatus.EN_PROCESO, JobStatus.CANCELADO),
        JobStatus.EN_PROCESO to setOf(JobStatus.ENTREGADO, JobStatus.CANCELADO),
        JobStatus.ENTREGADO to emptySet(),
        JobStatus.CANCELADO to emptySet(),
    )

    fun canTransition(from: JobStatus, to: JobStatus): Boolean =
        (allowed[from] ?: emptySet()).contains(to)

    fun isEditable(status: JobStatus): Boolean =
        status != JobStatus.ENTREGADO && status != JobStatus.CANCELADO

    /** La dirección de ejecución es obligatoria al pasar a PROGRAMADO. */
    fun canTransitionWith(from: JobStatus, to: JobStatus, executionAddress: String): String? {
        if (!isEditable(from)) return "El trabajo está $from y no admite cambios."
        if (!canTransition(from, to)) return "Transición no permitida: $from → $to."
        if (to == JobStatus.PROGRAMADO && executionAddress.isBlank()) {
            return "La dirección de ejecución es obligatoria para programar el trabajo."
        }
        return null
    }
}

// ---------- Informes de Trabajo (F5) ----------

enum class ReportStatus { BORRADOR, EMITIDO }
enum class ReportDestination { INTERNO, CLIENTE }

/**
 * Estados aprobados del Informe (docs/03 §6): BORRADOR editable y EMITIDO
 * inmutable. Corregir un emitido reabre el borrador (EMITIDO→BORRADOR); al
 * re-emitirse se crea una versión nueva enlazada a la anterior conservando
 * el snapshot previo y el mismo número oficial.
 */
object ReportTransitions {
    private val allowed: Map<ReportStatus, Set<ReportStatus>> = mapOf(
        ReportStatus.BORRADOR to setOf(ReportStatus.EMITIDO),
        ReportStatus.EMITIDO to setOf(ReportStatus.BORRADOR),
    )

    fun canTransition(from: ReportStatus, to: ReportStatus): Boolean =
        from == to || (allowed[from] ?: emptySet()).contains(to)

    fun isEditable(status: ReportStatus): Boolean = status == ReportStatus.BORRADOR
}

// ---------- Cuentas de Cobro, Métodos de Cobro y Pagos (F6) ----------

/**
 * Estados aprobados de la Cuenta de Cobro (docs/03 §6): nace PENDIENTE;
 * PARCIAL y PAGADA se derivan de los pagos aplicados (nunca las envía el
 * usuario directamente) y CANCELADA solo se alcanza sin pagos aplicados.
 */
enum class CollectionAccountStatus { PENDIENTE, PARCIAL, PAGADA, CANCELADA }

/** Estado de asignación del Pago (docs/02 §24): todo, parte o nada aplicado. */
enum class PaymentAssignmentStatus { UNASSIGNED, PARTIAL_ASSIGNED, ASSIGNED }

/** Operaciones auditadas de asignación aprobadas para F6. */
enum class PaymentAssignmentAction { ASSIGN, UNASSIGN, REASSIGN }

/**
 * Estado derivado de la Cuenta: PARCIAL/PAGADA emergen del dinero aplicado
 * frente al neto; CANCELADA es terminal y nunca deriva de importes.
 */
fun deriveCollectionStatus(cancelled: Boolean, applied: java.math.BigDecimal, net: java.math.BigDecimal): CollectionAccountStatus =
    when {
        cancelled -> CollectionAccountStatus.CANCELADA
        applied.signum() <= 0 -> CollectionAccountStatus.PENDIENTE
        applied.compareTo(net) >= 0 -> CollectionAccountStatus.PAGADA
        else -> CollectionAccountStatus.PARCIAL
    }

/** Estado derivado del Pago según el remanente sin asignar. */
fun deriveAssignmentStatus(applied: java.math.BigDecimal, unassigned: java.math.BigDecimal): PaymentAssignmentStatus =
    when {
        applied.signum() <= 0 -> PaymentAssignmentStatus.UNASSIGNED
        unassigned.signum() <= 0 -> PaymentAssignmentStatus.ASSIGNED
        else -> PaymentAssignmentStatus.PARTIAL_ASSIGNED
    }

object CollectionAccountTransitions {
    /** Editable solo en PENDIENTE y sin pagos aplicados (docs/03 §6). */
    fun isEditable(status: CollectionAccountStatus, applied: java.math.BigDecimal): Boolean =
        status == CollectionAccountStatus.PENDIENTE && applied.signum() <= 0

    /** Cancelable solo desde PENDIENTE y sin pagos aplicados (docs/03 §6). */
    fun canCancel(status: CollectionAccountStatus, applied: java.math.BigDecimal): Boolean =
        status == CollectionAccountStatus.PENDIENTE && applied.signum() <= 0

    /** Puede recibir aplicaciones de pago (saldo vivo y no cancelada). */
    fun canReceivePayments(status: CollectionAccountStatus): Boolean =
        status == CollectionAccountStatus.PENDIENTE || status == CollectionAccountStatus.PARCIAL
}

/** Validación mínima de Método de Cobro: `name` obligatorio, `detail` opcional. */
fun validateCollectionMethod(name: String): String? =
    if (name.isBlank()) "El nombre del método de cobro es obligatorio." else null

// ---------- Consolidados, Ajustes y Reembolsos (F7) ----------

/**
 * Tipo de ajuste de Pago aprobado (docs/03 §6): corrección del importe
 * efectivo o anulación terminal de una sola vez. Eventos append-only.
 */
enum class PaymentAdjustmentKind { CORRECTION, ANNULMENT }

/**
 * Destino de una asignación (docs/03 §6): Cuenta individual o
 * Consolidado. En wire viaja minúsculo (`account`/`consolidated`).
 */
enum class PaymentTargetKind { ACCOUNT, CONSOLIDATED }

/**
 * El Consolidado comparte el ciclo de vida de la Cuenta (mismos cuatro
 * estados). Miembros editables y cancelación solo en PENDIENTE sin pagos
 * aplicados; PARCIAL/PAGADA los deriva el servidor.
 */
object ConsolidatedTransitions {
    fun isEditable(status: CollectionAccountStatus, applied: java.math.BigDecimal): Boolean =
        status == CollectionAccountStatus.PENDIENTE && applied.signum() <= 0

    fun canCancel(status: CollectionAccountStatus, applied: java.math.BigDecimal): Boolean =
        status == CollectionAccountStatus.PENDIENTE && applied.signum() <= 0

    fun canReceivePayments(status: CollectionAccountStatus): Boolean =
        status == CollectionAccountStatus.PENDIENTE || status == CollectionAccountStatus.PARCIAL
}

/**
 * Reserva compartida de asignaciones y Reembolsos (docs/03 §6):
 * `disponible = efectivo − aplicadoVigente − reembolsadoAcumulado`.
 * Invariante permanente: `aplicado + reembolsado ≤ efectivo`.
 */
fun availableForRefundOrAssign(
    effective: java.math.BigDecimal,
    applied: java.math.BigDecimal,
    refunded: java.math.BigDecimal,
): java.math.BigDecimal =
    effective.subtract(applied).subtract(refunded).max(java.math.BigDecimal.ZERO)

/** ¿El Pago admite asignaciones, correcciones o Reembolsos? (no anulado). */
fun paymentIsLive(annulled: Boolean): Boolean = !annulled

// ---------- Inventario y Compras (F8) ----------

/**
 * Estados aprobados de la Compra (docs/03 §6): nace BORRADOR sin
 * movimientos; CONFIRMADA registra entradas por línea y admite edición
 * por diferencia con trazabilidad; ANULADA es terminal e irreversible —
 * solo `archived` puede cambiar después. El archivado es acción separada
 * y nunca revierte movimientos.
 */
enum class PurchaseStatus { BORRADOR, CONFIRMADA, ANULADA }

object PurchaseTransitions {
    private val allowed: Map<PurchaseStatus, Set<PurchaseStatus>> = mapOf(
        PurchaseStatus.BORRADOR to setOf(PurchaseStatus.CONFIRMADA, PurchaseStatus.ANULADA),
        PurchaseStatus.CONFIRMADA to setOf(PurchaseStatus.ANULADA),
        PurchaseStatus.ANULADA to emptySet(),
    )

    fun canTransition(from: PurchaseStatus, to: PurchaseStatus): Boolean =
        from == to || (allowed[from] ?: emptySet()).contains(to)

    /** Editable en BORRADOR (líneas libres) y CONFIRMADA (edición diferencial). */
    fun isEditable(status: PurchaseStatus): Boolean = status != PurchaseStatus.ANULADA

    /** Confirmar exige al menos una línea (mínimo aprobado). */
    fun canConfirm(status: PurchaseStatus, lineCount: Int): Boolean =
        status == PurchaseStatus.BORRADOR && lineCount >= 1
}

/** Condición como dimensión del stock del mismo Material (no dos materiales). */
enum class MaterialCondition { NUEVO, USADO }

/** Tipos de movimiento de inventario (append-only): entradas y salidas. */
enum class MovementType { PURCHASE_IN, JOB_CONSUMPTION, ADJUSTMENT_IN, ADJUSTMENT_OUT }

/** Origen explicable del movimiento: compra, consumo en Trabajo, sobrante o ajuste manual. */
enum class MovementOrigin { COMPRA, TRABAJO, SOBRANTE, AJUSTE }

/** Catálogo global de Materiales: nombre y unidad requeridos (descripción opcional). */
fun validateMaterial(name: String, unit: String): String? = when {
    name.isBlank() -> "El material requiere un nombre."
    unit.isBlank() -> "El material requiere una unidad."
    else -> null
}

/** Los tipos IN suman al saldo proyectado; los OUT restan. */
fun movementIsInbound(type: MovementType): Boolean =
    type == MovementType.PURCHASE_IN || type == MovementType.ADJUSTMENT_IN

/** Cantidad decimal positiva para inventario (admite fracciones). */
fun isPositiveQuantity(q: String): Boolean =
    q.trim().matches(Regex("^\\d+(\\.\\d{1,6})?$")) &&
        runCatching { java.math.BigDecimal(q.trim()) }.getOrNull()
            ?.let { it.signum() > 0 } == true

/** Normaliza un decimal: sin ceros finales ni "-0". */
fun normDecimal(d: String): String =
    runCatching { java.math.BigDecimal(d.trim()).stripTrailingZeros().toPlainString() }
        .getOrDefault("0")

/** Códigos ISO 4217 vigentes (lista oficial). `java.util.Currency` en
 *  Android acepta cualquier código de 3 letras (ICU es permisivo), así que
 *  la fuente de verdad es este conjunto explícito. */
val ISO_4217_CODES: Set<String> = setOf(
    "AED","AFN","ALL","AMD","ANG","AOA","ARS","AUD","AWG","AZN",
    "BAM","BBD","BDT","BGN","BHD","BIF","BMD","BND","BOB","BOV","BRL","BSD","BTN","BWP","BYN","BZD",
    "CAD","CDF","CHE","CHF","CHW","CLF","CLP","CNY","COP","COU","CRC","CUP","CVE","CZK",
    "DJF","DKK","DOP","DZD",
    "EGP","ERN","ETB","EUR",
    "FJD","FKP",
    "GBP","GEL","GHS","GIP","GMD","GNF","GTQ","GYD",
    "HKD","HNL","HTG","HUF",
    "IDR","ILS","INR","IQD","IRR","ISK",
    "JMD","JOD","JPY",
    "KES","KGS","KHR","KMF","KPW","KRW","KWD","KYD","KZT",
    "LAK","LBP","LKR","LRD","LSL","LYD",
    "MAD","MDL","MGA","MKD","MMK","MNT","MOP","MRU","MUR","MVR","MWK","MXN","MXV","MYR","MZN",
    "NAD","NGN","NIO","NOK","NPR","NZD",
    "OMR",
    "PAB","PEN","PGK","PHP","PKR","PLN","PYG",
    "QAR",
    "RON","RSD","RUB","RWF",
    "SAR","SBD","SCR","SDG","SEK","SGD","SHP","SLE","SOS","SRD","SSP","STN","SVC","SYP","SZL",
    "THB","TJS","TMT","TND","TOP","TRY","TTD","TWD","TZS",
    "UAH","UGX","USD","USN","UYI","UYU","UYW","UZS",
    "VED","VES","VND","VUV",
    "WST",
    "XAF","XAG","XAU","XBA","XBB","XBC","XBD","XCD","XDR","XOF","XPD","XPF","XPT","XSU","XTS","XUA",
    "YER",
    "ZAR","ZMW","ZWG",
)

fun isValidCurrencyCode(code: String): Boolean =
    code.uppercase() in ISO_4217_CODES

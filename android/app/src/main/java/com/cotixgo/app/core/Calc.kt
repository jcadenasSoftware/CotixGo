package com.cotixgo.app.core

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Motor de cálculo compartido Cotización/Cuenta de Cobro (regla aprobada).
 *
 * Reglas V1:
 * - Precios ingresados como bruto por línea; sin descuentos ni impuestos.
 * - La base de cada retención es la suma de las líneas cuyo `conceptType`
 *   está en su `appliesTo` (SERVICIO sí por defecto, MATERIAL no, OTRO según
 *   configuración). Retenciones múltiples: cada una sobre su base completa,
 *   nunca en secuencia sobre el saldo retenido.
 * - Redondeo a 2 decimales por línea (HALF_UP); el total es la suma de las
 *   líneas ya redondeadas. El redondeo de cada retención ocurre en la etapa
 *   final del cálculo.
 * - Los importes son BigDecimal: nunca binario flotante.
 */

private val SCALE_2 = 2
private val ONE_HUNDRED = BigDecimal("100")

data class CalcLine(
    val conceptType: ConceptType,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
) {
    /** Subtotal de la línea: qty × precio, redondeado a 2 decimales. */
    val total: BigDecimal
        get() = quantity.multiply(unitPrice).setScale(SCALE_2, RoundingMode.HALF_UP)
}

data class RetentionSpec(
    val name: String,
    val ratePercent: BigDecimal,
    val appliesTo: Set<ConceptType>,
)

data class RetentionResult(
    val name: String,
    val ratePercent: BigDecimal,
    val appliesTo: Set<ConceptType>,
    val base: BigDecimal,
    val amount: BigDecimal,
)

data class CalcResult(
    val lineTotals: List<BigDecimal>,
    val gross: BigDecimal,
    val retentions: List<RetentionResult>,
    val totalRetained: BigDecimal,
    val net: BigDecimal,
)

object QuoteCalc {

    fun compute(lines: List<CalcLine>, retentions: List<RetentionSpec>): CalcResult {
        val lineTotals = lines.map { it.total }
        val gross = lineTotals.fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
            .setScale(SCALE_2, RoundingMode.HALF_UP)

        val results = retentions.map { spec ->
            val base = lineTotals.foldIndexed(BigDecimal.ZERO) { i, acc, v ->
                if (lines[i].conceptType in spec.appliesTo) acc.add(v) else acc
            }
            // Etapa final del cálculo: cada retención se redondea a 2 decimales.
            val amount = base.multiply(spec.ratePercent)
                .divide(ONE_HUNDRED, SCALE_2, RoundingMode.HALF_UP)
            RetentionResult(spec.name, spec.ratePercent, spec.appliesTo, base, amount)
        }

        val totalRetained = results.fold(BigDecimal.ZERO) { acc, r -> acc.add(r.amount) }
            .setScale(SCALE_2, RoundingMode.HALF_UP)
        val net = gross.subtract(totalRetained).setScale(SCALE_2, RoundingMode.HALF_UP)

        return CalcResult(lineTotals, gross, results, totalRetained, net)
    }

    /**
     * Cálculo inverso: bruto único necesario para obtener `targetNet` cuando el
     * importe queda íntegramente dentro de la base de las retenciones dadas
     * (caso de uso: "quiero un neto X por los servicios"). Devuelve null si la
     * suma de tasas es >= 100% (bruto infinito, configuración inválida).
     */
    fun requiredGrossForNet(targetNet: BigDecimal, rates: List<BigDecimal>): BigDecimal? {
        val totalRate = rates.fold(BigDecimal.ZERO, BigDecimal::add)
        if (totalRate >= ONE_HUNDRED) return null
        val divisor = BigDecimal.ONE.subtract(totalRate.divide(ONE_HUNDRED))
        var candidate = targetNet.divide(divisor, SCALE_2, RoundingMode.HALF_UP)
        // Bruto único: el menor bruto de 2 decimales cuyo neto calculado
        // (con el mismo redondeo de retenciones) alcanza el objetivo.
        repeat(100) {
            val retained = rates.fold(BigDecimal.ZERO) { acc, rate ->
                acc.add(candidate.multiply(rate).divide(ONE_HUNDRED, SCALE_2, RoundingMode.HALF_UP))
            }
            if (candidate.subtract(retained) >= targetNet) return candidate
            candidate = candidate.add(BigDecimal("0.01"))
        }
        return candidate
    }

    /** Formato de importe para UI: dos decimales fijos. */
    fun format(amount: BigDecimal): String =
        amount.setScale(SCALE_2, RoundingMode.HALF_UP).toPlainString()
}

fun String.toMoney(): BigDecimal =
    this.trim().ifBlank { "0" }.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        ?: BigDecimal.ZERO.setScale(SCALE_2)

fun String.toQty(): BigDecimal = this.trim().ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO

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

/** Línea tras el reparto inverso: mismo índice y cantidad, precio nuevo. */
data class GrossLineResult(
    val index: Int,
    val quantity: BigDecimal,
    val unitPrice: BigDecimal,
    val lineTotal: BigDecimal,
)

/** Resultado del reparto inverso neto→bruto verificado con [QuoteCalc.compute]. */
data class GrossDistribution(
    val lines: List<GrossLineResult>,
    val gross: BigDecimal,
    val totalRetained: BigDecimal,
    val net: BigDecimal,
    /** true si el neto resultante es exactamente el objetivo al centavo. */
    val exact: Boolean,
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

    private val CENT = BigDecimal("0.01")

    /**
     * Reparto inverso: nuevos precios brutos por línea para que el documento
     * cobre `targetNet` tras las retenciones dadas.
     *
     * - Solo se incrementan las líneas sujetas a alguna retención (unión de
     *   `appliesTo`); las no sujetas conservan cantidad y precio. Si ninguna
     *   línea es sujeta (p. ej. sin retenciones seleccionadas) el reparto se
     *   aplica a todas: es la única forma de alcanzar el objetivo.
     * - El bruto del pool sujeto se reparte en proporción al total original
     *   de cada línea, con el residual de centavos compensado en la última
     *   línea sujeta; los importes por línea cuadran al centavo con el
     *   bruto total.
     * - El resultado se verifica con [compute]; `exact` indica que el neto
     *   real iguala el objetivo al centavo (si no, queda el mejor bruto
     *   alcanzable y la UI lo advierte).
     * - Devuelve null cuando el objetivo no es alcanzable: sin líneas,
     *   tasa efectiva del pool sujeto >= 100 %, objetivo menor que el
     *   subtotal no sujeto o líneas sujetas sin importe.
     */
    fun distributeGrossForNet(
        lines: List<CalcLine>,
        retentions: List<RetentionSpec>,
        targetNet: BigDecimal,
    ): GrossDistribution? {
        if (lines.isEmpty() || targetNet.signum() <= 0) return null
        // Normaliza a 2 decimales: todas las comparaciones de neto son por
        // igualdad exacta y BigDecimal("810000") != BigDecimal("810000.00").
        val target = targetNet.setScale(SCALE_2, RoundingMode.HALF_UP)
        val origTotals = lines.map { it.total }
        var subjectIdx = lines.indices.filter { i ->
            retentions.any { lines[i].conceptType in it.appliesTo }
        }
        if (subjectIdx.isEmpty()) subjectIdx = lines.indices.toList()
        val subjectSet = subjectIdx.toSet()

        val nonSubjectTotal = lines.indices.filter { it !in subjectSet }
            .fold(BigDecimal.ZERO) { a, i -> a.add(origTotals[i]) }
            .setScale(SCALE_2, RoundingMode.HALF_UP)
        val subjectNet = target.subtract(nonSubjectTotal)
        if (subjectNet.signum() < 0) return null
        val subjectTotal = subjectIdx.fold(BigDecimal.ZERO) { a, i -> a.add(origTotals[i]) }
        if (subjectTotal.signum() <= 0) return null

        val lineRate = lines.map { l ->
            retentions.filter { l.conceptType in it.appliesTo }
                .fold(BigDecimal.ZERO) { a, r -> a.add(r.ratePercent) }
        }
        if (subjectIdx.any { lineRate[it] >= ONE_HUNDRED }) return null
        // Fracción del pool sujeto cubierta por cada retención (proporción
        // sobre los importes actuales; base esperada = share × bruto sujeto).
        val shareOf = retentions.map { r ->
            subjectIdx.filter { lines[it].conceptType in r.appliesTo }
                .fold(BigDecimal.ZERO) { a, i -> a.add(origTotals[i]) }
                .divide(subjectTotal, 10, RoundingMode.HALF_UP)
        }
        val divisor = BigDecimal.ONE.subtract(
            retentions.mapIndexed { ri, r ->
                r.ratePercent.multiply(shareOf[ri]).divide(ONE_HUNDRED)
            }.fold(BigDecimal.ZERO, BigDecimal::add),
        )
        if (divisor.signum() <= 0) return null

        fun netOf(subjectGross: BigDecimal): BigDecimal {
            val retained = retentions.mapIndexed { ri, r ->
                subjectGross.multiply(shareOf[ri]).multiply(r.ratePercent)
                    .divide(ONE_HUNDRED, SCALE_2, RoundingMode.HALF_UP)
            }.fold(BigDecimal.ZERO, BigDecimal::add)
            return nonSubjectTotal.add(subjectGross).subtract(retained)
        }

        // El menor bruto sujeto cuyo neto alcanza el objetivo; se prefiere
        // el que lo iguala exactamente (el redondeo de retenciones puede
        // hacer saltar el neto un centavo por encima).
        var exactGross: BigDecimal? = null
        var firstGe: BigDecimal? = null
        var g = subjectNet.divide(divisor, SCALE_2, RoundingMode.HALF_UP)
            .subtract(BigDecimal("0.03"))
        var steps = 0
        while (steps++ < 500 && exactGross == null) {
            val net = netOf(g)
            if (net == target) {
                exactGross = g
            } else {
                if (firstGe == null && net > target) firstGe = g
                g = g.add(CENT)
            }
        }
        val subjectGross = exactGross ?: firstGe ?: return null

        // Bruto por línea sujeta: neto objetivo proporcional al total
        // original, "engrosado" con su tasa agregada; la última línea
        // sujeta absorbe el residual para cuadrar la suma al centavo.
        val lastS = subjectIdx.last()
        val grossTarget = HashMap<Int, BigDecimal>()
        var grossAcc = BigDecimal.ZERO
        for (i in subjectIdx) {
            if (i == lastS) {
                grossTarget[i] = subjectGross.subtract(grossAcc)
            } else {
                val shareNet = subjectNet.multiply(origTotals[i])
                    .divide(subjectTotal, SCALE_2, RoundingMode.HALF_UP)
                val denom = BigDecimal.ONE.subtract(lineRate[i].divide(ONE_HUNDRED))
                val v = shareNet.divide(denom, SCALE_2, RoundingMode.HALF_UP)
                grossAcc = grossAcc.add(v)
                grossTarget[i] = v
            }
        }

        // De total objetivo a precio unitario de 2 decimales; el residual
        // de granularidad (qty × 0,01) se arrastra a la siguiente línea.
        val fit = HashMap<Int, Pair<BigDecimal, BigDecimal>>() // unitPrice → total
        var carry = BigDecimal.ZERO
        for (i in subjectIdx) {
            val needed = grossTarget[i]!!.add(carry)
            val (u, t) = fitUnitPrice(lines[i].quantity, needed)
            fit[i] = u to t
            carry = needed.subtract(t)
        }
        if (carry.signum() != 0) {
            for (i in subjectIdx) {
                val (u0, t0) = fit[i]!!
                val exact = exactUnitPrice(lines[i].quantity, t0.add(carry))
                if (exact != null) { fit[i] = exact to t0.add(carry); carry = BigDecimal.ZERO; break }
            }
        }

        fun buildWith(lastU: Pair<Int, BigDecimal>? = null): List<CalcLine> =
            lines.indices.map { i ->
                if (i !in subjectSet) lines[i] else {
                    val u = if (i == lastU?.first) lastU!!.second else fit[i]!!.first
                    CalcLine(lines[i].conceptType, lines[i].quantity, u)
                }
            }

        var calc = compute(buildWith(), retentions)
        if (calc.net != target) {
            // Reparación fina: mueve la última línea sujeta en centavos
            // hasta que el neto real cuadre con el objetivo (o lo mejor
            // posible dentro de la granularidad de su precio unitario).
            val last = lastS
            val t0 = fit[last]!!.second
            var bestCalc = calc
            var bestU = fit[last]!!.first
            var bestDiff = target.subtract(calc.net).abs()
            for (k in -60..60) {
                if (k == 0) continue
                val target2 = t0.add(CENT.multiply(BigDecimal(k)))
                if (target2.signum() < 0) continue
                val u = exactUnitPrice(lines[last].quantity, target2) ?: continue
                val c2 = compute(buildWith(last to u), retentions)
                val diff = target.subtract(c2.net).abs()
                if (diff < bestDiff) {
                    bestDiff = diff; bestU = u; bestCalc = c2
                    if (diff.signum() == 0) break
                }
            }
            fit[last] = bestU to bestCalc.lineTotals[last]
            calc = bestCalc
        }

        val results = lines.indices.map { i ->
            val f = fit[i]
            GrossLineResult(i, lines[i].quantity, f?.first ?: lines[i].unitPrice,
                f?.second ?: origTotals[i])
        }
        return GrossDistribution(results, calc.gross, calc.totalRetained, calc.net,
            exact = calc.net == target)
    }

    /** Precio unitario de 2 decimales con qty × u lo más cerca de `needed`. */
    private fun fitUnitPrice(qty: BigDecimal, needed: BigDecimal): Pair<BigDecimal, BigDecimal> {
        if (qty.signum() <= 0) {
            return BigDecimal.ZERO.setScale(SCALE_2) to BigDecimal.ZERO.setScale(SCALE_2)
        }
        val base = needed.divide(qty, SCALE_2, RoundingMode.HALF_UP)
        var bestU = base
        var bestTotal = qty.multiply(base).setScale(SCALE_2, RoundingMode.HALF_UP)
        var bestDiff = bestTotal.subtract(needed).abs()
        for (k in 1..60) {
            for (cand in listOf(base.subtract(CENT.multiply(BigDecimal(k))),
                base.add(CENT.multiply(BigDecimal(k))))) {
                if (cand.signum() < 0) continue
                val t = qty.multiply(cand).setScale(SCALE_2, RoundingMode.HALF_UP)
                val d = t.subtract(needed).abs()
                if (d.signum() == 0) return cand to t
                if (d < bestDiff) { bestU = cand; bestTotal = t; bestDiff = d }
            }
        }
        return bestU to bestTotal
    }

    /** u de 2 decimales con qty × u == `needed` exacto; null si no existe. */
    private fun exactUnitPrice(qty: BigDecimal, needed: BigDecimal): BigDecimal? {
        if (qty.signum() <= 0) {
            return if (needed.signum() == 0) BigDecimal.ZERO.setScale(SCALE_2) else null
        }
        val base = needed.divide(qty, SCALE_2, RoundingMode.HALF_UP)
        for (k in 0..60) {
            for (cand in listOf(base,
                base.subtract(CENT.multiply(BigDecimal(k))),
                base.add(CENT.multiply(BigDecimal(k))))) {
                if (cand.signum() < 0) continue
                if (qty.multiply(cand).setScale(SCALE_2, RoundingMode.HALF_UP) == needed) return cand
            }
        }
        return null
    }

    /** Formato de importe para UI: dos decimales fijos. */
    fun format(amount: BigDecimal): String =
        amount.setScale(SCALE_2, RoundingMode.HALF_UP).toPlainString()
}

fun String.toMoney(): BigDecimal =
    this.trim().ifBlank { "0" }.toBigDecimalOrNull()?.setScale(2, RoundingMode.HALF_UP)
        ?: BigDecimal.ZERO.setScale(SCALE_2)

fun String.toQty(): BigDecimal = this.trim().ifBlank { "0" }.toBigDecimalOrNull() ?: BigDecimal.ZERO

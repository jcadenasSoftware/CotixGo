package com.cotixgo.app

import com.cotixgo.app.core.CalcLine
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.RetentionSpec
import com.cotixgo.app.core.QuoteTransitions
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.effectiveQuoteStatus
import com.cotixgo.app.core.expiryEpochDay
import com.cotixgo.app.core.newProvisionalNumber
import com.cotixgo.app.core.validateQuoteForEmit
import com.cotixgo.app.core.PROVISIONAL_NUMBER_REGEX
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal

/**
 * Ejecuta los vectores compartidos de `shared/test-vectors/calc/` y
 * `shared/test-vectors/transitions/` (convención v0: mismos casos para
 * Android y el futuro backend PHP).
 */
class CalcVectorTest {

    private fun vectorsDir(): File {
        // El working dir de los tests JVM es <repo>/android/app.
        val candidates = listOf(
            File("../../shared/test-vectors"),
            File("../shared/test-vectors"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("No se encontró shared/test-vectors desde ${File(".").absolutePath}")
    }

    private fun calcVectors(): List<JsonObject> {
        val dir = File(vectorsDir(), "calc")
        return dir.listFiles { f -> f.extension == "json" }!!
            .sortedBy { it.name }
            .map { Json.parseToJsonElement(it.readText()).jsonObject }
    }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content
    private fun money(s: String): BigDecimal = BigDecimal(s).setScale(2)

    @Test
    fun `shared calc vectors all pass`() {
        val vectors = calcVectors()
        assertTrue("Debe haber vectores de cálculo", vectors.isNotEmpty())
        vectors.forEach { v ->
            val id = v.str("id")
            val input = v["input"]!!.jsonObject
            val expected = v["expected"]!!.jsonObject

            when (v.str("category")) {
                "calc" -> {
                    val lines = input["lines"]!!.jsonArray.map { l ->
                        val o = l.jsonObject
                        CalcLine(
                            conceptType = ConceptType.valueOf(o["concept_type"]!!.jsonPrimitive.content),
                            quantity = o["quantity"]!!.jsonPrimitive.content.toBigDecimal(),
                            unitPrice = o["unit_price"]!!.jsonPrimitive.content.toBigDecimal(),
                        )
                    }
                    val specs = input["retentions"]!!.jsonArray.map { r ->
                        val o = r.jsonObject
                        RetentionSpec(
                            name = o["name"]!!.jsonPrimitive.content,
                            ratePercent = o["rate_percent"]!!.jsonPrimitive.content.toBigDecimal(),
                            appliesTo = o["applies_to"]!!.jsonArray
                                .map { ConceptType.valueOf(it.jsonPrimitive.content) }.toSet(),
                        )
                    }
                    val result = QuoteCalc.compute(lines, specs)
                    assertEquals("$id: bruto", money(expected["gross"]!!.jsonPrimitive.content), result.gross)
                    expected["line_totals"]?.jsonArray?.forEachIndexed { i, lt ->
                        assertEquals("$id: total línea $i", money(lt.jsonPrimitive.content), result.lineTotals[i])
                    }
                    val expRets = expected["retentions"]!!.jsonArray
                    assertEquals("$id: nº retenciones", expRets.size, result.retentions.size)
                    expRets.forEachIndexed { i, er ->
                        val eo = er.jsonObject
                        val actual = result.retentions[i]
                        assertEquals("$id: base retención ${eo["name"]!!.jsonPrimitive.content}",
                            money(eo["base"]!!.jsonPrimitive.content), actual.base)
                        assertEquals("$id: importe retención ${eo["name"]!!.jsonPrimitive.content}",
                            money(eo["amount"]!!.jsonPrimitive.content), actual.amount)
                    }
                    assertEquals("$id: total retenido", money(expected["total_retained"]!!.jsonPrimitive.content), result.totalRetained)
                    assertEquals("$id: neto", money(expected["net"]!!.jsonPrimitive.content), result.net)
                }
                "calc-inverse" -> {
                    val rates = input["service_rates"]!!.jsonArray
                        .map { it.jsonPrimitive.content.toBigDecimal() }
                    val gross = QuoteCalc.requiredGrossForNet(
                        input["target_net"]!!.jsonPrimitive.content.toBigDecimal(), rates)
                    assertNotNull("$id: bruto requerido", gross)
                    assertEquals("$id: bruto requerido",
                        money(expected["required_gross"]!!.jsonPrimitive.content), gross)
                    // Coherencia: recalcular el neto del bruto devuelto alcanza el objetivo.
                    val check = QuoteCalc.compute(
                        listOf(CalcLine(ConceptType.SERVICIO, BigDecimal.ONE, gross!!)),
                        rates.map { RetentionSpec("r", it, setOf(ConceptType.SERVICIO)) },
                    )
                    assertTrue("$id: neto del bruto alcanza el objetivo",
                        check.net >= input["target_net"]!!.jsonPrimitive.content.toBigDecimal())
                }
                else -> error("$id: categoría desconocida")
            }
        }
    }

    @Test
    fun `approved example reproduces exactly`() {
        val result = QuoteCalc.compute(
            listOf(
                CalcLine(ConceptType.MATERIAL, BigDecimal.ONE, BigDecimal("800000")),
                CalcLine(ConceptType.SERVICIO, BigDecimal.ONE, BigDecimal("600000")),
            ),
            listOf(RetentionSpec("Ret", BigDecimal("6"), setOf(ConceptType.SERVICIO))),
        )
        assertEquals(BigDecimal("1400000.00"), result.gross)
        assertEquals(BigDecimal("36000.00"), result.retentions[0].amount)
        assertEquals(BigDecimal("1364000.00"), result.net)
    }

    @Test
    fun `inverse returns null at 100 percent or more`() {
        assertNull(QuoteCalc.requiredGrossForNet(BigDecimal("1000"), listOf(BigDecimal("100"))))
        assertNull(QuoteCalc.requiredGrossForNet(BigDecimal("1000"), listOf(BigDecimal("60"), BigDecimal("50"))))
    }

    @Test
    fun `quote transitions shared vector passes`() {
        val file = File(vectorsDir(), "transitions/quote-status.json")
        val v = Json.parseToJsonElement(file.readText()).jsonObject
        v["valid"]!!.jsonArray.forEach { t ->
            val pair = t.jsonArray
            val from = QuoteStatus.valueOf(pair[0].jsonPrimitive.content)
            val to = QuoteStatus.valueOf(pair[1].jsonPrimitive.content)
            assertTrue("$from → $to debe ser válida", QuoteTransitions.canTransition(from, to))
        }
        v["invalid"]!!.jsonArray.forEach { t ->
            val pair = t.jsonArray
            val from = QuoteStatus.valueOf(pair[0].jsonPrimitive.content)
            val to = QuoteStatus.valueOf(pair[1].jsonPrimitive.content)
            assertFalse("$from → $to debe ser inválida", QuoteTransitions.canTransition(from, to))
        }
    }
}

class QuoteDomainTest {

    @Test
    fun `vigencia corre desde creacion y vence despues del ultimo dia valido`() {
        val issueDay = 20000L
        val expiry = expiryEpochDay(issueDay, 30) // último día válido
        assertEquals(issueDay + 29, expiry)
        // El último día válido aún no está vencida (regla DP2-F2).
        assertEquals(QuoteStatus.EMITIDA,
            effectiveQuoteStatus(QuoteStatus.EMITIDA, expiry, expiry))
        assertEquals(QuoteStatus.VENCIDA,
            effectiveQuoteStatus(QuoteStatus.EMITIDA, expiry, expiry + 1))
        // BORRADOR también vence (vigencia desde la creación).
        assertEquals(QuoteStatus.VENCIDA,
            effectiveQuoteStatus(QuoteStatus.BORRADOR, expiry, expiry + 1))
        assertEquals(QuoteStatus.BORRADOR,
            effectiveQuoteStatus(QuoteStatus.BORRADOR, expiry, expiry))
        // RECHAZADA también deriva a VENCIDA; las terminales no cambian.
        assertEquals(QuoteStatus.VENCIDA,
            effectiveQuoteStatus(QuoteStatus.RECHAZADA, expiry, expiry + 1))
        assertEquals(QuoteStatus.APROBADA,
            effectiveQuoteStatus(QuoteStatus.APROBADA, expiry, expiry + 1))
        assertEquals(QuoteStatus.CANCELADA,
            effectiveQuoteStatus(QuoteStatus.CANCELADA, expiry, expiry + 1))
    }

    @Test
    fun `provisional number matches COT-PEND-XXXX and is diverse`() {
        val numbers = (1..200).map { newProvisionalNumber("COT") }.toSet()
        assertEquals(200, numbers.size)
        numbers.forEach {
            assertTrue(it, it.matches(PROVISIONAL_NUMBER_REGEX))
            assertTrue(it.startsWith("COT-PEND-"))
            assertEquals(13, it.length)
        }
    }

    @Test
    fun `emit requires at least one line`() {
        assertNull(validateQuoteForEmit(3))
        assertEquals("La cotización necesita al menos una línea para emitirse.",
            validateQuoteForEmit(0))
    }

    @Test
    fun `editable statuses are BORRADOR and EMITIDA`() {
        assertTrue(QuoteTransitions.isEditable(QuoteStatus.BORRADOR))
        assertTrue(QuoteTransitions.isEditable(QuoteStatus.EMITIDA))
        assertFalse(QuoteTransitions.isEditable(QuoteStatus.RECHAZADA))
        assertFalse(QuoteTransitions.isEditable(QuoteStatus.APROBADA))
        assertFalse(QuoteTransitions.isEditable(QuoteStatus.VENCIDA))
        assertFalse(QuoteTransitions.isEditable(QuoteStatus.CANCELADA))
    }

    @Test
    fun `money and qty parsers behave`() {
        assertEquals(BigDecimal("12.35"), "12.345".toMoney()) // HALF_UP
        assertEquals(BigDecimal("0.00"), "".toMoney())
        assertEquals(BigDecimal("0.00"), "abc".toMoney())
        assertEquals(BigDecimal("1.5"), "1.5".toQty())
    }
}

/**
 * Reparto inverso neto→bruto entre líneas sujetas (Calc.distributeGrossForNet):
 * el caso de aceptación acordado (dos servicios de $450.000 y $360.000,
 * retención 6 %, neto objetivo $810.000 → bruto $861.702,13 repartido
 * $478.723,40 + $382.978,73 con el centavo residual en la última línea).
 */
class GrossDistributionTest {

    private fun line(type: ConceptType, qty: String, price: String) =
        CalcLine(type, BigDecimal(qty), BigDecimal(price))

    private fun rebuild(
        orig: List<CalcLine>,
        dist: com.cotixgo.app.core.GrossDistribution,
    ): List<CalcLine> = dist.lines.map {
        CalcLine(orig[it.index].conceptType, it.quantity, it.unitPrice)
    }

    @Test
    fun `acceptance case 6pct two service lines`() {
        val orig = listOf(
            line(ConceptType.SERVICIO, "1", "450000"),
            line(ConceptType.SERVICIO, "1", "360000"),
        )
        val rets = listOf(RetentionSpec("Ret 6%", BigDecimal("6"), setOf(ConceptType.SERVICIO)))
        val d = QuoteCalc.distributeGrossForNet(orig, rets, BigDecimal("810000"))!!

        assertTrue(d.exact)
        assertEquals(BigDecimal("861702.13"), d.gross)
        assertEquals(BigDecimal("51702.13"), d.totalRetained)
        assertEquals(BigDecimal("810000.00"), d.net)
        // Reparto proporcional con redondeo compensado en la última línea.
        assertEquals(BigDecimal("478723.40"), d.lines[0].lineTotal)
        assertEquals(BigDecimal("382978.73"), d.lines[1].lineTotal)
        assertEquals(d.gross, d.lines.fold(BigDecimal.ZERO) { a, l -> a.add(l.lineTotal) })
        // Coherencia con el motor: las líneas resultantes reproducen totales.
        val check = QuoteCalc.compute(rebuild(orig, d), rets)
        assertEquals(BigDecimal("861702.13"), check.gross)
        assertEquals(BigDecimal("51702.13"), check.totalRetained)
        assertEquals(BigDecimal("810000.00"), check.net)
    }

    @Test
    fun `non subject lines keep their amounts`() {
        val orig = listOf(
            line(ConceptType.MATERIAL, "1", "100000"),
            line(ConceptType.SERVICIO, "1", "450000"),
            line(ConceptType.SERVICIO, "1", "360000"),
        )
        val rets = listOf(RetentionSpec("Ret 6%", BigDecimal("6"), setOf(ConceptType.SERVICIO)))
        val d = QuoteCalc.distributeGrossForNet(orig, rets, BigDecimal("810000"))!!

        // La línea MATERIAL no es sujeta: conserva precio y total.
        assertEquals(BigDecimal("100000"), d.lines[0].unitPrice)
        assertEquals(BigDecimal("100000.00"), d.lines[0].lineTotal)
        // El pool sujeto aporta neto 710.000 → bruto sujeto 755.319,15.
        assertEquals(BigDecimal("419621.74"), d.lines[1].lineTotal)
        assertEquals(BigDecimal("335697.41"), d.lines[2].lineTotal)
        assertEquals(BigDecimal("855319.15"), d.gross)
        assertTrue(d.exact)
        val check = QuoteCalc.compute(rebuild(orig, d), rets)
        assertEquals(d.gross, check.gross)
        assertEquals(BigDecimal("810000.00"), check.net)
    }

    @Test
    fun `no retentions scales all lines proportionally`() {
        val orig = listOf(
            line(ConceptType.SERVICIO, "1", "450000"),
            line(ConceptType.MATERIAL, "2", "90000"),
        )
        val d = QuoteCalc.distributeGrossForNet(orig, emptyList(), BigDecimal("630000"))!!
        assertTrue(d.exact)
        assertEquals(BigDecimal("630000.00"), d.gross)
        assertEquals(BigDecimal("630000.00"), d.net)
        val check = QuoteCalc.compute(rebuild(orig, d), emptyList())
        assertEquals(BigDecimal("630000.00"), check.net)
    }

    @Test
    fun `target below non subject total returns null`() {
        val orig = listOf(
            line(ConceptType.MATERIAL, "1", "900000"),
            line(ConceptType.SERVICIO, "1", "100000"),
        )
        val rets = listOf(RetentionSpec("Ret 6%", BigDecimal("6"), setOf(ConceptType.SERVICIO)))
        // Las no sujetas ya suman 900.000: no se puede bajar de ahí.
        assertNull(QuoteCalc.distributeGrossForNet(orig, rets, BigDecimal("800000")))
    }

    @Test
    fun `aggregate rate at 100 percent returns null`() {
        val orig = listOf(line(ConceptType.SERVICIO, "1", "100000"))
        val rets = listOf(
            RetentionSpec("A", BigDecimal("60"), setOf(ConceptType.SERVICIO)),
            RetentionSpec("B", BigDecimal("45"), setOf(ConceptType.SERVICIO)),
        )
        assertNull(QuoteCalc.distributeGrossForNet(orig, rets, BigDecimal("1000")))
    }

    @Test
    fun `multi line totals keep exact document sums`() {
        // Tres líneas sujetas: la suma de importes cuadra con el bruto
        // y el neto no baja del objetivo aunque el reparto se redondee.
        val orig = listOf(
            line(ConceptType.SERVICIO, "1", "333333.33"),
            line(ConceptType.SERVICIO, "1", "333333.33"),
            line(ConceptType.SERVICIO, "1", "333333.34"),
        )
        val rets = listOf(RetentionSpec("Ret 6%", BigDecimal("6"), setOf(ConceptType.SERVICIO)))
        val d = QuoteCalc.distributeGrossForNet(orig, rets, BigDecimal("777777.77"))!!
        assertEquals(d.gross, d.lines.fold(BigDecimal.ZERO) { a, l -> a.add(l.lineTotal) })
        val check = QuoteCalc.compute(rebuild(orig, d), rets)
        assertEquals(check.gross, check.lineTotals.fold(BigDecimal.ZERO) { a, t -> a.add(t) })
        assertTrue(check.net >= BigDecimal("777777.77"))
    }
}

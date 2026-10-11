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

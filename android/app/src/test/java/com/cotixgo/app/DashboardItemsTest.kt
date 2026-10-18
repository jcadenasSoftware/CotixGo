package com.cotixgo.app

import com.cotixgo.app.core.DASHBOARD_SECTION_LIMIT
import com.cotixgo.app.core.dashboardItems
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.ReportEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Filtros del resumen accionable del Dashboard (docs/04 §2.1): estados
 * canónicos, exclusión de archivadas, orden y límite por sección.
 */
class DashboardItemsTest {

    private val today = 20742L // 2026-10-18

    private fun quote(
        id: String, status: String, expiry: Long = today + 10,
        archived: Boolean = false, userId: String = "u1",
    ) = QuoteEntity(
        id = id, userId = userId, clientId = "c1", profileId = "p1",
        provisionalNumber = "COT-PEND-$id", status = status,
        issueDateEpochDay = today - 30, validityDays = 30, expiryDateEpochDay = expiry,
        currency = "COP", archived = archived, createdAt = 1, updatedAt = 1,
    )

    private fun job(
        id: String, status: String, start: Long? = null,
        archived: Boolean = false,
    ) = JobEntity(
        id = id, userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "TR-PEND-$id", status = status,
        startDateEpochDay = start, archived = archived,
        createdAt = 1, updatedAt = 1,
    )

    private fun report(
        id: String, status: String, updated: Long = 1,
        archived: Boolean = false,
    ) = ReportEntity(
        id = id, userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "INF-PEND-$id", status = status,
        title = "Informe $id", destination = "CLIENTE",
        archived = archived, createdAt = 1, updatedAt = updated,
    )

    private fun account(
        id: String, status: String, due: Long? = null,
        archived: Boolean = false,
    ) = CollectionAccountEntity(
        id = id, userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "CC-PEND-$id", status = status,
        currency = "COP", net = "100.00", balance = "100.00",
        issuedAt = 1, dueDateEpochDay = due, archived = archived,
        createdAt = 1, updatedAt = 1,
    )

    private fun items(
        quotes: List<QuoteEntity> = emptyList(),
        jobs: List<JobEntity> = emptyList(),
        reports: List<ReportEntity> = emptyList(),
        accounts: List<CollectionAccountEntity> = emptyList(),
    ) = dashboardItems(quotes, jobs, reports, accounts, today)

    // ---------- Cotizaciones: próximas a vencer + vencidas ----------

    @Test
    fun cotizaciones_emitidaYVencida_aparecen_otrosEstadosNo() {
        val qs = listOf(
            quote("A", "EMITIDA", expiry = today + 5),   // próxima a vencer
            quote("B", "EMITIDA", expiry = today - 1),   // vencida efectiva
            quote("C", "BORRADOR", expiry = today + 5),  // borrador vigente: fuera
            quote("D", "BORRADOR", expiry = today - 1),  // borrador vencido: atención
            quote("E", "APROBADA"),
            quote("F", "RECHAZADA", expiry = today + 5),
            quote("G", "RECHAZADA", expiry = today - 1), // rechazada vencida → VENCIDA efectiva
            quote("H", "CANCELADA"),
            quote("I", "VENCIDA", expiry = today - 2),   // vencida materializada
        )
        // El estado efectivo gobierna (igual que en la lista de cotizaciones):
        // G e I son VENCIDA efectiva y aparecen; F sigue RECHAZADA y queda fuera.
        assertEquals(listOf("I", "B", "D", "G", "A"), items(quotes = qs).attentionQuotes.map { it.id })
    }

    @Test
    fun cotizaciones_archivadas_excluidas_aunqueRequieranAtencion() {
        val qs = listOf(quote("A", "EMITIDA", archived = true), quote("B", "VENCIDA", archived = true))
        assertTrue(items(quotes = qs).attentionQuotes.isEmpty())
    }

    @Test
    fun cotizaciones_limite_porVencimientoMasProximo() {
        val qs = (1..8).map { quote("Q$it", "EMITIDA", expiry = today + it) }
        val got = items(quotes = qs).attentionQuotes
        assertEquals(DASHBOARD_SECTION_LIMIT, got.size)
        assertEquals(today + 1, got.first().expiryDateEpochDay)
    }

    // ---------- Trabajos: PROGRAMADO y EN_PROCESO ----------

    @Test
    fun trabajos_programadoYEnProceso_otrosEstadosFuera() {
        val js = listOf(
            job("A", "PROGRAMADO", start = today + 3),
            job("B", "EN_PROCESO"),
            job("C", "BORRADOR"),
            job("D", "ENTREGADO"),
            job("E", "CANCELADO"),
        )
        assertEquals(listOf("B", "A"), items(jobs = js).activeJobs.map { it.id })
    }

    @Test
    fun trabajos_enProcesoPrimero_luegoPorFechaYArchivadosFuera() {
        val js = listOf(
            job("P1", "PROGRAMADO", start = today + 10),
            job("P2", "PROGRAMADO", start = today + 1),
            job("P3", "PROGRAMADO", start = null),
            job("E1", "EN_PROCESO"),
            job("P4", "PROGRAMADO", archived = true),
        )
        assertEquals(listOf("E1", "P2", "P1", "P3"), items(jobs = js).activeJobs.map { it.id })
    }

    // ---------- Informes: BORRADOR ----------

    @Test
    fun informes_soloBorrador_masRecientePrimero() {
        val rs = listOf(
            report("A", "BORRADOR", updated = 100),
            report("B", "EMITIDO", updated = 300),
            report("C", "BORRADOR", updated = 200),
            report("D", "BORRADOR", updated = 400, archived = true),
        )
        assertEquals(listOf("C", "A"), items(reports = rs).draftReports.map { it.id })
    }

    // ---------- Cuentas: PENDIENTE y PARCIAL ----------

    @Test
    fun cuentas_pendienteYParcial_otrosEstadosFuera() {
        val acs = listOf(
            account("A", "PENDIENTE", due = today + 7),
            account("B", "PARCIAL", due = today + 2),
            account("C", "PAGADA"),
            account("D", "CANCELADA"),
            account("E", "PARCIAL", due = today - 1), // vencida: primero
            account("F", "PARCIAL", archived = true),
        )
        assertEquals(listOf("E", "B", "A"), items(accounts = acs).openAccounts.map { it.id })
    }

    @Test
    fun cuentas_sinVencimiento_alFinal() {
        val acs = listOf(
            account("A", "PENDIENTE", due = null),
            account("B", "PENDIENTE", due = today + 1),
        )
        assertEquals(listOf("B", "A"), items(accounts = acs).openAccounts.map { it.id })
    }

    // ---------- Vacío y cambio de cuenta ----------

    @Test
    fun vacio_todasLasSecciones() {
        assertTrue(items().isEmpty)
        assertTrue(
            items(
                quotes = listOf(quote("A", "APROBADA")),
                jobs = listOf(job("J", "ENTREGADO")),
                reports = listOf(report("R", "EMITIDO")),
                accounts = listOf(account("K", "PAGADA")),
            ).isEmpty,
        )
    }

    @Test
    fun cambioDeCuenta_losItemsVienenDeLasListasActuales_sinResiduo() {
        val accountA = items(
            quotes = listOf(quote("A", "EMITIDA", userId = "u1")),
            accounts = listOf(account("K", "PENDIENTE")),
        )
        // La segunda emisión llega ya acotada por usuario (los flows son
        // flatMapLatest sobre `user`): con listas de u2 no queda nada de u1.
        val accountB = items(
            quotes = listOf(quote("B", "EMITIDA", userId = "u2")),
        )
        assertTrue(accountB.attentionQuotes.all { it.userId == "u2" })
        assertTrue(accountB.openAccounts.isEmpty())
        assertEquals(listOf("A"), accountA.attentionQuotes.map { it.id })
    }
}

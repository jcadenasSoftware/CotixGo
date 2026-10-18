package com.cotixgo.app.core

import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.ReportEntity

/**
 * Resumen accionable del Dashboard (docs/04 §2.1, docs/03 §2.8, docs/01 §18):
 * vista operacional sobre las entidades existentes — "qué requiere atención
 * hoy" — sin gráficos ni analítica financiera.
 *
 * Criterios aprobados por sección (estados canónicos del dominio, sin
 * inventar criterios ni umbrales nuevos; las archivadas nunca aparecen):
 * - Cotizaciones que requieren atención: EMITIDA efectiva (próximas a
 *   vencer) y VENCIDA efectiva — decisión del titular de incluir las
 *   vencidas. El estado efectivo gobierna como en la lista de
 *   cotizaciones: un BORRADOR o RECHAZADA cuya vigencia expiró aparece
 *   como VENCIDA (mismo criterio que QuoteListScreen); APROBADA,
 *   CANCELADA y RECHAZADA vigente quedan fuera.
 * - Trabajos activos: PROGRAMADO y EN_PROCESO (los estados operativos del
 *   ciclo; BORRADOR no es aún compromiso agenda­ble y los terminales
 *   ENTREGADO/CANCELADO cierran la operación).
 * - Informes pendientes: BORRADOR (EMITIDO cierra el ciclo del documento).
 * - Cuentas por cobrar: PENDIENTE y PARCIAL con su saldo (PAGADA/CANCELADA
 *   ya no requieren gestión).
 *
 * El límite por sección coincide con el de cotizaciones preexistente (5).
 */
const val DASHBOARD_SECTION_LIMIT = 5

data class DashboardItems(
    val attentionQuotes: List<QuoteEntity>,
    val activeJobs: List<JobEntity>,
    val draftReports: List<ReportEntity>,
    val openAccounts: List<CollectionAccountEntity>,
) {
    val isEmpty: Boolean
        get() = attentionQuotes.isEmpty() && activeJobs.isEmpty() &&
            draftReports.isEmpty() && openAccounts.isEmpty()
}

fun dashboardItems(
    quotes: List<QuoteEntity>,
    jobs: List<JobEntity>,
    reports: List<ReportEntity>,
    accounts: List<CollectionAccountEntity>,
    todayEpochDay: Long,
): DashboardItems = DashboardItems(
    attentionQuotes = quotes
        .asSequence()
        .filter { !it.archived }
        .filter {
            effectiveQuoteStatus(
                QuoteStatus.valueOf(it.status), it.expiryDateEpochDay, todayEpochDay,
            ).let { s -> s == QuoteStatus.EMITIDA || s == QuoteStatus.VENCIDA }
        }
        .sortedBy { it.expiryDateEpochDay }
        .take(DASHBOARD_SECTION_LIMIT)
        .toList(),
    activeJobs = jobs
        .asSequence()
        .filter { !it.archived }
        .filter {
            val s = JobStatus.valueOf(it.status)
            s == JobStatus.PROGRAMADO || s == JobStatus.EN_PROCESO
        }
        .sortedWith(
            compareBy(
                { if (it.status == JobStatus.EN_PROCESO.name) 0 else 1 },
                { it.startDateEpochDay ?: Long.MAX_VALUE },
                { it.provisionalNumber },
            ),
        )
        .take(DASHBOARD_SECTION_LIMIT)
        .toList(),
    draftReports = reports
        .asSequence()
        .filter { !it.archived && it.status == ReportStatus.BORRADOR.name }
        .sortedByDescending { it.updatedAt }
        .take(DASHBOARD_SECTION_LIMIT)
        .toList(),
    openAccounts = accounts
        .asSequence()
        .filter { !it.archived }
        .filter {
            val s = CollectionAccountStatus.valueOf(it.status)
            s == CollectionAccountStatus.PENDIENTE || s == CollectionAccountStatus.PARCIAL
        }
        .sortedWith(
            compareBy(
                { it.dueDateEpochDay ?: Long.MAX_VALUE },
                { it.provisionalNumber },
            ),
        )
        .take(DASHBOARD_SECTION_LIMIT)
        .toList(),
)

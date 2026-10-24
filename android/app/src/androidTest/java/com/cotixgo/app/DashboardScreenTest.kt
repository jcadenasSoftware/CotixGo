package com.cotixgo.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.dashboardItems
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.ReportEntity
import com.cotixgo.app.ui.DashboardScreen
import com.cotixgo.app.ui.dashboardDayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI instrumentada del Dashboard (docs/04 §2.1): acciones primarias,
 * resúmenes accionables con navegación a detalle, indicador de atención
 * de sincronización y estados de carga/vacío.
 */
@RunWith(AndroidJUnit4::class)
class DashboardScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = 20742L

    private val emittedQuote = QuoteEntity(
        id = "q1", userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "COT-PEND-0001", status = "EMITIDA",
        issueDateEpochDay = today - 30, validityDays = 40, expiryDateEpochDay = today + 5,
        currency = "COP", clientNameSnapshot = "Cliente Demo", createdAt = 1, updatedAt = 1,
    )
    private val expiredQuote = emittedQuote.copy(
        id = "q2", provisionalNumber = "COT-PEND-0002", expiryDateEpochDay = today - 1,
    )
    private val processJob = JobEntity(
        id = "j1", userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "TR-PEND-0001", status = "EN_PROCESO",
        clientNameSnapshot = "Cliente Demo", createdAt = 1, updatedAt = 1,
    )
    private val draftReport = ReportEntity(
        id = "r1", userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "INF-PEND-0001", status = "BORRADOR",
        title = "Informe borrador", destination = "CLIENTE",
        clientNameSnapshot = "Cliente Demo", createdAt = 1, updatedAt = 1,
    )
    private val partialAccount = CollectionAccountEntity(
        id = "a1", userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "CC-PEND-0001", status = "PARCIAL",
        currency = "COP", net = "100.00", balance = "40.00",
        issuedAt = 1, clientNameSnapshot = "Cliente Demo", createdAt = 1, updatedAt = 1,
    )

    private fun screen(
        loading: Boolean = false,
        quotes: List<QuoteEntity> = emptyList(),
        jobs: List<JobEntity> = emptyList(),
        reports: List<ReportEntity> = emptyList(),
        accounts: List<CollectionAccountEntity> = emptyList(),
        syncPending: Int = 0,
        syncPaused: Int = 0,
        boundEmail: String? = "e2e@synthetic.local",
        onNewAccount: () -> Unit = {},
        onOpenQuote: (String) -> Unit = {},
        onOpenJob: (String) -> Unit = {},
        onOpenReport: (String) -> Unit = {},
        onOpenAccount: (String) -> Unit = {},
        onSyncAttention: () -> Unit = {},
    ) {
        compose.setContent {
            DashboardScreen(
                userName = "Tester",
                loading = loading,
                items = dashboardItems(quotes, jobs, reports, accounts, today),
                todayEpochDay = today,
                syncPending = syncPending,
                syncPaused = syncPaused,
                boundEmail = boundEmail,
                onNewClient = {},
                onNewQuote = {},
                onNewJob = {},
                onNewAccount = onNewAccount,
                onOpenQuote = onOpenQuote,
                onOpenJob = onOpenJob,
                onOpenReport = onOpenReport,
                onOpenAccount = onOpenAccount,
                onSyncAttention = onSyncAttention,
                onSettings = {},
            )
        }
    }

    @Test
    fun accionCuentaDeCobro_habilitadaYNavega() {
        var navigated = false
        screen(onNewAccount = { navigated = true })
        compose.onNodeWithText("Cuenta de Cobro").assertIsEnabled().performClick()
        assertTrue(navigated)
    }

    @Test
    fun resumenes_muestranElementosYNaveganASuDetalle() {
        val opened = mutableListOf<String>()
        screen(
            quotes = listOf(emittedQuote, expiredQuote),
            jobs = listOf(processJob),
            reports = listOf(draftReport),
            accounts = listOf(partialAccount),
            onOpenQuote = { opened += "quote:$it" },
            onOpenJob = { opened += "job:$it" },
            onOpenReport = { opened += "report:$it" },
            onOpenAccount = { opened += "account:$it" },
        )
        compose.onNodeWithText("Cotizaciones por vencer y vencidas").assertExists()
        compose.onNodeWithText("Trabajos programados y en proceso").assertExists()
        compose.onNodeWithText("Informes pendientes").assertExists()
        compose.onNodeWithText("Cuentas por cobrar").assertExists()
        val vencida = dashboardDayLabel(expiredQuote.expiryDateEpochDay)
        val vence = dashboardDayLabel(emittedQuote.expiryDateEpochDay)
        compose.onNodeWithText("Vencida: $vencida", substring = true).assertExists()
        compose.onNodeWithText("Vence: $vence", substring = true).assertExists()

        compose.onNodeWithText("TR-PEND-0001 — Cliente Demo").performClick()
        compose.onNodeWithText("INF-PEND-0001 — Informe borrador").performClick()
        compose.onNodeWithText("CC-PEND-0001 — Cliente Demo").performClick()
        compose.onNodeWithText("COT-PEND-0002 — Cliente Demo").performClick()
        assertEquals(listOf("job:j1", "report:r1", "account:a1", "quote:q2"), opened)
    }

    @Test
    fun operacionesQueRequierenAtencion_enlazanALaPantallaDeAtencion() {
        var navigated = false
        screen(syncPaused = 3, syncPending = 5, onSyncAttention = { navigated = true })
        compose.onNodeWithText("3 operaciones requieren atención").performClick()
        assertTrue(navigated)
    }

    @Test
    fun indicadores_pendientesYSincronizado() {
        var pending by mutableIntStateOf(4)
        compose.setContent {
            DashboardScreen(
                userName = "Tester", loading = false,
                items = dashboardItems(emptyList(), emptyList(), emptyList(), emptyList(), today),
                todayEpochDay = today, syncPending = pending, syncPaused = 0,
                boundEmail = "e2e@synthetic.local",
                onNewClient = {}, onNewQuote = {}, onNewJob = {}, onNewAccount = {},
                onOpenQuote = {}, onOpenJob = {}, onOpenReport = {}, onOpenAccount = {},
                onSyncAttention = {}, onSettings = {},
            )
        }
        compose.onNodeWithText("4 operaciones pendientes de sincronizar").assertExists()
        compose.runOnIdle { pending = 0 }
        compose.onNodeWithText("Sincronizado").assertExists()
    }

    @Test
    fun estados_vacioYCarga() {
        var loading by mutableStateOf(false)
        compose.setContent {
            DashboardScreen(
                userName = "Tester", loading = loading,
                items = dashboardItems(emptyList(), emptyList(), emptyList(), emptyList(), today),
                todayEpochDay = today, syncPending = 0, syncPaused = 0,
                boundEmail = "e2e@synthetic.local",
                onNewClient = {}, onNewQuote = {}, onNewJob = {}, onNewAccount = {},
                onOpenQuote = {}, onOpenJob = {}, onOpenReport = {}, onOpenAccount = {},
                onSyncAttention = {}, onSettings = {},
            )
        }
        compose.onNodeWithText("Sin pendientes por revisar.").assertExists()
        compose.runOnIdle { loading = true }
        compose.onNodeWithText("Cargando…").assertExists()
    }
}

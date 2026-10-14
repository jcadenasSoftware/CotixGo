package com.cotixgo.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.data.ConsolidatedCollectionEntity
import com.cotixgo.app.ui.ConsolidatedDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI instrumentada del detalle del Consolidado (F7): una cancelación
 * rechazada por el repositorio muestra el motivo dentro del diálogo y
 * mantiene al usuario en contexto.
 */
@RunWith(AndroidJUnit4::class)
class ConsolidatedDetailScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val consolidated = ConsolidatedCollectionEntity(
        id = "cons-1", userId = "u1", clientId = "cli-1",
        provisionalNumber = "CONS-PEND-0001", status = "PENDIENTE",
        currency = "COP", totalAmount = "200.00", appliedAmount = "0.00",
        balance = "200.00", clientNameSnapshot = "Cliente Demo",
        createdAt = 1, updatedAt = 1,
    )

    @Test
    fun cancel_rejected_showsErrorAndStaysInDialog() {
        compose.setContent {
            ConsolidatedDetailScreen(
                consolidated = consolidated,
                memberAccounts = emptyList(),
                payments = emptyList(),
                onEdit = {},
                onCancelConsolidated = { done -> done(false) },
                onSetArchived = {},
                onOpenAccount = {},
                onOpenPayment = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Cancelar consolidado").performClick()
        // Botón, título y confirmación comparten texto: el último nodo es el
        // botón de confirmación dentro del diálogo.
        compose.onAllNodesWithText("Cancelar consolidado").onLast().performClick()
        // Rechazo visible; el diálogo sigue abierto en el mismo contexto.
        compose.onNodeWithText("No se pudo cancelar", substring = true).assertExists()
        compose.onNodeWithText("La cancelación libera las cuentas miembro", substring = true)
            .assertExists()
    }
}

package com.cotixgo.app

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.data.PaymentEntity
import com.cotixgo.app.ui.PaymentDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * UI instrumentada del detalle de Pago (F7): las operaciones rechazadas
 * por reglas de saldo/estado muestran el mensaje del repositorio y el
 * diálogo permanece abierto (el usuario no pierde el contexto); las
 * aceptadas cierran el diálogo.
 */
@RunWith(AndroidJUnit4::class)
class PaymentDetailScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val payment = PaymentEntity(
        id = "pay-1", userId = "u1", clientId = "cli-1",
        amount = "100.00", effectiveAmount = "100.00",
        appliedAmount = "0.00", unassignedAmount = "40.00",
        refundedAmount = "60.00",
        paidAtEpochDay = LocalDate.now().toEpochDay(),
        createdAt = 1, updatedAt = 1,
    )

    private fun detail(
        onCorrect: (String, String, (String?) -> Unit) -> Unit = { _, _, d -> d(null) },
        onAnnul: (String, (String?) -> Unit) -> Unit = { _, d -> d(null) },
        onRefund: (String, Long, String?, String, (String?) -> Unit) -> Unit = { _, _, _, _, d -> d(null) },
    ) {
        compose.setContent {
            PaymentDetailScreen(
                payment = payment,
                clientName = "Cliente Demo",
                methodName = null,
                account = null,
                consolidated = null,
                receivableAccounts = emptyList(),
                receivableConsolidateds = emptyList(),
                assignments = emptyList(),
                adjustments = emptyList(),
                refunds = emptyList(),
                accountsById = emptyMap(),
                consolidatedsById = emptyMap(),
                methods = emptyList(),
                latestOps = emptyMap(),
                onAssign = { _, _, _, _, d -> d(null) },
                onUnassign = { _, d -> d(null) },
                onReassign = { _, _, _, d -> d(null) },
                onCorrect = onCorrect,
                onAnnul = onAnnul,
                onRefund = onRefund,
                onBack = {},
            )
        }
    }

    @Test
    fun correct_belowRefundFloor_showsErrorAndStaysInDialog() {
        val error = "El nuevo efectivo no puede quedar por debajo del reembolsado acumulado."
        detail(onCorrect = { _, _, done -> done(error) })
        compose.onNodeWithText("Corregir").performClick()
        compose.onNodeWithText("Nuevo importe efectivo *").performTextInput("50.00")
        compose.onNodeWithText("Motivo *").performTextInput("corrección")
        compose.onNodeWithText("Confirmar").performClick()
        // El rechazo se muestra dentro del diálogo, que permanece abierto.
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Corregir importe del pago").assertExists()
    }

    @Test
    fun annul_rejected_showsErrorAndStaysInDialog() {
        val error = "El pago ya está anulado."
        detail(onAnnul = { _, done -> done(error) })
        compose.onNodeWithText("Anular").performClick()
        compose.onNodeWithText("Motivo *").performTextInput("error de captura")
        compose.onNodeWithText("Confirmar").performClick()
        compose.onNodeWithText(error).assertExists()
        compose.onNodeWithText("Anular pago").assertExists()
    }

    @Test
    fun refund_aboveAvailable_showsErrorAndStaysInDialog() {
        val error = "El reembolso excede el disponible no aplicado."
        detail(onRefund = { _, _, _, _, done -> done(error) })
        compose.onNodeWithText("Reembolsar").performClick()
        compose.onNodeWithText("Importe a reembolsar *").performTextInput("45.00")
        compose.onNodeWithText("Motivo *").performTextInput("devolución")
        compose.onNodeWithText("Confirmar").performClick()
        compose.onNodeWithText(error).assertExists()
        // El diálogo sigue abierto: su campo de importe permanece visible.
        compose.onNodeWithText("Importe a reembolsar *").assertExists()
    }

    @Test
    fun correct_accepted_closesDialog() {
        detail(onCorrect = { _, _, done -> done(null) })
        compose.onNodeWithText("Corregir").performClick()
        compose.onNodeWithText("Nuevo importe efectivo *").performTextInput("90.00")
        compose.onNodeWithText("Motivo *").performTextInput("corrección")
        compose.onNodeWithText("Confirmar").performClick()
        compose.onNodeWithText("Corregir importe del pago").assertDoesNotExist()
    }
}

package com.cotixgo.app

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cotixgo.app.data.AppliedRetentionSnapshot
import com.cotixgo.app.data.ClientSnapshot
import com.cotixgo.app.data.ProfileSnapshot
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteLineSnapshot
import com.cotixgo.app.data.QuoteSnapshot
import com.cotixgo.app.data.QuoteVersionEntity
import com.cotixgo.app.ui.QuoteDetailScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * UI instrumentada del detalle de Cotización: la acción "Vista previa PDF"
 * está disponible en EMITIDA (sin exigir la aprobación) y usa el snapshot
 * congelado de la versión emitida; si falta o no se puede leer, el detalle
 * muestra el aviso en lugar de generar un PDF incompleto.
 */
@RunWith(AndroidJUnit4::class)
class QuoteDetailScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now().toEpochDay()

    private fun quote(status: String, emittedVersion: Int) = QuoteEntity(
        id = "q1", userId = "u1", clientId = "c1", profileId = "p1",
        provisionalNumber = "COT-PEND-AB12", title = "Demo",
        status = status, issueDateEpochDay = today, validityDays = 30,
        expiryDateEpochDay = today + 29, currency = "COP",
        currentVersion = emittedVersion.coerceAtLeast(1),
        emittedVersion = emittedVersion,
        gross = "861702.13", totalRetained = "51702.13", net = "810000.00",
        clientNameSnapshot = "Cliente Demo", createdAt = 1, updatedAt = 1,
    )

    private val emittedSnap = QuoteSnapshot(
        version = 1, provisionalNumber = "COT-PEND-AB12", title = "Demo",
        client = ClientSnapshot("c1", "Cliente Demo", "PERSONA", "", "", "", ""),
        profile = ProfileSnapshot("p1", "Perfil Demo", "", "", null),
        issueDate = LocalDate.now().toString(),
        validityDays = 30, expiryDate = LocalDate.now().plusDays(29).toString(),
        currency = "COP", notes = "",
        lines = listOf(
            QuoteLineSnapshot("SERVICIO", "Servicio A", "1", "und", "478723.40", "478723.40"),
            QuoteLineSnapshot("SERVICIO", "Servicio B", "1", "und", "382978.73", "382978.73"),
        ),
        retentions = listOf(
            AppliedRetentionSnapshot("Ret 6%", "6", listOf("SERVICIO"), "861702.13", "51702.13"),
        ),
        gross = "861702.13", totalRetained = "51702.13", net = "810000.00",
        savedAtEpochMs = 1,
    )

    private fun detail(quote: QuoteEntity, snapshot: QuoteSnapshot?) {
        compose.setContent {
            QuoteDetailScreen(
                quote = quote,
                versions = emptyList(),
                latestSnapshot = snapshot,
                emittedSnapshot = snapshot,
                emitFailed = false,
                onEmit = {},
                onApprove = {},
                onReject = {},
                onRevertReject = {},
                onCancel = {},
                onRevalidate = {},
                onEdit = {},
                onArchive = {},
                onOpenVersion = {},
                onCreateJob = {},
                onBack = {},
            )
        }
    }

    @Test
    fun issued_showsPdfPreviewBeforeApproval() {
        detail(quote("EMITIDA", emittedVersion = 1), emittedSnap)
        // Disponible en EMITIDA junto a las acciones del documento, sin
        // aprobar primero.
        compose.onNodeWithText("Aprobar").assertExists()
        compose.onNodeWithText("Vista previa PDF").assertExists()
        // Totales del snapshot congelado, en formato localizado.
        compose.onNodeWithText("861.702,13", substring = true).assertExists()
        compose.onNodeWithText("810.000,00", substring = true).assertExists()
    }

    @Test
    fun issued_missingSnapshot_showsClearMessage() {
        detail(quote("EMITIDA", emittedVersion = 1), null)
        compose.onNodeWithText(
            "Vista previa PDF no disponible", substring = true,
        ).assertExists()
    }

    @Test
    fun draft_hasNoPdfPreview() {
        detail(quote("BORRADOR", emittedVersion = 0), emittedSnap)
        compose.onNodeWithText("Vista previa PDF").assertDoesNotExist()
        compose.onNodeWithText("Emitir").assertExists()
    }

    @Test
    fun approved_showsCreateJobPrimaryAndEmittedVersionPill() {
        val q = quote("APROBADA", emittedVersion = 2).copy(currentVersion = 2)
        compose.setContent {
            com.cotixgo.app.ui.CotixGoTheme {
                QuoteDetailScreen(
                quote = q,
                versions = listOf(
                    QuoteVersionEntity(id = "v2", quoteId = "q1", version = 2, snapshotJson = "{}", createdAt = 2),
                    QuoteVersionEntity(id = "v1", quoteId = "q1", version = 1, snapshotJson = "{}", createdAt = 1),
                ),
                latestSnapshot = emittedSnap,
                emittedSnapshot = emittedSnap,
                emitFailed = false,
                onEmit = {},
                onApprove = {},
                onReject = {},
                onRevertReject = {},
                onCancel = {},
                onRevalidate = {},
                onEdit = {},
                onArchive = {},
                onOpenVersion = {},
                onCreateJob = {},
                onBack = {},
            )
            }
        }
        // Acción principal APROBADA arriba, Vista previa secundaria, Archivar
        // terciaria; la versión emitida se identifica con el pill "emitida".
        compose.onNodeWithText("Crear trabajo").assertExists()
        compose.onNodeWithText("Vista previa PDF").assertExists()
        compose.onNodeWithText("Archivar").assertExists()
        saveShot("approved-detail.png")
        // Las versiones están bajo el fold: el scroll las compone.
        compose.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasText("Versión 2"))
        compose.onNodeWithText("Versión 2").assertExists()
        compose.onNodeWithText("emitida").assertExists()
        saveShot("approved-versions.png")
    }

    private fun saveShot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        FileOutputStream(File(dir, name)).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}

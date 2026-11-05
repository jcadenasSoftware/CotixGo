package com.cotixgo.app

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.ui.JobFormScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * UI instrumentada del formulario de Trabajo: verifica la carga asíncrona
 * del Trabajo en edición y el manejo del resultado del guardado.
 */
@RunWith(AndroidJUnit4::class)
class JobFormScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val client = ClientEntity(
        id = "cli-1", userId = "u1", type = "PERSONA", name = "Cliente Demo",
        createdAt = 1, updatedAt = 1,
    )
    private val profile = ProfileEntity(
        id = "pro-1", userId = "u1", businessName = "Profesional Demo",
        createdAt = 1, updatedAt = 1,
    )
    private val job = JobEntity(
        id = "job-1", userId = "u1", clientId = client.id, profileId = profile.id,
        provisionalNumber = "TR-PEND-0001", status = "BORRADOR",
        executionAddress = "Calle 12 #34-56", notes = "nota inicial",
        clientNameSnapshot = client.name, createdAt = 1, updatedAt = 1,
    )

    private fun form(
        initial: JobEntity?,
        preselectedClientId: String? = null,
        onSave: (com.cotixgo.app.data.JobInput, (String?) -> Unit) -> Unit = { _, d -> d(null) },
        onSaved: (String) -> Unit = {},
    ) {
        compose.setContent {
            JobFormScreen(
                initial = initial,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                preselectedClientId = preselectedClientId,
                onSave = onSave,
                onSaved = onSaved,
                onBack = {},
            )
        }
    }

    @Test
    fun edit_populatesFieldsAfterAsyncLoad() {
        var initial by mutableStateOf<JobEntity?>(null)
        compose.setContent {
            JobFormScreen(
                initial = initial,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                onSave = { _, d -> d(null) },
                onSaved = {},
                onBack = {},
            )
        }
        // Antes de la emisión, el formulario muestra el estado vacío.
        compose.onNodeWithText("Cliente *").assertExists()
        // Llega el Trabajo persistido por el Flow: los campos se pueblan
        // con Cliente, Perfil, dirección y observaciones existentes.
        compose.runOnIdle { initial = job }
        compose.onNodeWithText("Cliente Demo").assertExists()
        compose.onNodeWithText("Profesional Demo").assertExists()
        compose.onNodeWithTag("jobAddress").assertTextContains("Calle 12 #34-56")
        compose.onNodeWithTag("jobNotes").assertTextContains("nota inicial")
    }

    @Test
    fun edit_userEditsSurviveReEmission() {
        var initial by mutableStateOf<JobEntity?>(job)
        compose.setContent {
            JobFormScreen(
                initial = initial,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                onSave = { _, d -> d(null) },
                onSaved = {},
                onBack = {},
            )
        }
        compose.onNodeWithTag("jobNotes").performTextClearance()
        compose.onNodeWithTag("jobNotes").performTextInput("editado por el usuario")
        // El Flow re-emite el mismo Trabajo (p.ej. por sync): no debe pisar lo escrito.
        compose.runOnIdle { initial = job.copy(updatedAt = 2) }
        compose.onNodeWithTag("jobNotes").assertTextContains("editado por el usuario")
    }

    @Test
    fun edit_failedSave_keepsFormOpenWithError() {
        var savedCalled = false
        var backCalled = false
        compose.setContent {
            JobFormScreen(
                initial = job,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                onSave = { _, d -> d(null) },
                onSaved = { savedCalled = true },
                onBack = { backCalled = true },
            )
        }
        compose.onNodeWithText("Guardar trabajo").performClick()
        compose.onNodeWithText("No se pudo guardar el trabajo", substring = true).assertExists()
        assertTrue(!savedCalled)
        assertTrue(!backCalled)
        // El formulario sigue abierto.
        compose.onNodeWithText("Editar trabajo").assertExists()
    }

    @Test
    fun edit_successfulSave_navigatesAfterConfirmation() {
        var savedId: String? = null
        compose.setContent {
            JobFormScreen(
                initial = job,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                onSave = { _, d -> d(job.id) },
                onSaved = { savedId = it },
                onBack = {},
            )
        }
        compose.onNodeWithTag("jobNotes").performTextInput("nota corregida")
        compose.onNodeWithText("Guardar trabajo").performClick()
        // Navega solo cuando el guardado confirmó éxito con el id del Trabajo.
        assertEquals(job.id, savedId)
        compose.onNodeWithText("No se pudo guardar el trabajo", substring = true).assertDoesNotExist()
    }

    @Test
    fun create_newJob_savesInputAndNavigates() {
        var input: com.cotixgo.app.data.JobInput? = null
        var savedId: String? = null
        form(
            initial = null,
            preselectedClientId = client.id,
            onSave = { i, d -> input = i; d("job-new") },
            onSaved = { savedId = it },
        )
        compose.onNodeWithText("Nuevo trabajo").assertExists()
        compose.onNodeWithTag("jobAddress").performTextInput("Av 5 #10")
        compose.onNodeWithText("Guardar trabajo").performClick()
        assertEquals(client.id, input?.clientId)
        assertEquals(profile.id, input?.profileId)
        assertEquals("Av 5 #10", input?.executionAddress)
        assertEquals("job-new", savedId)
    }

    @Test
    fun create_newJob_preloadsClientAndLinksSourceQuote() {
        var input: com.cotixgo.app.data.JobInput? = null
        val srcQuote = QuoteEntity(
            id = "quo-1", userId = "u1", clientId = client.id, profileId = profile.id,
            provisionalNumber = "CT-0001", status = "APROBADA",
            issueDateEpochDay = 1, validityDays = 30, expiryDateEpochDay = 31,
            currency = "COP", net = "810000.00",
            createdAt = 1, updatedAt = 1,
        )
        compose.setContent {
            com.cotixgo.app.ui.CotixGoTheme {
                JobFormScreen(
                    initial = null,
                    clients = listOf(client),
                    profile = profile,
                    linkableQuotes = listOf(srcQuote),
                    preselectedClientId = client.id,
                    preselectedClientName = client.name,
                    preselectedQuoteId = srcQuote.id,
                    onSave = { i, _ -> input = i },
                    onSaved = {},
                    onBack = {},
                )
            }
        }
        // El cliente de la cotización queda precargado sin abrir el selector
        // y la cotización de origen se marca como vinculada.
        compose.onNodeWithText("Cliente Demo").assertExists()
        compose.onNodeWithText("CT-0001").assertExists()
        // El neto se muestra con el formato monetario localizado de CotixGo.
        compose.onNodeWithText("Neto $ 810.000,00 COP").assertExists()
        saveShot("job-form-preloaded.png")
        compose.onNodeWithText("Guardar trabajo").performClick()
        assertEquals(client.id, input?.clientId)
        assertEquals(listOf(srcQuote.id), input?.quoteIds)
    }

    private fun saveShot(name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val dir = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.filesDir
        java.io.FileOutputStream(java.io.File(dir, name)).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test
    fun create_newJob_showsNoticeWhenSourceClientMissing() {
        compose.setContent {
            JobFormScreen(
                initial = null,
                clients = listOf(client),
                profile = profile,
                linkableQuotes = emptyList(),
                preselectedQuoteId = "quo-missing",
                onSave = { _, d -> d(null) },
                onSaved = {},
                onBack = {},
            )
        }
        // Sin dato de cliente precargable, el formulario explica el estado
        // en lugar de mostrar un selector vacío sin contexto.
        compose.onNodeWithText("No se pudo precargar", substring = true).assertExists()
        compose.onNodeWithText("Seleccionar cliente").assertExists()
    }
}

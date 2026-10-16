package com.cotixgo.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.CollectionAccountInput
import com.cotixgo.app.data.CollectionAccountItemEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.MaterialEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.PurchaseEntity
import com.cotixgo.app.data.PurchaseInput
import com.cotixgo.app.data.PurchaseItemEntity
import com.cotixgo.app.data.UserEntity
import com.cotixgo.app.ui.CollectionAccountEditorScreen
import com.cotixgo.app.ui.PurchaseEditorScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regresión del defecto de carga asíncrona en editores: los Flow de Room
 * emiten `emptyList()` antes de la primera consulta real; los editores
 * recordaban esa lista como `initial*` y Guardar vaciaba líneas y
 * vínculos persistidos. Las rutas ahora distinguen "cargando" (null) de
 * "cargado" (lista, aunque vacía) y no componen el editor hasta la
 * primera emisión. Estos tests replican el contrato de la ruta y
 * verifican que Guardar conserva líneas y relaciones.
 */
@RunWith(AndroidJUnit4::class)
class EditorAsyncLoadTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // ---- Compra ----

    private val matCemento = MaterialEntity(
        id = "mat-1", userId = "u1", name = "Cemento", unit = "kg",
        createdAt = 1, updatedAt = 1,
    )
    private val matVarilla = MaterialEntity(
        id = "mat-2", userId = "u1", name = "Varilla", unit = "und",
        createdAt = 1, updatedAt = 1,
    )
    private val purchase = PurchaseEntity(
        id = "pur-1", userId = "u1", provisionalNumber = "COM-PEND-AAAA",
        status = "BORRADOR", purchaseDateEpochDay = 20742,
        supplierPlace = "Depósito Sintético", currency = "COP",
        totalAmount = "1250.00", createdAt = 1, updatedAt = 1,
    )
    private val itemA = PurchaseItemEntity(
        id = "pi-1", purchaseId = "pur-1", position = 1, materialId = "mat-1",
        materialNameSnapshot = "Cemento", quantity = "10", unit = "kg",
        unitPrice = "100.00", lineTotal = "1000.00", condition = "NUEVO",
        createdAt = 1,
    )
    private val itemB = PurchaseItemEntity(
        id = "pi-2", purchaseId = "pur-1", position = 2, materialId = "mat-2",
        materialNameSnapshot = "Varilla", quantity = "5", unit = "und",
        unitPrice = "50.00", lineTotal = "250.00", condition = "USADO",
        createdAt = 1,
    )

    @Test
    fun purchaseEdit_linesLoadedAfterFirstEmission_preservedOnSave() {
        var items by mutableStateOf<List<PurchaseItemEntity>?>(null)
        var saved: PurchaseInput? = null
        compose.setContent {
            val loaded = items
            if (loaded == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                PurchaseEditorScreen(
                    initial = purchase,
                    initialItems = loaded,
                    materials = listOf(matCemento, matVarilla),
                    confirmError = null,
                    onSave = { input, _, done -> saved = input; done(null) },
                    onBack = {},
                )
            }
        }
        // Antes de la emisión el editor no se compone: "cargando" no es
        // un documento sin líneas.
        compose.onNodeWithText("Editar compra").assertDoesNotExist()
        compose.onNodeWithText("Cemento").assertDoesNotExist()
        // Llegan las líneas persistidas.
        compose.runOnIdle { items = listOf(itemA, itemB) }
        compose.onNodeWithText("Editar compra").assertExists()
        compose.onNodeWithText("Cemento").assertExists()
        compose.onNodeWithText("Varilla").assertExists()
        // Guardar conserva las dos líneas con sus datos (el botón cierra
        // la LazyColumn: hay que desplazar hasta él).
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardar"))
        compose.onNodeWithText("Guardar").performClick()
        compose.runOnIdle {
            val s = saved ?: error("onSave no se invocó")
            assertEquals(2, s.items.size)
            assertEquals("mat-1", s.items[0].materialId)
            assertEquals("10", s.items[0].quantity)
            assertEquals("100.00", s.items[0].unitPrice)
            assertEquals("mat-2", s.items[1].materialId)
            assertEquals("5", s.items[1].quantity)
        }
    }

    // ---- Cuenta de Cobro ----

    private val user = UserEntity(
        id = "u1", name = "Usuario Test", mainCurrency = "COP",
        createdAt = 1, updatedAt = 1,
    )
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
        clientNameSnapshot = client.name, createdAt = 1, updatedAt = 1,
    )
    private val account = CollectionAccountEntity(
        id = "acc-1", userId = "u1", clientId = client.id, profileId = profile.id,
        provisionalNumber = "CC-PEND-AAAA", status = "PENDIENTE",
        currency = "COP", gross = "100.00", net = "100.00", balance = "100.00",
        issuedAt = 1, clientNameSnapshot = client.name,
        createdAt = 1, updatedAt = 1,
    )
    private val accItem = CollectionAccountItemEntity(
        id = "ai-1", accountId = "acc-1", position = 1, conceptType = "SERVICIO",
        description = "Mano de obra", quantity = "2", unit = "hr",
        unitPrice = "50.00", lineTotal = "100.00", createdAt = 1,
    )

    @Test
    fun collectionEdit_itemsAndJobLinksLoadedAfterEmission_preservedOnSave() {
        var items by mutableStateOf<List<CollectionAccountItemEntity>?>(null)
        var jobIds by mutableStateOf<List<String>?>(null)
        var saved: CollectionAccountInput? = null
        compose.setContent {
            val loadedItems = items
            val loadedJobs = jobIds
            if (loadedItems == null || loadedJobs == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                CollectionAccountEditorScreen(
                    initial = account,
                    initialItems = loadedItems,
                    initialJobIds = loadedJobs,
                    initialQuoteIds = emptyList(),
                    clients = listOf(client),
                    profile = profile,
                    user = user,
                    jobs = listOf(job),
                    quotes = emptyList(),
                    takenJobIds = emptySet(),
                    takenQuoteIds = emptySet(),
                    retentions = emptyList(),
                    initialRetentions = emptyList(),
                    onSave = { input -> saved = input },
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("Editar cuenta").assertDoesNotExist()
        // Llegan líneas y vínculo.
        compose.runOnIdle {
            items = listOf(accItem)
            jobIds = listOf(job.id)
        }
        compose.onNodeWithText("Editar cuenta").assertExists()
        compose.onNodeWithText("Mano de obra", substring = true).assertExists()
        // Guardar conserva la línea y el vínculo al Trabajo.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Guardar"))
        compose.onNodeWithText("Guardar").performClick()
        compose.runOnIdle {
            val s = saved ?: error("onSave no se invocó")
            assertEquals(1, s.items.size)
            assertEquals("Mano de obra", s.items[0].description)
            assertEquals(listOf(job.id), s.jobIds)
        }
    }
}

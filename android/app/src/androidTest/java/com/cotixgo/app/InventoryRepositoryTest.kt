package com.cotixgo.app

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.MaterialCondition
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.MovementOrigin
import com.cotixgo.app.core.MovementType
import com.cotixgo.app.data.AppDatabase
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.EntityTypes
import com.cotixgo.app.data.InventoryMovementInput
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.MaterialInput
import com.cotixgo.app.data.PurchaseInput
import com.cotixgo.app.data.PurchaseLineInput
import com.cotixgo.app.data.Session
import com.cotixgo.app.data.SyncRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/**
 * Pruebas del repositorio F8 sobre Room real (BD en memoria): catálogo
 * global de Materiales (nombre+unidad, archivado), Compras BORRADOR →
 * CONFIRMADA → ANULADA con numeración `COM-PEND-` local y encolado
 * durable, movimientos manuales (ajuste/sobrante con nota), consumo
 * explícito vía `job_material` vinculado (sin reinterpretar históricos)
 * y el saldo observado que combina espejo del servidor + pendientes
 * locales. Datos exclusivamente sintéticos.
 */
@RunWith(AndroidJUnit4::class)
class InventoryRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AppRepository
    private lateinit var prodBefore: Map<String, String?>

    // Preferencias exclusivas de prueba (D2): la sesión nunca toca
    // cotixgo.session ni los artefactos reales de la app.
    private val prefsName = TestIsolation.name("e2e-session-inventory")

    @Before
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        prodBefore = TestIsolation.snapshotProd(ctx)
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).build()
        val session = Session(ctx, db, prefsName).apply { apiBaseUrl = "http://127.0.0.1:1" }
        repo = AppRepository(db, SyncRepository(db, session))
    }

    @After
    fun tearDown() {
        db.close()
        TestIsolation.deletePrefs(
            ApplicationProvider.getApplicationContext(), prefsName)
        TestIsolation.assertProdUntouched(
            ApplicationProvider.getApplicationContext(), prodBefore)
    }

    private suspend fun seedUser(): String {
        repo.completeOnboarding("Test User", "Perfil Test")
        return db.userDao().observe().first()!!.id
    }

    private suspend fun seedJob(userId: String): String {
        repo.saveClient(ClientInput(type = ClientType.PERSONA, name = "Cliente T"))
        val client = db.clientDao().observeForUser(userId).first().first()
        val profile = db.profileDao().observeForUser(userId).first().first()
        return repo.saveJob(JobInput(clientId = client.id, profileId = profile.id))!!
    }

    private fun purchaseInput(matId: String, qty: String = "3") = PurchaseInput(
        purchaseDateEpochDay = LocalDate.now().toEpochDay(),
        items = listOf(
            PurchaseLineInput(materialId = matId, quantity = qty, unit = "kg", unitPrice = "10.00"),
        ),
    )

    @Test
    fun material_validacion_archivado_yEncolado() = runTest {
        val userId = seedUser()
        // Nombre y unidad requeridos.
        assertNull(repo.saveMaterial(MaterialInput(name = "", unit = "kg")))
        assertNull(repo.saveMaterial(MaterialInput(name = "Cemento", unit = " ")))

        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg", description = "Gris"))!!
        val m2 = repo.saveMaterial(MaterialInput(name = "Varilla", unit = "und"))!!
        val mats = db.materialDao().observeForUser(userId).first()
        assertEquals(2, mats.size)
        assertTrue(mats.all { it.pendingSync })

        // Operaciones encoladas para el push (una por alta).
        val ops = db.syncDao().pendingOps()
        assertEquals(2, ops.count { it.entityType == EntityTypes.MATERIAL })

        // Archivado: solo visibilidad; sigue existiendo en el catálogo.
        repo.setMaterialArchived(m1, true)
        assertTrue(db.materialDao().get(m1)!!.archived)
        assertEquals(
            1,
            db.materialDao().observeActiveForUser(userId).first().size,
        )
        // El material archivado ya no acepta movimientos manuales.
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(
                    materialId = m1, condition = MaterialCondition.NUEVO,
                    movementType = MovementType.ADJUSTMENT_IN, origin = MovementOrigin.AJUSTE,
                    quantity = "1", note = "x",
                ),
            ),
        )
        // Desarchivar lo rehabilita.
        repo.setMaterialArchived(m1, false)
        assertNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(
                    materialId = m1, condition = MaterialCondition.NUEVO,
                    movementType = MovementType.ADJUSTMENT_IN, origin = MovementOrigin.AJUSTE,
                    quantity = "1", note = "Reingreso",
                ),
            ),
        )
        assertEquals(m2, mats[1].id) // orden por nombre: Cemento, Varilla
    }

    @Test
    fun compra_ciclo_completo_yEncolado() = runTest {
        val userId = seedUser()
        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg"))!!

        // Sin fecha → rechazo; sin líneas al confirmar → rechazo.
        assertNotNull(
            repo.savePurchase(purchaseInput(m1).copy(purchaseDateEpochDay = 0)),
        )
        assertNull(repo.savePurchase(purchaseInput(m1).copy(items = emptyList())))
        val empty = db.purchaseDao().observeForUser(userId).first().last()
        assertNotNull(repo.confirmPurchase(empty.id)) // BORRADOR sin líneas

        // BORRADOR con línea: numeración provisional única y encolado.
        assertNull(repo.savePurchase(purchaseInput(m1)))
        val p1 = db.purchaseDao().observeForUser(userId).first().first { it.id != empty.id }
        assertEquals("BORRADOR", p1.status)
        assertTrue(p1.provisionalNumber.startsWith("COM-PEND-"))
        assertEquals("COP", p1.currency)
        val items = db.purchaseItemDao().forPurchase(p1.id)
        assertEquals(1, items.size)
        assertEquals("Cemento", items[0].materialNameSnapshot)
        assertEquals("30.00", items[0].lineTotal)
        assertEquals("30.00", p1.totalAmount)

        // Confirmar: transición válida y nuevo op encolado.
        assertNull(repo.confirmPurchase(p1.id))
        assertEquals("CONFIRMADA", db.purchaseDao().get(p1.id)!!.status)
        val opsAfterConfirm = db.syncDao().pendingOps()
        assertTrue(opsAfterConfirm.count { it.entityType == EntityTypes.PURCHASE && it.entityId == p1.id } >= 2)
        assertNull(opsAfterConfirm.firstOrNull {
            it.entityType == EntityTypes.INVENTORY_MOVEMENT
        }) // los movimientos de compra los deriva el servidor, no el cliente

        // Edición en CONFIRMADA (diferencial del lado servidor): local permite.
        assertNull(repo.savePurchase(purchaseInput(m1, "7"), p1.id))
        assertEquals("70.00", db.purchaseDao().get(p1.id)!!.totalAmount)

        // Re-confirmar una ya confirmada: local rechaza.
        assertNotNull(repo.confirmPurchase(p1.id))

        // Anular: terminal; el servidor genera los inversos.
        assertNull(repo.annulPurchase(p1.id))
        assertEquals("ANULADA", db.purchaseDao().get(p1.id)!!.status)
        assertNotNull(repo.annulPurchase(p1.id))
        assertNotNull(repo.savePurchase(purchaseInput(m1), p1.id))

        // Archivar la ANULADA sí está permitido (no revierte nada).
        repo.setPurchaseArchived(p1.id, true)
        assertTrue(db.purchaseDao().get(p1.id)!!.archived)
    }

    @Test
    fun compra_confirmada_no_se_vacia() = runTest {
        val userId = seedUser()
        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg"))!!
        assertNull(repo.savePurchase(purchaseInput(m1)))
        val p1 = db.purchaseDao().observeForUser(userId).first().first()
        assertNull(repo.confirmPurchase(p1.id))

        // Regresión: una edición que declare items vacíos sobre una
        // CONFIRMADA se rechaza en local (misma regla que el servidor) —
        // las líneas persistidas no se borran.
        assertNotNull(
            repo.savePurchase(purchaseInput(m1).copy(items = emptyList()), p1.id),
        )
        val items = db.purchaseItemDao().forPurchase(p1.id)
        assertEquals(1, items.size)
        assertEquals("30.00", db.purchaseDao().get(p1.id)!!.totalAmount)
        assertEquals("CONFIRMADA", db.purchaseDao().get(p1.id)!!.status)

        // La edición válida con líneas sigue permitida en CONFIRMADA.
        assertNull(repo.savePurchase(purchaseInput(m1, "7"), p1.id))
        assertEquals(1, db.purchaseItemDao().forPurchase(p1.id).size)
        assertEquals("7", db.purchaseItemDao().forPurchase(p1.id)[0].quantity)

        // Y el borrador sí puede guardarse sin líneas (mínimo aprobado
        // solo exige línea para confirmar).
        assertNull(repo.savePurchase(purchaseInput(m1).copy(items = emptyList())))
        val draft = db.purchaseDao().observeForUser(userId).first().first { it.id != p1.id }
        assertEquals("BORRADOR", draft.status)
        assertEquals(0, db.purchaseItemDao().forPurchase(draft.id).size)
    }

    @Test
    fun compra_guardarYConfirmar_encadena_alta_y_confirmacion() = runTest {
        val userId = seedUser()
        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg"))!!

        // "Guardar y confirmar" en una compra nueva: nace BORRADOR y queda
        // CONFIRMADA local con create+confirm encolados — el push envía la
        // confirmación rebasada tras aplicar el alta; la intención no se
        // descarta aunque quede offline.
        assertNull(repo.savePurchase(purchaseInput(m1), confirm = true))
        val p = db.purchaseDao().observeForUser(userId).first().first()
        assertEquals("CONFIRMADA", p.status)
        val ops = db.syncDao().pendingOpsForEntity(EntityTypes.PURCHASE, p.id)
        assertEquals(2, ops.size)
        assertTrue(ops[0].payloadJson.contains("\"status\":\"BORRADOR\""))
        assertTrue(ops[1].payloadJson.contains("\"status\":\"CONFIRMADA\""))
        // Sin movimientos encolados: los de compra los deriva el servidor.
        assertNull(db.syncDao().pendingOps().firstOrNull {
            it.entityType == EntityTypes.INVENTORY_MOVEMENT
        })
    }

    @Test
    fun compra_numeracion_provisional_unica() = runTest {
        val userId = seedUser()
        val m1 = repo.saveMaterial(MaterialInput(name = "Arena", unit = "m3"))!!
        repo.savePurchase(purchaseInput(m1))
        repo.savePurchase(purchaseInput(m1))
        val nums = db.purchaseDao().observeForUser(userId).first().map { it.provisionalNumber }
        assertEquals(nums.size, nums.distinct().size)
    }

    @Test
    fun consumo_vinculado_crea_movimiento_appendOnly() = runTest {
        val userId = seedUser()
        val jobId = seedJob(userId)
        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg"))!!

        // Registro histórico descriptivo (sin vínculo): editable como antes.
        val jmDesc = repo.saveJobMaterial(
            JobMaterialInput(jobId = jobId, description = "Material viejo",
                origin = MaterialOrigin.PROFESIONAL, quantity = "1", unit = "und"),
        )!!
        assertNull(db.jobMaterialDao().get(jmDesc)!!.materialId)
        // No puede ganar vínculo al editarse.
        assertNull(
            repo.saveJobMaterial(
                JobMaterialInput(jobId = jobId, description = "Material viejo",
                    origin = MaterialOrigin.PROFESIONAL, quantity = "1", unit = "und",
                    materialId = m1, condition = MaterialCondition.NUEVO),
                existingId = jmDesc,
            ),
        )

        // Consumo nuevo vinculado: job_material + inventory_movement en la
        // misma transacción, ambos encolados (movimiento TRABAJO append-only).
        val jmLink = repo.saveJobMaterial(
            JobMaterialInput(jobId = jobId, description = "Consumo cemento",
                origin = MaterialOrigin.PROFESIONAL, quantity = "4", unit = "kg",
                materialId = m1, condition = MaterialCondition.NUEVO),
        )!!
        val jm = db.jobMaterialDao().get(jmLink)!!
        assertEquals(m1, jm.materialId)
        assertEquals("NUEVO", jm.condition)
        val movs = db.inventoryMovementDao().observeForMaterial(m1).first()
        assertEquals(1, movs.size)
        val mov = movs[0]
        assertEquals(MovementType.JOB_CONSUMPTION.name, mov.movementType)
        assertEquals(MovementOrigin.TRABAJO.name, mov.origin)
        assertEquals(jobId, mov.jobId)
        assertEquals(jmLink, mov.jobMaterialId)
        assertEquals("4", mov.quantity)
        val ops = db.syncDao().pendingOps()
        assertTrue(ops.any { it.entityType == EntityTypes.JOB_MATERIAL && it.entityId == jmLink })
        assertTrue(ops.any { it.entityType == EntityTypes.INVENTORY_MOVEMENT && it.entityId == mov.id })

        // El vínculo es inmutable: ni cantidad ni condición cambian.
        assertNull(
            repo.saveJobMaterial(
                JobMaterialInput(jobId = jobId, description = "Consumo cemento",
                    origin = MaterialOrigin.PROFESIONAL, quantity = "9", unit = "kg",
                    materialId = m1, condition = MaterialCondition.NUEVO),
                existingId = jmLink,
            ),
        )
        // El consumo vinculado exige origen PROFESIONAL y cantidad positiva.
        assertNull(
            repo.saveJobMaterial(
                JobMaterialInput(jobId = jobId, description = "x",
                    origin = MaterialOrigin.CLIENTE, quantity = "1", unit = "kg",
                    materialId = m1, condition = MaterialCondition.NUEVO),
            ),
        )
        assertNull(
            repo.saveJobMaterial(
                JobMaterialInput(jobId = jobId, description = "x",
                    origin = MaterialOrigin.PROFESIONAL, quantity = "0", unit = "kg",
                    materialId = m1, condition = MaterialCondition.NUEVO),
            ),
        )
    }

    @Test
    fun movimientos_manuales_notas_yCombos() = runTest {
        val userId = seedUser()
        val jobId = seedJob(userId)
        val m1 = repo.saveMaterial(MaterialInput(name = "Pintura", unit = "lt"))!!

        // Nota obligatoria en ajustes y sobrantes.
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.ADJUSTMENT_IN, MovementOrigin.AJUSTE, "2", note = ""),
            ),
        )
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.ADJUSTMENT_IN, MovementOrigin.SOBRANTE, "2", note = ""),
            ),
        )
        // Combinaciones no permitidas para el registro manual.
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.JOB_CONSUMPTION, MovementOrigin.TRABAJO, "1", note = "x"),
            ),
        )
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.ADJUSTMENT_OUT, MovementOrigin.SOBRANTE, "1", note = "x"),
            ),
        )
        // Sobrante con referencia a Trabajo inexistente → rechazo.
        assertNotNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.ADJUSTMENT_IN, MovementOrigin.SOBRANTE, "1",
                    jobId = "inexistente", note = "x"),
            ),
        )

        // Casos válidos: sobrante con Trabajo, ajuste de salida.
        assertNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.USADO,
                    MovementType.ADJUSTMENT_IN, MovementOrigin.SOBRANTE, "1.5",
                    jobId = jobId, note = "Sobrante del trabajo"),
            ),
        )
        assertNull(
            repo.saveInventoryMovement(
                InventoryMovementInput(m1, MaterialCondition.NUEVO,
                    MovementType.ADJUSTMENT_OUT, MovementOrigin.AJUSTE, "0.5",
                    note = "Merma"),
            ),
        )
        val movs = db.inventoryMovementDao().observeForMaterial(m1).first()
        assertEquals(2, movs.size)
        assertEquals("1.5", movs[0].quantity)
        assertEquals(jobId, movs[0].jobId)
        assertEquals("USADO", movs[0].condition)
        assertEquals("0.5", movs[1].quantity)
        // Ambos encolados como append-only.
        val ops = db.syncDao().pendingOps()
        assertEquals(2, ops.count { it.entityType == EntityTypes.INVENTORY_MOVEMENT })
    }

    @Test
    fun stock_observado_combina_espejo_y_pendientes() = runTest {
        val userId = seedUser()
        val m1 = repo.saveMaterial(MaterialInput(name = "Cemento", unit = "kg"))!!
        val m2 = repo.saveMaterial(MaterialInput(name = "Varilla", unit = "und"))!!

        // Sin espejo ni pendientes: saldo cero.
        var view = repo.observeInventory(userId).first()
        assertEquals(2, view.size)
        assertEquals(0.toBigDecimal(), view.first { it.material.id == m1 }.stockNew)

        // Compra CONFIRMADA local pendiente de sync: el delta aparece.
        repo.savePurchase(purchaseInput(m1, "10"))
        val p1 = db.purchaseDao().observeForUser(userId).first().last()
        repo.confirmPurchase(p1.id)
        view = repo.observeInventory(userId).first()
        assertEquals(
            10.toBigDecimal(),
            view.first { it.material.id == m1 }.stockNew,
        )

        // Consumo vinculado pendiente: resta sobre el delta de compra.
        val jobId = seedJob(userId)
        repo.saveJobMaterial(
            JobMaterialInput(jobId = jobId, description = "Consumo",
                origin = MaterialOrigin.PROFESIONAL, quantity = "4", unit = "kg",
                materialId = m1, condition = MaterialCondition.NUEVO),
        )
        view = repo.observeInventory(userId).first()
        assertEquals(
            6.toBigDecimal(),
            view.first { it.material.id == m1 }.stockNew,
        )
        // El otro material no se ve afectado.
        assertEquals(
            0.toBigDecimal(),
            view.first { it.material.id == m2 }.stockNew,
        )

        // Salida mayor al saldo → stock negativo permitido y visible.
        repo.saveInventoryMovement(
            InventoryMovementInput(m1, MaterialCondition.NUEVO,
                MovementType.ADJUSTMENT_OUT, MovementOrigin.AJUSTE, "20", note = "Merma"),
        )
        view = repo.observeInventory(userId).first()
        assertEquals(
            (-14).toBigDecimal(),
            view.first { it.material.id == m1 }.stockNew,
        )
    }
}

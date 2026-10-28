package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.MaterialCondition
import com.cotixgo.app.core.MovementOrigin
import com.cotixgo.app.core.MovementType
import com.cotixgo.app.core.PurchaseStatus
import com.cotixgo.app.core.PurchaseTransitions
import com.cotixgo.app.core.isPositiveQuantity
import com.cotixgo.app.core.movementIsInbound
import com.cotixgo.app.core.validateMaterial
import com.cotixgo.app.data.InventoryMovementEntity
import com.cotixgo.app.data.InventoryMovementInput
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.MaterialEntity
import com.cotixgo.app.data.MaterialInput
import com.cotixgo.app.data.MaterialStockView
import com.cotixgo.app.data.PurchaseEntity
import com.cotixgo.app.data.PurchaseInput
import com.cotixgo.app.data.PurchaseItemEntity
import com.cotixgo.app.data.PurchaseLineInput
import java.math.BigDecimal
import java.time.LocalDate

private fun stockText(q: BigDecimal, unit: String): String =
    "${q.stripTrailingZeros().toPlainString()} $unit".trim()

private fun movementSign(t: String): String =
    if (t == MovementType.PURCHASE_IN.name || t == MovementType.ADJUSTMENT_IN.name) "+" else "−"

private fun movementOriginLabel(origin: String): String = when (origin) {
    "COMPRA" -> "Compra"
    "TRABAJO" -> "Consumo en trabajo"
    "SOBRANTE" -> "Sobrante"
    else -> "Ajuste"
}

/** Chip de estado de Compra (misma paleta que Cobros/Pagos). */
@Composable
private fun PurchaseStatusChip(status: String) {
    val (bg, fg) = when (status) {
        "CONFIRMADA" -> MaterialTheme.colorScheme.primaryContainer to
            MaterialTheme.colorScheme.onPrimaryContainer
        "ANULADA" -> MaterialTheme.colorScheme.errorContainer to
            MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = MaterialTheme.shapes.small) {
        Text(
            purchaseStatusLabel(status),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = fg,
        )
    }
}

/** Fecha localizada (mismo helper que el resto de la app — español). */
private fun fmtDay(epochDay: Long): String = dashboardDayLabel(epochDay)

private fun purchaseStatusLabel(s: String): String = when (s) {
    "CONFIRMADA" -> "Confirmada"
    "ANULADA" -> "Anulada"
    else -> "Borrador"
}

// ---------- Inventario: catálogo de Materiales, stock y Compras ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InventoryScreen(
    inventory: List<MaterialStockView>,
    purchases: List<PurchaseEntity>,
    movements: List<InventoryMovementEntity>,
    latestOps: Map<Pair<String, String>, com.cotixgo.app.data.LatestOpState>,
    materialsById: Map<String, MaterialEntity>,
    jobsById: Map<String, JobEntity>,
    activeMaterials: List<MaterialEntity>,
    onOpenMaterial: (String) -> Unit,
    onNewPurchase: () -> Unit,
    onOpenPurchase: (String) -> Unit,
    onSaveMaterial: (MaterialInput, String?) -> Unit,
    onSaveMovement: (InventoryMovementInput, (String?) -> Unit) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var showArchived by remember { mutableStateOf(false) }
    var materialDialog by remember { mutableStateOf(false) }
    var movementDialog by remember { mutableStateOf(false) }

    if (materialDialog) {
        MaterialFormDialog(
            initial = null,
            onDismiss = { materialDialog = false },
            onSave = { input -> onSaveMaterial(input, null); materialDialog = false },
        )
    }
    if (movementDialog) {
        MovementFormDialog(
            materials = activeMaterials,
            jobs = jobsById.values.toList(),
            preselectedMaterialId = null,
            onDismiss = { movementDialog = false },
            onSave = { input, done -> onSaveMovement(input) { done(it); if (it == null) movementDialog = false } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Inventario") }) },
        floatingActionButton = {
            when (tab) {
                0 -> Button(onClick = { materialDialog = true }) { Text("+ Material") }
                1 -> Button(onClick = onNewPurchase) { Text("+ Compra") }
                else -> Button(onClick = { movementDialog = true }) { Text("+ Movimiento") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Materiales", "Compras", "Movimientos").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(t) }
                }
            }
            when (tab) {
                0 -> {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = !showArchived,
                            onClick = { showArchived = false },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        ) { Text("Activos") }
                        SegmentedButton(
                            selected = showArchived,
                            onClick = { showArchived = true },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        ) { Text("Archivados") }
                    }
                    val visible = inventory.filter { it.material.archived == showArchived }
                    if (visible.isEmpty()) {
                        Text(if (showArchived) "No hay materiales archivados." else "Todavía no hay materiales en el catálogo.")
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visible, key = { it.material.id }) { v ->
                            Card(onClick = { onOpenMaterial(v.material.id) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(v.material.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "Nuevo: ${stockText(v.stockNew, v.material.unit)} · Usado: ${stockText(v.stockUsed, v.material.unit)}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (v.rejectedNew.signum() != 0 || v.rejectedUsed.signum() != 0) {
                                        Text(
                                            "Movimientos rechazados por el servidor: " +
                                                "nuevo ${v.rejectedNew.stripTrailingZeros().toPlainString()} · " +
                                                "usado ${v.rejectedUsed.stripTrailingZeros().toPlainString()} " +
                                                "(no cuentan en el stock mostrado)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                    if (v.material.pendingSync) {
                                        Text("Pendiente de sincronizar", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (purchases.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Card {
                                Text(
                                    "Todavía no hay compras registradas.",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(purchases, key = { it.id }) { p ->
                            Card(onClick = { onOpenPurchase(p.id) }, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp)) {
                                    Text(
                                        p.officialNumber ?: p.provisionalNumber,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            fmtDay(p.purchaseDateEpochDay) +
                                                (if (p.archived) " · Archivada" else ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f),
                                        )
                                        PurchaseStatusChip(p.status)
                                    }
                                    Text(
                                        "${p.totalAmount} ${p.currency}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (p.pendingSync) {
                                        Text("Pendiente de sincronizar", style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (movements.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Card {
                                Text(
                                    "Sin movimientos de inventario.",
                                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                        }
                    }
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(movements, key = { it.id }) { m ->
                            Card(
                                onClick = { m.purchaseId?.let(onOpenPurchase) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(
                                        materialsById[m.materialId]?.name ?: "Material",
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    // El signo de la cantidad se colorea por sentido
                                    // real del movimiento (entrada/salida).
                                    val inbound = runCatching {
                                        movementIsInbound(MovementType.valueOf(m.movementType))
                                    }.getOrDefault(true)
                                    val qtyColor = if (inbound) {
                                        androidx.compose.ui.graphics.Color(0xFF2E7D32)
                                    } else {
                                        MaterialTheme.colorScheme.error
                                    }
                                    Row {
                                        Text(
                                            "${movementOriginLabel(m.origin)} · ",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        Text(
                                            "${movementSign(m.movementType)}${m.quantity} ${m.unit}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = qtyColor,
                                        )
                                        Text(
                                            " · " + (if (m.condition == "USADO") "Usado" else "Nuevo"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    val ref = m.jobId?.let { jobsById[it]?.let { j -> j.officialNumber ?: j.provisionalNumber } }
                                    if (ref != null) {
                                        Text("Trabajo $ref", style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (m.note.isNotBlank()) {
                                        Text(m.note, style = MaterialTheme.typography.bodySmall)
                                    }
                                    latestOps[
                                        com.cotixgo.app.data.EntityTypes.INVENTORY_MOVEMENT to m.id,
                                    ]?.let { mop ->
                                        if (mop.state == "PAUSED" || mop.resultCode == "REJECTED_ACKNOWLEDGED") {
                                            Text(
                                                "Rechazado por el servidor (${mop.resultCode ?: ""}): no cuenta en el stock.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Material: formulario y detalle ----------

@Composable
fun MaterialFormDialog(
    initial: MaterialEntity?,
    onDismiss: () -> Unit,
    onSave: (MaterialInput) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var unit by remember { mutableStateOf(initial?.unit ?: "") }
    var description by remember { mutableStateOf(initial?.description ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo material" else "Editar material") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre *") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = unit, onValueChange = { unit = it },
                    label = { Text("Unidad *") }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "El stock se actualiza mediante compras y movimientos.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val issue = validateMaterial(name, unit)
                if (issue != null) error = issue else onSave(MaterialInput(name, unit, description))
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaterialDetailScreen(
    material: MaterialEntity?,
    stock: MaterialStockView?,
    movements: List<InventoryMovementEntity>,
    purchasesById: Map<String, PurchaseEntity>,
    jobsById: Map<String, JobEntity>,
    onEdit: (MaterialInput) -> Unit,
    onArchive: (Boolean) -> Unit,
    onNewMovement: (String) -> Unit,
    onOpenPurchase: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (material == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var editDialog by remember { mutableStateOf(false) }
    if (editDialog) {
        MaterialFormDialog(
            initial = material,
            onDismiss = { editDialog = false },
            onSave = { onEdit(it); editDialog = false },
        )
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(material.name) }) },
        floatingActionButton = {
            if (!material.archived) {
                Button(onClick = { onNewMovement(material.id) }) { Text("+ Movimiento") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Unidad: ${material.unit}")
            if (material.description.isNotBlank()) Text(material.description)
            if (material.archived) {
                Text("Archivado", color = MaterialTheme.colorScheme.secondary)
            }
            Text(
                "Existencias — Nuevo: ${stockText(stock?.stockNew ?: BigDecimal.ZERO, material.unit)} · Usado: ${stockText(stock?.stockUsed ?: BigDecimal.ZERO, material.unit)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { editDialog = true }) { Text("Editar") }
                OutlinedButton(onClick = { onArchive(!material.archived) }) {
                    Text(if (material.archived) "Desarchivar" else "Archivar")
                }
            }
            Text("Movimientos", style = MaterialTheme.typography.titleSmall)
            if (movements.isEmpty()) Text("Sin movimientos registrados.")
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(movements, key = { it.id }) { m ->
                    Card(
                        onClick = { m.purchaseId?.let(onOpenPurchase) },
                        enabled = m.purchaseId != null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${movementOriginLabel(m.origin)} · ${movementSign(m.movementType)}${m.quantity} ${m.unit} · ${if (m.condition == "USADO") "Usado" else "Nuevo"}",
                            )
                            val ref = when {
                                m.purchaseId != null -> purchasesById[m.purchaseId]?.let {
                                    "Compra ${it.officialNumber ?: it.provisionalNumber}"
                                }
                                m.jobId != null -> jobsById[m.jobId]?.let {
                                    "Trabajo ${it.officialNumber ?: it.provisionalNumber}"
                                }
                                else -> null
                            }
                            if (ref != null) Text(ref, style = MaterialTheme.typography.bodySmall)
                            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall)
                            if (m.pendingSync) {
                                Text("Pendiente de sincronizar", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Movimiento manual (ajuste o sobrante) ----------

@Composable
fun MovementFormDialog(
    materials: List<MaterialEntity>,
    jobs: List<JobEntity>,
    preselectedMaterialId: String?,
    onDismiss: () -> Unit,
    onSave: (InventoryMovementInput, (String?) -> Unit) -> Unit,
) {
    var materialId by remember { mutableStateOf(preselectedMaterialId ?: "") }
    var origin by remember { mutableStateOf(MovementOrigin.AJUSTE) }
    var isEntry by remember { mutableStateOf(true) }
    var condition by remember { mutableStateOf(MaterialCondition.NUEVO) }
    var qty by remember { mutableStateOf("") }
    var unitCost by remember { mutableStateOf("") }
    var jobId by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var materialMenu by remember { mutableStateOf(false) }
    var jobMenu by remember { mutableStateOf(false) }
    val material = materials.firstOrNull { it.id == materialId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Movimiento de inventario") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Ajuste", "Sobrante").forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = origin == (if (i == 0) MovementOrigin.AJUSTE else MovementOrigin.SOBRANTE),
                            onClick = { origin = if (i == 0) MovementOrigin.AJUSTE else MovementOrigin.SOBRANTE },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        ) { Text(t) }
                    }
                }
                if (origin == MovementOrigin.AJUSTE) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("Entrada", "Salida").forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = isEntry == (i == 0),
                                onClick = { isEntry = i == 0 },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            ) { Text(t) }
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { materialMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(material?.name ?: "Material *")
                    }
                    DropdownMenu(expanded = materialMenu, onDismissRequest = { materialMenu = false }) {
                        materials.forEach { m ->
                            DropdownMenuItem(
                                text = { Text("${m.name} (${m.unit})") },
                                onClick = { materialId = m.id; materialMenu = false },
                            )
                        }
                    }
                }
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Nuevo", "Usado").forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = condition == MaterialCondition.entries[i],
                            onClick = { condition = MaterialCondition.entries[i] },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        ) { Text(t) }
                    }
                }
                OutlinedTextField(
                    value = qty, onValueChange = { qty = it },
                    label = { Text("Cantidad * (${material?.unit ?: "unidad"})") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = unitCost, onValueChange = { unitCost = it },
                    label = { Text("Costo unitario (opcional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                if (origin == MovementOrigin.SOBRANTE) {
                    Text(
                        "Registra una entrada de material recuperado o sobrante; asociar un trabajo es opcional.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Box {
                        OutlinedButton(onClick = { jobMenu = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                jobId?.let { jid ->
                                    jobs.firstOrNull { it.id == jid }
                                        ?.let { "Trabajo ${it.officialNumber ?: it.provisionalNumber}" }
                                } ?: "Trabajo asociado (opcional)",
                            )
                        }
                        DropdownMenu(expanded = jobMenu, onDismissRequest = { jobMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("Sin trabajo") },
                                onClick = { jobId = null; jobMenu = false },
                            )
                            jobs.filter { !it.archived }.forEach { j ->
                                DropdownMenuItem(
                                    text = { Text(j.officialNumber ?: j.provisionalNumber) },
                                    onClick = { jobId = j.id; jobMenu = false },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Nota explicativa *") }, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                when {
                    material == null -> error = "Selecciona un material."
                    !isPositiveQuantity(qty) -> error = "La cantidad debe ser positiva."
                    note.isBlank() -> error = "El movimiento manual requiere una nota explicativa."
                    else -> onSave(
                        InventoryMovementInput(
                            materialId = material.id,
                            condition = condition,
                            movementType = when {
                                origin == MovementOrigin.SOBRANTE || isEntry -> MovementType.ADJUSTMENT_IN
                                else -> MovementType.ADJUSTMENT_OUT
                            },
                            origin = origin,
                            quantity = qty,
                            unitCost = unitCost.ifBlank { null },
                            jobId = if (origin == MovementOrigin.SOBRANTE) jobId else null,
                            note = note,
                        ),
                        { err -> if (err != null) error = err },
                    )
                }
            }) { Text("Registrar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------- Compra: lista implícita en InventoryScreen; editor y detalle ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseEditorScreen(
    initial: PurchaseEntity?,
    initialItems: List<PurchaseItemEntity>,
    materials: List<MaterialEntity>,
    confirmError: String?,
    currency: String,
    onSave: (PurchaseInput, Boolean, (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    data class LineState(
        var materialId: String,
        var quantity: String,
        var unit: String,
        var unitPrice: String,
        var condition: MaterialCondition,
    )

    val isEdit = initial != null
    var dateText by remember {
        mutableStateOf(
            initial?.purchaseDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() }
                ?: LocalDate.now().toString(),
        )
    }
    var supplier by remember { mutableStateOf(initial?.supplierPlace ?: "") }
    var reference by remember { mutableStateOf(initial?.reference ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val lines = remember {
        initialItems.map {
            LineState(it.materialId, it.quantity, it.unit, it.unitPrice,
                runCatching { MaterialCondition.valueOf(it.condition) }.getOrDefault(MaterialCondition.NUEVO))
        }.toMutableStateList()
    }
    var error by remember { mutableStateOf<String?>(null) }

    fun buildInput(): PurchaseInput? {
        val day = runCatching { LocalDate.parse(dateText.trim()).toEpochDay() }.getOrNull()
            ?: run { error = "La fecha debe ser AAAA-MM-DD."; return null }
        return PurchaseInput(
            purchaseDateEpochDay = day,
            items = lines.map {
                PurchaseLineInput(it.materialId, it.quantity, it.unit, it.unitPrice, it.condition)
            },
            supplierPlace = supplier, reference = reference, notes = notes,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (isEdit) "Editar compra" else "Nueva compra") })
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                OutlinedTextField(
                    value = dateText, onValueChange = { dateText = it },
                    label = { Text("Fecha (AAAA-MM-DD) *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = supplier, onValueChange = { supplier = it },
                    label = { Text("Proveedor / lugar (opcional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = reference, onValueChange = { reference = it },
                    label = { Text("Referencia (opcional)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item { Text("Líneas", style = MaterialTheme.typography.titleSmall) }
            items(lines.size) { i ->
                val line = lines[i]
                var menu by remember { mutableStateOf(false) }
                val mat = materials.firstOrNull { it.id == line.materialId }
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            OutlinedButton(onClick = { menu = true }, modifier = Modifier.fillMaxWidth()) {
                                Text(mat?.name ?: "Material *")
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                materials.forEach { m ->
                                    DropdownMenuItem(
                                        text = { Text("${m.name} (${m.unit})") },
                                        onClick = {
                                            lines[i] = line.copy(
                                                materialId = m.id,
                                                unit = if (line.unit.isBlank()) m.unit else line.unit,
                                            )
                                            menu = false
                                        },
                                    )
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = line.quantity,
                                onValueChange = { lines[i] = line.copy(quantity = it) },
                                label = { Text("Cantidad") },
                                modifier = Modifier.weight(1f), singleLine = true,
                            )
                            OutlinedTextField(
                                value = line.unit,
                                onValueChange = { lines[i] = line.copy(unit = it) },
                                label = { Text("Unidad") },
                                modifier = Modifier.weight(1f), singleLine = true,
                            )
                        }
                        OutlinedTextField(
                            value = line.unitPrice,
                            onValueChange = { lines[i] = line.copy(unitPrice = it) },
                            label = { Text("Precio unitario") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf("Nuevo", "Usado").forEachIndexed { c, t ->
                                SegmentedButton(
                                    selected = line.condition == MaterialCondition.entries[c],
                                    onClick = {
                                        lines[i] = line.copy(condition = MaterialCondition.entries[c])
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = c, count = 2),
                                ) { Text(t) }
                            }
                        }
                        TextButton(onClick = { lines.removeAt(i) }) { Text("Quitar línea") }
                    }
                }
            }
            item {
                OutlinedButton(
                    onClick = {
                        lines.add(LineState(materials.firstOrNull()?.id ?: "", "", "", "", MaterialCondition.NUEVO))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("+ Línea") }
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notas") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                // Moneda de la compra: la principal del perfil (no editable
                // en el formulario — `savePurchase` la toma del usuario).
                Text(
                    "Moneda: $currency (moneda principal del perfil)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    "Un borrador no afecta existencias; solo la compra confirmada actualiza el stock.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                confirmError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val input = buildInput() ?: return@Button
                        onSave(input, false) { if (it != null) error = it }
                    }) { Text("Guardar") }
                    if (!isEdit || initial.status == PurchaseStatus.BORRADOR.name) {
                        OutlinedButton(onClick = {
                            val input = buildInput() ?: return@OutlinedButton
                            if (input.items.isEmpty()) {
                                error = "Confirmar la compra requiere al menos una línea."
                                return@OutlinedButton
                            }
                            onSave(input, true) { if (it != null) error = it }
                        }) { Text("Guardar y confirmar") }
                    }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseDetailScreen(
    purchase: PurchaseEntity?,
    items: List<PurchaseItemEntity>,
    movements: List<InventoryMovementEntity>,
    materialsById: Map<String, MaterialEntity>,
    actionError: String?,
    onEdit: () -> Unit,
    onConfirm: () -> Unit,
    onAnnul: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    if (purchase == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val status = runCatching { PurchaseStatus.valueOf(purchase.status) }
        .getOrDefault(PurchaseStatus.BORRADOR)
    val editable = PurchaseTransitions.isEditable(status) && !purchase.archived
    var confirmAnnul by remember { mutableStateOf(false) }

    if (confirmAnnul) {
        AlertDialog(
            onDismissRequest = { confirmAnnul = false },
            title = { Text("Anular compra") },
            text = {
                Text(
                    "La anulación es terminal e irreversible" +
                        if (status == PurchaseStatus.CONFIRMADA)
                            ": se registrarán los movimientos inversos de inventario."
                        else ".",
                )
            },
            confirmButton = {
                Button(onClick = { confirmAnnul = false; onAnnul() }) { Text("Anular") }
            },
            dismissButton = { TextButton(onClick = { confirmAnnul = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(purchase.officialNumber ?: purchase.provisionalNumber) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(purchaseStatusLabel(purchase.status), style = MaterialTheme.typography.titleMedium)
            Text("Fecha: ${LocalDate.ofEpochDay(purchase.purchaseDateEpochDay)}")
            if (purchase.supplierPlace.isNotBlank()) Text("Proveedor/lugar: ${purchase.supplierPlace}")
            if (purchase.reference.isNotBlank()) Text("Referencia: ${purchase.reference}")
            if (purchase.notes.isNotBlank()) Text("Notas: ${purchase.notes}")
            if (purchase.archived) Text("Archivada", color = MaterialTheme.colorScheme.secondary)
            Text("Total: ${purchase.totalAmount} ${purchase.currency}",
                style = MaterialTheme.typography.titleSmall)

            Text("Líneas", style = MaterialTheme.typography.titleSmall)
            if (items.isEmpty()) Text("Sin líneas.")
            items.forEach { it ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(it.materialNameSnapshot.ifBlank {
                            materialsById[it.materialId]?.name ?: "Material"
                        })
                        Text(
                            "${it.quantity} ${it.unit} × ${it.unitPrice} = ${it.lineTotal} ${purchase.currency} · ${if (it.condition == "USADO") "Usado" else "Nuevo"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (movements.isNotEmpty()) {
                Text("Movimientos de inventario", style = MaterialTheme.typography.titleSmall)
                movements.forEach { m ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                "${movementOriginLabel(m.origin)} · ${movementSign(m.movementType)}${m.quantity} ${m.unit} · ${materialsById[m.materialId]?.name ?: ""} · ${if (m.condition == "USADO") "Usado" else "Nuevo"}",
                            )
                            if (m.note.isNotBlank()) {
                                Text(m.note, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }

            actionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (editable) {
                    OutlinedButton(onClick = onEdit) { Text("Editar") }
                    if (status == PurchaseStatus.BORRADOR && items.isNotEmpty()) {
                        Button(onClick = onConfirm) { Text("Confirmar") }
                    }
                }
                if (status != PurchaseStatus.ANULADA) {
                    OutlinedButton(onClick = { confirmAnnul = true }) { Text("Anular") }
                }
                OutlinedButton(onClick = { onArchive(!purchase.archived) }) {
                    Text(if (purchase.archived) "Desarchivar" else "Archivar")
                }
            }
            TextButton(onClick = onBack) { Text("Volver") }
        }
    }
}

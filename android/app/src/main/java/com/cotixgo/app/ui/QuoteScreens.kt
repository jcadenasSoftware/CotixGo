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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.effectiveQuoteStatus
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
import com.cotixgo.app.data.QuoteSnapshot
import com.cotixgo.app.data.QuoteVersionEntity
import com.cotixgo.app.data.RetentionConfigEntity
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.ServiceEntity
import com.cotixgo.app.data.ServiceInput
import com.cotixgo.app.data.UserEntity
import java.time.LocalDate

private fun label(status: QuoteStatus): String = when (status) {
    QuoteStatus.BORRADOR -> "Borrador"
    QuoteStatus.EMITIDA -> "Emitida"
    QuoteStatus.APROBADA -> "Aprobada"
    QuoteStatus.RECHAZADA -> "Rechazada"
    QuoteStatus.VENCIDA -> "Vencida"
    QuoteStatus.CANCELADA -> "Cancelada"
}

private fun QuoteEntity.effectiveStatus(today: Long = LocalDate.now().toEpochDay()): QuoteStatus =
    effectiveQuoteStatus(QuoteStatus.valueOf(status), expiryDateEpochDay, today)

// ---------- Lista de Cotizaciones ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesListScreen(
    quotes: List<QuoteEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) } // 0 Abiertas, 1 Cerradas, 2 Archivadas
    val visible = quotes.filter {
        if (tab == 2) it.archived else !it.archived && when (tab) {
            0 -> it.effectiveStatus() in setOf(QuoteStatus.BORRADOR, QuoteStatus.EMITIDA, QuoteStatus.RECHAZADA)
            else -> it.effectiveStatus() in setOf(QuoteStatus.APROBADA, QuoteStatus.VENCIDA, QuoteStatus.CANCELADA)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cotizaciones") }) },
        floatingActionButton = { Button(onClick = onNew) { Text("+ Cotización") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Abiertas", "Cerradas", "Archivadas").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(t) }
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No hay cotizaciones en esta vista.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { q ->
                        Card(onClick = { onOpen(q.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(q.provisionalNumber, style = MaterialTheme.typography.titleMedium)
                                Text(q.clientNameSnapshot, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "${label(q.effectiveStatus())} · ${q.currency} ${q.net}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Editor de Cotización ----------

class LineDraft {
    var conceptType by mutableStateOf(ConceptType.SERVICIO)
    var description by mutableStateOf("")
    var quantity by mutableStateOf("1")
    var unit by mutableStateOf("")
    var unitPrice by mutableStateOf("")
    var serviceRef by mutableStateOf<String?>(null)
    var materialOrigin by mutableStateOf<MaterialOrigin?>(null)
    var saveToCatalog by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteEditorScreen(
    initial: QuoteSnapshot?,
    clients: List<ClientEntity>,
    profile: ProfileEntity?,
    user: UserEntity?,
    retentions: List<RetentionConfigEntity>,
    services: List<ServiceEntity>,
    onSave: (QuoteInput, List<ServiceInput>) -> Unit,
    onBack: () -> Unit,
) {
    var clientId by remember { mutableStateOf(initial?.client?.id ?: "") }
    var validity by remember { mutableStateOf(initial?.validityDays?.toString() ?: (profile?.vigenciaDias?.toString() ?: "")) }
    var currency by remember { mutableStateOf(initial?.currency ?: (user?.mainCurrency ?: "")) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val lines = remember {
        mutableStateListOf<LineDraft>().apply {
            initial?.lines?.forEach { l ->
                add(LineDraft().apply {
                    conceptType = ConceptType.valueOf(l.conceptType)
                    description = l.description
                    quantity = l.quantity
                    unit = l.unit
                    unitPrice = l.unitPrice
                    serviceRef = l.serviceRef
                    materialOrigin = l.materialOrigin?.let(MaterialOrigin::valueOf)
                })
            }
        }
    }
    val selectedRetentions = remember {
        mutableStateListOf<String>().apply {
            initial?.retentions?.forEach { r -> add(r.name) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }
    var lineDialog by remember { mutableStateOf<LineDraft?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var catalogDialog by remember { mutableStateOf(false) }
    var targetNet by remember { mutableStateOf("") }
    var inverseResult by remember { mutableStateOf<String?>(null) }

    val issueDay = initial?.issueDate?.let { LocalDate.parse(it).toEpochDay() }
        ?: LocalDate.now().toEpochDay()
    val validityDays = validity.toIntOrNull() ?: 0
    val expiryDay = issueDay + validityDays - 1

    val calcLines = lines.map {
        com.cotixgo.app.core.CalcLine(it.conceptType, it.quantity.toQty(), it.unitPrice.toMoney())
    }
    val calcSpecs = retentions.filter { it.name in selectedRetentions }.map {
        com.cotixgo.app.core.RetentionSpec(
            it.name, it.ratePercent.toQty(),
            it.appliesTo.split(",").map(com.cotixgo.app.core.ConceptType::valueOf).toSet(),
        )
    }
    val calc = QuoteCalc.compute(calcLines, calcSpecs)

    if (lineDialog != null) {
        val draft = lineDialog!!
        AlertDialog(
            onDismissRequest = { lineDialog = null },
            title = { Text(if (editIndex >= 0) "Editar línea" else "Nueva línea") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        ConceptType.entries.forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = draft.conceptType == t,
                                onClick = { draft.conceptType = t },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                            ) { Text(t.name.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                    OutlinedTextField(
                        value = draft.description, onValueChange = { draft.description = it },
                        label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.quantity, onValueChange = { draft.quantity = it },
                        label = { Text("Cantidad") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = draft.unit, onValueChange = { draft.unit = it },
                        label = { Text("Unidad") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = draft.unitPrice, onValueChange = { draft.unitPrice = it },
                        label = { Text("Precio bruto") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    if (draft.conceptType == ConceptType.MATERIAL) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf("Del cliente", "Propio").forEachIndexed { i, t ->
                                SegmentedButton(
                                    selected = draft.materialOrigin == MaterialOrigin.entries[i],
                                    onClick = { draft.materialOrigin = MaterialOrigin.entries[i] },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                                ) { Text(t) }
                            }
                        }
                    }
                    if (draft.conceptType == ConceptType.SERVICIO && draft.serviceRef == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.saveToCatalog, onCheckedChange = { draft.saveToCatalog = it })
                            Text("Guardar en el catálogo")
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (editIndex >= 0) lines[editIndex] = draft else lines.add(draft)
                    lineDialog = null
                    editIndex = -1
                }) { Text("Aceptar") }
            },
            dismissButton = { TextButton(onClick = { lineDialog = null; editIndex = -1 }) { Text("Cancelar") } },
        )
    }

    if (catalogDialog) {
        val active = services.filter { !it.archived }
        AlertDialog(
            onDismissRequest = { catalogDialog = false },
            title = { Text("Servicio del catálogo") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (active.isEmpty()) Text("El catálogo no tiene servicios activos.")
                    active.forEach { s ->
                        TextButton(onClick = {
                            lines.add(LineDraft().apply {
                                conceptType = ConceptType.SERVICIO
                                description = s.name
                                unitPrice = s.suggestedPrice
                                serviceRef = s.id
                            })
                            catalogDialog = false
                        }) { Text(s.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { catalogDialog = false }) { Text("Cerrar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (initial == null) "Nueva cotización" else "Editar cotización") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    OutlinedButton(onClick = { clientMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *",
                        )
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        clients.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { clientId = c.id; clientMenu = false })
                        }
                    }
                }
            }
            item { Text("Perfil: ${profile?.businessName ?: "—"}", style = MaterialTheme.typography.bodySmall) }
            item { Text("Emisión: ${LocalDate.ofEpochDay(issueDay)}", style = MaterialTheme.typography.bodySmall) }
            item {
                OutlinedTextField(
                    value = validity, onValueChange = { validity = it.filter(Char::isDigit) },
                    label = { Text("Vigencia (días) *") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (validityDays > 0) {
                    Text("Vence: ${LocalDate.ofEpochDay(expiryDay)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Moneda (ISO 4217) *") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lineDialog = LineDraft() }) { Text("+ Línea manual") }
                    OutlinedButton(onClick = { catalogDialog = true }) { Text("Desde catálogo") }
                }
            }
            items(lines.size) { i ->
                val l = lines[i]
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("[${l.conceptType.name}] ${l.description.ifBlank { "(sin descripción)" }}")
                        Text(
                            "${l.quantity} ${l.unit} × ${l.unitPrice} = ${QuoteCalc.format(l.quantity.toQty().multiply(l.unitPrice.toMoney()))}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editIndex = i; lineDialog = l }) { Text("Editar") }
                            TextButton(onClick = { lines.removeAt(i) }) { Text("Quitar") }
                        }
                    }
                }
            }
            if (retentions.any { !it.archived }) {
                item {
                    Text("Retenciones", style = MaterialTheme.typography.titleSmall)
                    retentions.filter { !it.archived }.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = r.name in selectedRetentions,
                                onCheckedChange = {
                                    if (it) selectedRetentions.add(r.name) else selectedRetentions.remove(r.name)
                                },
                            )
                            Text("${r.name} (${r.ratePercent}%)")
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bruto: ${QuoteCalc.format(calc.gross)}")
                    calc.retentions.forEach { Text("${it.name} (${it.ratePercent}%): -${QuoteCalc.format(it.amount)}", style = MaterialTheme.typography.bodySmall) }
                    Text("Total retenido: ${QuoteCalc.format(calc.totalRetained)}", style = MaterialTheme.typography.bodySmall)
                    Text("Neto: ${QuoteCalc.format(calc.net)}", style = MaterialTheme.typography.titleSmall)
                }
            }
            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = targetNet, onValueChange = { targetNet = it },
                        label = { Text("Neto objetivo") },
                        modifier = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedButton(onClick = {
                        val serviceRates = calcSpecs.filter { ConceptType.SERVICIO in it.appliesTo }.map { it.ratePercent }
                        inverseResult = QuoteCalc.requiredGrossForNet(targetNet.toMoney(), serviceRates)
                            ?.let { "Bruto requerido: ${QuoteCalc.format(it)}" }
                            ?: "No se puede calcular (tasas >= 100%)"
                    }) { Text("Inverso") }
                }
                inverseResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Condiciones / observaciones") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        when {
                            clientId.isBlank() -> error = "El cliente es obligatorio."
                            validityDays <= 0 -> error = "La vigencia en días es obligatoria."
                            currency.length != 3 -> error = "La moneda debe ser un código ISO 4217."
                            lines.any { it.description.isBlank() } -> error = "Toda línea necesita descripción."
                            else -> {
                                error = null
                                onSave(
                                    QuoteInput(
                                        clientId = clientId,
                                        profileId = profile?.id ?: "",
                                        issueDateEpochDay = issueDay,
                                        validityDays = validityDays,
                                        currency = currency,
                                        notes = notes,
                                        lines = lines.map {
                                            QuoteLineInput(
                                                conceptType = it.conceptType,
                                                description = it.description,
                                                quantity = it.quantity,
                                                unit = it.unit,
                                                unitPrice = it.unitPrice,
                                                serviceRef = it.serviceRef,
                                                materialOrigin = it.materialOrigin,
                                            )
                                        },
                                        retentions = retentions.filter { it.name in selectedRetentions }.map {
                                            RetentionInput(
                                                name = it.name,
                                                ratePercent = it.ratePercent,
                                                appliesTo = it.appliesTo.split(",").map(ConceptType::valueOf).toSet(),
                                            )
                                        },
                                    ),
                                    lines.filter { it.saveToCatalog }.map {
                                        ServiceInput(
                                            profileId = profile?.id ?: "",
                                            name = it.description,
                                            suggestedPrice = it.unitPrice,
                                        )
                                    },
                                )
                            }
                        }
                    }) { Text("Guardar") }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

// ---------- Detalle de Cotización ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteDetailScreen(
    quote: QuoteEntity?,
    versions: List<QuoteVersionEntity>,
    latestSnapshot: QuoteSnapshot?,
    emitFailed: Boolean,
    onEmit: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRevertReject: () -> Unit,
    onCancel: () -> Unit,
    onRevalidate: (Int) -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onOpenVersion: (Int) -> Unit,
    onBack: () -> Unit,
) {
    if (quote == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val effective = quote.effectiveStatus()
    var revalidateDialog by remember { mutableStateOf(false) }
    var revalidateDays by remember { mutableStateOf(latestSnapshot?.validityDays?.toString() ?: "") }

    if (revalidateDialog) {
        AlertDialog(
            onDismissRequest = { revalidateDialog = false },
            title = { Text("Revalidar cotización") },
            text = {
                Column {
                    Text("Se crea una cotización nueva a partir de esta. La vencida permanece intacta.")
                    OutlinedTextField(
                        value = revalidateDays, onValueChange = { revalidateDays = it.filter(Char::isDigit) },
                        label = { Text("Vigencia (días) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    (revalidateDays.toIntOrNull() ?: 0).let { if (it > 0) { revalidateDialog = false; onRevalidate(it) } }
                }) { Text("Revalidar") }
            },
            dismissButton = { TextButton(onClick = { revalidateDialog = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(quote.provisionalNumber) }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("${label(effective)} · versión ${quote.currentVersion}", style = MaterialTheme.typography.titleMedium)
                Text("${quote.currency} ${quote.net} neto", style = MaterialTheme.typography.titleSmall)
            }
            latestSnapshot?.let { s ->
                item {
                    Text("Cliente: ${s.client.name}")
                    Text("Perfil: ${s.profile.businessName}", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Emisión ${s.issueDate} · vigencia ${s.validityDays} días · vence ${s.expiryDate}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                items(s.lines) { l ->
                    Text(
                        "[${l.conceptType}] ${l.description} — ${l.quantity} ${l.unit} × ${l.unitPrice} = ${l.lineTotal}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                item {
                    Text("Bruto: ${s.gross}")
                    s.retentions.forEach { Text("${it.name} (${it.ratePercent}%): -${it.amount}", style = MaterialTheme.typography.bodySmall) }
                    Text("Neto: ${s.net}", style = MaterialTheme.typography.titleSmall)
                }
            }
            if (emitFailed) {
                item { Text("La cotización necesita al menos una línea para emitirse.", color = MaterialTheme.colorScheme.error) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (effective) {
                        QuoteStatus.BORRADOR -> {
                            Button(onClick = onEmit) { Text("Emitir") }
                            OutlinedButton(onClick = onEdit) { Text("Editar") }
                            OutlinedButton(onClick = onCancel) { Text("Cancelar cotización") }
                        }
                        QuoteStatus.EMITIDA -> {
                            Button(onClick = onApprove) { Text("Aprobar") }
                            OutlinedButton(onClick = onReject) { Text("Rechazar") }
                            OutlinedButton(onClick = onEdit) { Text("Editar") }
                            OutlinedButton(onClick = onCancel) { Text("Cancelar cotización") }
                        }
                        QuoteStatus.RECHAZADA -> {
                            Button(onClick = onRevertReject) { Text("Revertir a emitida") }
                        }
                        QuoteStatus.VENCIDA -> {
                            Button(onClick = { revalidateDialog = true }) { Text("Revalidar") }
                        }
                        else -> {}
                    }
                    OutlinedButton(onClick = { onArchive(!quote.archived) }) {
                        Text(if (quote.archived) "Desarchivar" else "Archivar")
                    }
                    TextButton(onClick = onBack) { Text("Volver") }
                }
            }
            if (versions.isNotEmpty()) {
                item {
                    Text("Versiones", style = MaterialTheme.typography.titleSmall)
                    versions.forEach { v ->
                        TextButton(onClick = { onOpenVersion(v.version) }) {
                            Text("Versión ${v.version}" + if (v.version == quote.emittedVersion) " (emitida)" else "")
                        }
                    }
                }
            }
        }
    }
}

// ---------- Vista de una versión (histórico inmutable) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteVersionScreen(snapshot: QuoteSnapshot?, onBack: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(snapshot?.let { "${it.provisionalNumber} v${it.version}" } ?: "Versión") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot?.let { s ->
                item { Text("Cliente: ${s.client.name}") }
                item { Text("Perfil: ${s.profile.businessName}", style = MaterialTheme.typography.bodySmall) }
                item { Text("Emisión ${s.issueDate} · vence ${s.expiryDate} · ${s.currency}") }
                items(s.lines) { l ->
                    Text("[${l.conceptType}] ${l.description} — ${l.quantity} ${l.unit} × ${l.unitPrice} = ${l.lineTotal}")
                }
                item { Text("Bruto: ${s.gross} · Retenido: ${s.totalRetained} · Neto: ${s.net}") }
            }
            item { TextButton(onClick = onBack) { Text("Volver") } }
        }
    }
}

// ---------- Catálogo de servicios ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    services: List<ServiceEntity>,
    onSave: (ServiceInput, String?) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var dialog by remember { mutableStateOf<ServiceEntity?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val visible = services.filter { it.archived == showArchived }

    if (showDialog) {
        var name by remember { mutableStateOf(dialog?.name ?: "") }
        var desc by remember { mutableStateOf(dialog?.description ?: "") }
        var price by remember { mutableStateOf(dialog?.suggestedPrice ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialog = null },
            title = { Text(if (dialog == null) "Nuevo servicio" else "Editar servicio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre *") })
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it }, label = { Text("Precio sugerido") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onSave(ServiceInput(profileId = "", name = name, description = desc, suggestedPrice = price), dialog?.id)
                        showDialog = false; dialog = null
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false; dialog = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Catálogo de servicios") }) },
        floatingActionButton = { Button(onClick = { showDialog = true }) { Text("+ Servicio") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Activos", "Archivados").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = showArchived == (i == 1), onClick = { showArchived = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(t) }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { s ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name)
                            if (s.suggestedPrice.isNotBlank()) Text("Precio sugerido: ${s.suggestedPrice}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = s; showDialog = true }) { Text("Editar") }
                                TextButton(onClick = { onArchive(s.id, !s.archived) }) {
                                    Text(if (s.archived) "Desarchivar" else "Archivar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Configuración de retenciones (global Usuario/Titular) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetentionsScreen(
    retentions: List<RetentionConfigEntity>,
    onSave: (RetentionInput, String?) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<RetentionConfigEntity?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val visible = retentions.filter { it.archived == showArchived }

    if (showDialog) {
        var name by remember { mutableStateOf(dialog?.name ?: "") }
        var rate by remember { mutableStateOf(dialog?.ratePercent ?: "") }
        val applies = remember {
            mutableStateListOf<ConceptType>().apply {
                dialog?.appliesTo?.split(",")?.map(ConceptType::valueOf)?.let { addAll(it) }
                    ?: add(ConceptType.SERVICIO)
            }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialog = null },
            title = { Text(if (dialog == null) "Nueva retención" else "Editar retención") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre *") })
                    OutlinedTextField(
                        value = rate, onValueChange = { rate = it }, label = { Text("Tasa % *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text("Aplica a", style = MaterialTheme.typography.titleSmall)
                    ConceptType.entries.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = t in applies,
                                onCheckedChange = { if (it) applies.add(t) else applies.remove(t) },
                            )
                            Text(t.name)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && rate.toBigDecimalOrNull() != null && applies.isNotEmpty()) {
                        onSave(RetentionInput(name = name, ratePercent = rate, appliesTo = applies.toSet()), dialog?.id)
                        showDialog = false; dialog = null
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false; dialog = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Retenciones") }) },
        floatingActionButton = { Button(onClick = { showDialog = true }) { Text("+ Retención") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Activas", "Archivadas").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = showArchived == (i == 1), onClick = { showArchived = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(t) }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${r.name} — ${r.ratePercent}% sobre ${r.appliesTo}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = r; showDialog = true }) { Text("Editar") }
                                TextButton(onClick = { onArchive(r.id, !r.archived) }) {
                                    Text(if (r.archived) "Desarchivar" else "Archivar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Perfil Profesional ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: ProfileEntity?,
    onSave: (String, String, String, Int?) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(profile?.businessName ?: "") }
    var desc by remember { mutableStateOf(profile?.description ?: "") }
    var info by remember { mutableStateOf(profile?.commercialInfo ?: "") }
    var vigencia by remember { mutableStateOf(profile?.vigenciaDias?.toString() ?: "") }

    Scaffold(topBar = { TopAppBar(title = { Text("Perfil profesional") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre comercial *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = info, onValueChange = { info = it }, label = { Text("Información comercial") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = vigencia, onValueChange = { vigencia = it.filter(Char::isDigit) },
                label = { Text("Vigencia predeterminada de cotizaciones (días)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    if (name.isNotBlank()) onSave(name, desc, info, vigencia.toIntOrNull())
                }) { Text("Guardar") }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

// ---------- Ajustes ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: UserEntity?,
    onCurrency: (String) -> Unit,
    onServices: () -> Unit,
    onRetentions: () -> Unit,
    onProfile: () -> Unit,
    onBack: () -> Unit,
) {
    var currency by remember { mutableStateOf(user?.mainCurrency ?: "") }
    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = currency, onValueChange = { currency = it.uppercase().take(3) },
                label = { Text("Moneda principal (ISO 4217)") }, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { if (currency.length == 3) onCurrency(currency) }) { Text("Guardar moneda") }
            OutlinedButton(onClick = onServices, modifier = Modifier.fillMaxWidth()) { Text("Catálogo de servicios") }
            OutlinedButton(onClick = onRetentions, modifier = Modifier.fillMaxWidth()) { Text("Retenciones") }
            OutlinedButton(onClick = onProfile, modifier = Modifier.fillMaxWidth()) { Text("Perfil profesional") }
            TextButton(onClick = onBack) { Text("Volver") }
        }
    }
}

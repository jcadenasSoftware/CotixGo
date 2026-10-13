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
import com.cotixgo.app.core.CalcLine
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.PaymentAssignmentStatus
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.RetentionSpec
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.CollectionAccountInput
import com.cotixgo.app.data.CollectionAccountItemEntity
import com.cotixgo.app.data.CollectionItemInput
import com.cotixgo.app.data.CollectionMethodEntity
import com.cotixgo.app.data.CollectionMethodInput
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.PaymentAssignmentEntity
import com.cotixgo.app.data.PaymentEntity
import com.cotixgo.app.data.PaymentInput
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.RetentionConfigEntity
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.UserEntity
import java.time.LocalDate

private fun collectionLabel(status: CollectionAccountStatus): String = when (status) {
    CollectionAccountStatus.PENDIENTE -> "Pendiente"
    CollectionAccountStatus.PARCIAL -> "Parcial"
    CollectionAccountStatus.PAGADA -> "Pagada"
    CollectionAccountStatus.CANCELADA -> "Cancelada"
}

private fun assignmentLabel(status: PaymentAssignmentStatus): String = when (status) {
    PaymentAssignmentStatus.UNASSIGNED -> "Sin asignar"
    PaymentAssignmentStatus.PARTIAL_ASSIGNED -> "Parcialmente asignado"
    PaymentAssignmentStatus.ASSIGNED -> "Asignado"
}

private fun parseIsoDay(text: String): Long? =
    runCatching { LocalDate.parse(text.trim()).toEpochDay() }.getOrNull()

// ---------- Cobros: Cuentas / Pagos / Métodos ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    accounts: List<CollectionAccountEntity>,
    payments: List<PaymentEntity>,
    methods: List<CollectionMethodEntity>,
    clients: List<ClientEntity>,
    accountsById: Map<String, CollectionAccountEntity>,
    onOpenAccount: (String) -> Unit,
    onOpenPayment: (String) -> Unit,
    onNewAccount: () -> Unit,
    onNewPayment: () -> Unit,
    onSaveMethod: (CollectionMethodInput, String?) -> Unit,
    onSetMethodArchived: (String, Boolean) -> Unit,
) {
    var tab by remember { mutableStateOf(0) } // 0 Cuentas, 1 Pagos, 2 Métodos
    var methodDialog by remember { mutableStateOf<CollectionMethodEntity?>(null) }
    var newMethod by remember { mutableStateOf(false) }

    if (methodDialog != null || newMethod) {
        CollectionMethodDialog(
            initial = methodDialog,
            onDismiss = { methodDialog = null; newMethod = false },
            onSave = { input, id ->
                onSaveMethod(input, id)
                methodDialog = null; newMethod = false
            },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Cobros") }) },
        floatingActionButton = {
            when (tab) {
                0 -> Button(onClick = onNewAccount) { Text("+ Cuenta") }
                1 -> Button(onClick = onNewPayment) { Text("+ Pago") }
                else -> Button(onClick = { newMethod = true }) { Text("+ Método") }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Cuentas", "Pagos", "Métodos").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(t) }
                }
            }
            when (tab) {
                0 -> {
                    val visible = accounts.filter { !it.archived }
                    if (visible.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No hay cuentas de cobro.")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(visible, key = { it.id }) { a ->
                                Card(onClick = { onOpenAccount(a.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            a.officialNumber ?: a.provisionalNumber,
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(a.clientNameSnapshot, style = MaterialTheme.typography.bodySmall)
                                        Text(
                                            "${collectionLabel(CollectionAccountStatus.valueOf(a.status))} · " +
                                                "Saldo ${a.currency} ${a.balance} de ${a.net}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                1 -> {
                    if (payments.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No hay pagos registrados.")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(payments, key = { it.id }) { pay ->
                                Card(onClick = { onOpenPayment(pay.id) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            "${pay.amount} · ${LocalDate.ofEpochDay(pay.paidAtEpochDay)}",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            clients.firstOrNull { it.id == pay.clientId }?.name ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        val dest = pay.assignedAccountId?.let { aid ->
                                            accountsById[aid]?.let { it.officialNumber ?: it.provisionalNumber }
                                        }
                                        Text(
                                            assignmentLabel(PaymentAssignmentStatus.valueOf(pay.assignmentStatus)) +
                                                (dest?.let { " · $it" } ?: "") +
                                                if (pay.unassignedAmount.toMoney().signum() > 0) {
                                                    " · Sin asignar: ${pay.unassignedAmount}"
                                                } else "",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    if (methods.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No hay métodos de cobro.")
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(methods, key = { it.id }) { m ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(
                                            m.name + if (m.archived) " (archivado)" else "",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        if (m.detail.isNotBlank()) {
                                            Text(m.detail, style = MaterialTheme.typography.bodySmall)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            TextButton(onClick = { methodDialog = m }) { Text("Editar") }
                                            TextButton(onClick = { onSetMethodArchived(m.id, !m.archived) }) {
                                                Text(if (m.archived) "Desarchivar" else "Archivar")
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CollectionMethodDialog(
    initial: CollectionMethodEntity?,
    onDismiss: () -> Unit,
    onSave: (CollectionMethodInput, String?) -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var detail by remember { mutableStateOf(initial?.detail ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Nuevo método de cobro" else "Editar método") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Nombre *") }, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = detail, onValueChange = { detail = it },
                    label = { Text("Detalle (opcional)") }, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                if (name.isBlank()) {
                    error = "El nombre es obligatorio."
                } else {
                    onSave(CollectionMethodInput(name = name, detail = detail), initial?.id)
                }
            }) { Text("Guardar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

// ---------- Editor de Cuenta de Cobro ----------

private class CollectionLineDraft {
    var conceptType by mutableStateOf(ConceptType.SERVICIO)
    var description by mutableStateOf("")
    var quantity by mutableStateOf("1")
    var unit by mutableStateOf("")
    var unitPrice by mutableStateOf("")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionAccountEditorScreen(
    initial: CollectionAccountEntity?,
    initialItems: List<CollectionAccountItemEntity>,
    initialJobIds: List<String>,
    initialQuoteIds: List<String>,
    clients: List<ClientEntity>,
    profile: ProfileEntity?,
    user: UserEntity?,
    jobs: List<JobEntity>,
    quotes: List<QuoteEntity>,
    takenJobIds: Set<String>,
    takenQuoteIds: Set<String>,
    retentions: List<RetentionConfigEntity>,
    initialRetentions: List<String>,
    onSave: (CollectionAccountInput) -> Unit,
    onBack: () -> Unit,
) {
    var clientId by remember { mutableStateOf(initial?.clientId ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: (user?.mainCurrency ?: "")) }
    var dueDate by remember { mutableStateOf(initial?.dueDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "") }
    var conditions by remember { mutableStateOf(initial?.conditions ?: "") }
    // Origen: Trabajos o Cotizaciones (nunca ambos — la Cuenta no mezcla).
    var sourceMode by remember {
        mutableStateOf(if (initialQuoteIds.isNotEmpty()) "quotes" else "jobs")
    }
    val selectedJobs = remember { mutableStateListOf<String>().apply { addAll(initialJobIds) } }
    val selectedQuotes = remember { mutableStateListOf<String>().apply { addAll(initialQuoteIds) } }
    val lines = remember {
        mutableStateListOf<CollectionLineDraft>().apply {
            initialItems.forEach { l ->
                add(CollectionLineDraft().apply {
                    conceptType = ConceptType.valueOf(l.conceptType)
                    description = l.description
                    quantity = l.quantity
                    unit = l.unit
                    unitPrice = l.unitPrice
                })
            }
        }
    }
    val selectedRetentions = remember {
        mutableStateListOf<String>().apply { addAll(initialRetentions) }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }
    var sourceMenu by remember { mutableStateOf(false) }
    var lineDialog by remember { mutableStateOf<CollectionLineDraft?>(null) }
    var editIndex by remember { mutableStateOf(-1) }

    val profileId = profile?.id ?: ""
    val eligibleJobs = jobs.filter {
        it.clientId == clientId && it.profileId == profileId &&
            !it.archived && (it.id !in takenJobIds || it.id in selectedJobs)
    }
    val eligibleQuotes = quotes.filter {
        it.clientId == clientId && it.profileId == profileId &&
            it.status == QuoteStatus.APROBADA.name && !it.archived &&
            (it.id !in takenQuoteIds || it.id in selectedQuotes)
    }

    val calcLines = lines.map { CalcLine(it.conceptType, it.quantity.toQty(), it.unitPrice.toMoney()) }
    val calcSpecs = retentions.filter { it.name in selectedRetentions }.map {
        RetentionSpec(
            it.name, it.ratePercent.toQty(),
            it.appliesTo.split(",").map(ConceptType::valueOf).toSet(),
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
                        label = { Text("Precio") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Nueva cuenta de cobro" else "Editar cuenta") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    OutlinedButton(onClick = { clientMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *")
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        clients.filter { !it.archived }.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = {
                                clientId = c.id
                                selectedJobs.clear(); selectedQuotes.clear()
                                clientMenu = false
                            })
                        }
                    }
                }
            }
            item { Text("Perfil: ${profile?.businessName ?: "—"}", style = MaterialTheme.typography.bodySmall) }
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("Trabajos", "Cotizaciones").forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = (sourceMode == "jobs") == (i == 0),
                            onClick = {
                                sourceMode = if (i == 0) "jobs" else "quotes"
                            },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                        ) { Text(t) }
                    }
                }
            }
            item {
                Box {
                    OutlinedButton(onClick = { sourceMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        val n = if (sourceMode == "jobs") selectedJobs.size else selectedQuotes.size
                        Text(
                            if (sourceMode == "jobs") "Trabajos asociados ($n)" else "Cotizaciones asociadas ($n)",
                        )
                    }
                    DropdownMenu(expanded = sourceMenu, onDismissRequest = { sourceMenu = false }) {
                        if (sourceMode == "jobs") {
                            if (eligibleJobs.isEmpty()) {
                                DropdownMenuItem(text = { Text("Sin trabajos elegibles") }, onClick = {})
                            }
                            eligibleJobs.forEach { j ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = j.id in selectedJobs,
                                                onCheckedChange = null,
                                            )
                                            Text("${j.provisionalNumber} · ${j.clientNameSnapshot}")
                                        }
                                    },
                                    onClick = {
                                        if (j.id in selectedJobs) selectedJobs.remove(j.id)
                                        else selectedJobs.add(j.id)
                                    },
                                )
                            }
                        } else {
                            if (eligibleQuotes.isEmpty()) {
                                DropdownMenuItem(text = { Text("Sin cotizaciones aprobadas elegibles") }, onClick = {})
                            }
                            eligibleQuotes.forEach { q ->
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Checkbox(
                                                checked = q.id in selectedQuotes,
                                                onCheckedChange = null,
                                            )
                                            Text("${q.officialNumber ?: q.provisionalNumber} · ${q.clientNameSnapshot}")
                                        }
                                    },
                                    onClick = {
                                        if (q.id in selectedQuotes) selectedQuotes.remove(q.id)
                                        else selectedQuotes.add(q.id)
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Moneda (ISO 4217) *") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedButton(onClick = { lineDialog = CollectionLineDraft() }, modifier = Modifier.fillMaxWidth()) {
                    Text("+ Línea")
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
                    calc.retentions.forEach {
                        Text(
                            "${it.name} (${it.ratePercent}%): -${QuoteCalc.format(it.amount)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("Total retenido: ${QuoteCalc.format(calc.totalRetained)}", style = MaterialTheme.typography.bodySmall)
                    Text("Neto a cobrar: ${QuoteCalc.format(calc.net)}", style = MaterialTheme.typography.titleSmall)
                }
            }
            item {
                OutlinedTextField(
                    value = dueDate, onValueChange = { dueDate = it },
                    label = { Text("Vencimiento AAAA-MM-DD (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (dueDate.isNotBlank() && parseIsoDay(dueDate) == null) {
                    Text("Fecha inválida", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                OutlinedTextField(
                    value = conditions, onValueChange = { conditions = it },
                    label = { Text("Condiciones de cobro (opcional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        when {
                            clientId.isBlank() -> error = "El cliente es obligatorio."
                            currency.length != 3 -> error = "La moneda debe ser un código ISO 4217."
                            lines.isEmpty() -> error = "La cuenta requiere al menos una línea."
                            lines.any { it.description.isBlank() } -> error = "Toda línea necesita descripción."
                            dueDate.isNotBlank() && parseIsoDay(dueDate) == null -> error = "Vencimiento inválido."
                            else -> {
                                error = null
                                onSave(
                                    CollectionAccountInput(
                                        clientId = clientId,
                                        profileId = profileId,
                                        currency = currency,
                                        items = lines.map {
                                            CollectionItemInput(
                                                conceptType = it.conceptType,
                                                description = it.description,
                                                quantity = it.quantity,
                                                unit = it.unit,
                                                unitPrice = it.unitPrice,
                                            )
                                        },
                                        retentions = retentions.filter { it.name in selectedRetentions }.map {
                                            RetentionInput(
                                                name = it.name,
                                                ratePercent = it.ratePercent,
                                                appliesTo = it.appliesTo.split(",").map(ConceptType::valueOf).toSet(),
                                            )
                                        },
                                        jobIds = if (sourceMode == "jobs") selectedJobs.toList() else emptyList(),
                                        quoteIds = if (sourceMode == "quotes") selectedQuotes.toList() else emptyList(),
                                        dueDateEpochDay = parseIsoDay(dueDate),
                                        conditions = conditions,
                                    ),
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

// ---------- Detalle de Cuenta de Cobro ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionAccountDetailScreen(
    account: CollectionAccountEntity?,
    items: List<CollectionAccountItemEntity>,
    jobLinks: List<com.cotixgo.app.data.CollectionAccountJobEntity>,
    quoteLinks: List<com.cotixgo.app.data.CollectionAccountQuoteEntity>,
    jobs: List<JobEntity>,
    quotes: List<QuoteEntity>,
    assignments: List<PaymentAssignmentEntity>,
    payments: List<PaymentEntity>,
    onEdit: () -> Unit,
    onCancelAccount: () -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenQuote: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (account == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Cuenta") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Cuenta no encontrada.")
            }
        }
        return
    }
    val status = CollectionAccountStatus.valueOf(account.status)
    val editable = status == CollectionAccountStatus.PENDIENTE &&
        account.appliedAmount.toMoney().signum() <= 0
    var confirmCancel by remember { mutableStateOf(false) }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancelar cuenta") },
            text = { Text("La cancelación libera los trabajos y cotizaciones asociados. No se puede deshacer.") },
            confirmButton = {
                Button(onClick = { confirmCancel = false; onCancelAccount() }) { Text("Cancelar cuenta") }
            },
            dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Volver") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(account.officialNumber ?: account.provisionalNumber) },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "${collectionLabel(status)}${if (account.archived) " · Archivada" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Cliente: ${account.clientNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                Text("Emitida: ${java.time.Instant.ofEpochMilli(account.issuedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}",
                    style = MaterialTheme.typography.bodySmall)
                account.dueDateEpochDay?.let {
                    Text("Vence: ${LocalDate.ofEpochDay(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                Text("Líneas", style = MaterialTheme.typography.titleSmall)
            }
            items(items.sortedBy { it.position }, key = { it.id }) { l ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("[${l.conceptType}] ${l.description}")
                        Text("${l.quantity} ${l.unit} × ${l.unitPrice} = ${l.lineTotal}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Bruto: ${account.gross}")
                    Text("Retenido: ${account.totalRetained}", style = MaterialTheme.typography.bodySmall)
                    Text("Neto: ${account.net}", style = MaterialTheme.typography.titleSmall)
                    Text("Aplicado: ${account.appliedAmount} · Saldo: ${account.balance} ${account.currency}",
                        style = MaterialTheme.typography.titleSmall)
                }
            }
            if (jobLinks.isNotEmpty()) {
                item { Text("Trabajos asociados", style = MaterialTheme.typography.titleSmall) }
                items(jobLinks.sortedBy { it.position }, key = { it.id }) { link ->
                    val j = jobs.firstOrNull { it.id == link.jobId }
                    Card(onClick = { onOpenJob(link.jobId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${j?.officialNumber ?: j?.provisionalNumber ?: link.jobId} · ${j?.clientNameSnapshot ?: ""}",
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            if (quoteLinks.isNotEmpty()) {
                item { Text("Cotizaciones asociadas", style = MaterialTheme.typography.titleSmall) }
                items(quoteLinks.sortedBy { it.position }, key = { it.id }) { link ->
                    val q = quotes.firstOrNull { it.id == link.quoteId }
                    Card(onClick = { onOpenQuote(link.quoteId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${q?.officialNumber ?: q?.provisionalNumber ?: link.quoteId} · ${q?.clientNameSnapshot ?: ""}",
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
            val appliedEvents = assignments.sortedBy { it.createdAt }
            if (appliedEvents.isNotEmpty()) {
                item { Text("Movimientos de pago", style = MaterialTheme.typography.titleSmall) }
                items(appliedEvents, key = { it.id }) { ev ->
                    val pay = payments.firstOrNull { it.id == ev.paymentId }
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                when (ev.action) {
                                    "ASSIGN" -> "Aplicado ${ev.amount}"
                                    "UNASSIGN" -> "Retirado ${ev.amount}"
                                    else -> "Reasignado ${ev.amount}" +
                                        if (!ev.releasedAmount.isNullOrBlank() && ev.releasedAmount != ev.amount) {
                                            " (liberado ${ev.releasedAmount})"
                                        } else ""
                                },
                            )
                            Text(
                                "${LocalDate.ofEpochDay(java.time.Instant.ofEpochMilli(ev.createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toEpochDay())} · ${ev.reason}" +
                                    (pay?.reference?.takeIf { it.isNotBlank() }?.let { " · Ref. $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editable) {
                        OutlinedButton(onClick = onEdit) { Text("Editar") }
                        OutlinedButton(onClick = { confirmCancel = true }) { Text("Cancelar cuenta") }
                    }
                    OutlinedButton(onClick = { onSetArchived(!account.archived) }) {
                        Text(if (account.archived) "Desarchivar" else "Archivar")
                    }
                }
            }
        }
    }
}

// ---------- Registro de Pago ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentEditorScreen(
    clients: List<ClientEntity>,
    accounts: List<CollectionAccountEntity>,
    methods: List<CollectionMethodEntity>,
    onSave: (PaymentInput) -> Unit,
    onBack: () -> Unit,
) {
    var clientId by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var paidAt by remember { mutableStateOf(LocalDate.now().toString()) }
    var methodId by remember { mutableStateOf<String?>(null) }
    var reference by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var accountId by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }
    var accountMenu by remember { mutableStateOf(false) }

    val receivable = accounts.filter {
        it.clientId == clientId && !it.archived &&
            CollectionAccountStatus.valueOf(it.status) in setOf(
                CollectionAccountStatus.PENDIENTE, CollectionAccountStatus.PARCIAL,
            ) && it.balance.toMoney().signum() > 0
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nuevo pago") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box {
                OutlinedButton(onClick = { clientMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *")
                }
                DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                    clients.filter { !it.archived }.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name) }, onClick = {
                            clientId = c.id; accountId = null; clientMenu = false
                        })
                    }
                }
            }
            OutlinedTextField(
                value = amount, onValueChange = { amount = it },
                label = { Text("Importe recibido *") }, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            OutlinedTextField(
                value = paidAt, onValueChange = { paidAt = it },
                label = { Text("Fecha del pago AAAA-MM-DD") }, modifier = Modifier.fillMaxWidth(),
            )
            Box {
                OutlinedButton(onClick = { methodMenu = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(methods.firstOrNull { it.id == methodId }?.name ?: "Método de cobro (opcional)")
                }
                DropdownMenu(expanded = methodMenu, onDismissRequest = { methodMenu = false }) {
                    DropdownMenuItem(text = { Text("(sin método)") }, onClick = {
                        methodId = null; methodMenu = false
                    })
                    methods.filter { !it.archived }.forEach { m ->
                        DropdownMenuItem(text = { Text(m.name) }, onClick = {
                            methodId = m.id; methodMenu = false
                        })
                    }
                }
            }
            Box {
                OutlinedButton(
                    onClick = { accountMenu = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = clientId.isNotBlank(),
                ) {
                    val sel = accounts.firstOrNull { it.id == accountId }
                    Text(
                        sel?.let { "Aplicar a ${it.officialNumber ?: it.provisionalNumber} (saldo ${it.balance})" }
                            ?: "Sin asignar a cuenta (opcional)",
                    )
                }
                DropdownMenu(expanded = accountMenu, onDismissRequest = { accountMenu = false }) {
                    DropdownMenuItem(text = { Text("Sin asignar") }, onClick = {
                        accountId = null; accountMenu = false
                    })
                    receivable.forEach { a ->
                        DropdownMenuItem(
                            text = { Text("${a.officialNumber ?: a.provisionalNumber} · saldo ${a.balance}") },
                            onClick = { accountId = a.id; accountMenu = false },
                        )
                    }
                }
            }
            OutlinedTextField(
                value = reference, onValueChange = { reference = it },
                label = { Text("Referencia (opcional)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes, onValueChange = { notes = it },
                label = { Text("Notas (opcional)") }, modifier = Modifier.fillMaxWidth(),
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    when {
                        clientId.isBlank() -> error = "El cliente es obligatorio."
                        amount.toMoney().signum() <= 0 -> error = "El importe debe ser positivo."
                        parseIsoDay(paidAt) == null -> error = "Fecha de pago inválida."
                        else -> {
                            error = null
                            onSave(
                                PaymentInput(
                                    clientId = clientId,
                                    amount = amount,
                                    paidAtEpochDay = parseIsoDay(paidAt)!!,
                                    collectionMethodId = methodId,
                                    reference = reference,
                                    notes = notes,
                                    accountId = accountId,
                                    initialAssignReason = if (accountId != null) "Aplicación inicial del pago" else "",
                                ),
                            )
                        }
                    }
                }) { Text("Registrar pago") }
                TextButton(onClick = onBack) { Text("Cancelar") }
            }
        }
    }
}

// ---------- Detalle de Pago y asignaciones ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaymentDetailScreen(
    payment: PaymentEntity?,
    clientName: String,
    methodName: String?,
    account: CollectionAccountEntity?,
    receivableAccounts: List<CollectionAccountEntity>,
    assignments: List<PaymentAssignmentEntity>,
    accountsById: Map<String, CollectionAccountEntity>,
    onAssign: (String, String, String) -> Unit,
    onUnassign: (String) -> Unit,
    onReassign: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    if (payment == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Pago") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Pago no encontrado.")
            }
        }
        return
    }
    var assignDialog by remember { mutableStateOf(false) }
    var unassignDialog by remember { mutableStateOf(false) }
    var reassignDialog by remember { mutableStateOf(false) }
    var dialogError by remember { mutableStateOf<String?>(null) }

    if (assignDialog || reassignDialog || unassignDialog) {
        val isUnassign = unassignDialog
        var reason by remember { mutableStateOf("") }
        var amountText by remember { mutableStateOf("") }
        var targetId by remember { mutableStateOf<String?>(null) }
        var menuOpen by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {
                assignDialog = false; unassignDialog = false; reassignDialog = false; dialogError = null
            },
            title = {
                Text(
                    if (isUnassign) "Retirar aplicación"
                    else if (reassignDialog) "Reasignar a otra cuenta" else "Asignar a cuenta",
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isUnassign) {
                        Box {
                            OutlinedButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                                val sel = receivableAccounts.firstOrNull { it.id == targetId }
                                Text(sel?.let { "${it.officialNumber ?: it.provisionalNumber} · saldo ${it.balance}" }
                                    ?: "Cuenta destino *")
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                receivableAccounts.forEach { a ->
                                    DropdownMenuItem(
                                        text = { Text("${a.officialNumber ?: a.provisionalNumber} · saldo ${a.balance}") },
                                        onClick = { targetId = a.id; menuOpen = false },
                                    )
                                }
                            }
                        }
                        if (assignDialog) {
                            OutlinedTextField(
                                value = amountText, onValueChange = { amountText = it },
                                label = { Text("Importe a aplicar *") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            )
                        }
                    }
                    OutlinedTextField(
                        value = reason, onValueChange = { reason = it },
                        label = { Text("Motivo *") }, modifier = Modifier.fillMaxWidth(),
                    )
                    dialogError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    when {
                        reason.isBlank() -> dialogError = "El motivo es obligatorio."
                        isUnassign -> onUnassign(reason.trim())
                        targetId == null -> dialogError = "Elige la cuenta destino."
                        assignDialog && amountText.toMoney().signum() <= 0 ->
                            dialogError = "Importe inválido."
                        assignDialog -> onAssign(targetId!!, amountText, reason.trim())
                        else -> onReassign(targetId!!, reason.trim())
                    }
                }) { Text("Confirmar") }
            },
            dismissButton = {
                TextButton(onClick = {
                    assignDialog = false; unassignDialog = false; reassignDialog = false; dialogError = null
                }) { Text("Cancelar") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pago ${payment.amount}") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹") } },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(assignmentLabel(PaymentAssignmentStatus.valueOf(payment.assignmentStatus)),
                    style = MaterialTheme.typography.titleMedium)
                Text("Cliente: $clientName", style = MaterialTheme.typography.bodySmall)
                Text("Fecha: ${LocalDate.ofEpochDay(payment.paidAtEpochDay)}",
                    style = MaterialTheme.typography.bodySmall)
                methodName?.let { Text("Método: $it", style = MaterialTheme.typography.bodySmall) }
                if (payment.reference.isNotBlank()) {
                    Text("Referencia: ${payment.reference}", style = MaterialTheme.typography.bodySmall)
                }
                if (payment.notes.isNotBlank()) {
                    Text("Notas: ${payment.notes}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Aplicado: ${payment.appliedAmount} · Sin asignar: ${payment.unassignedAmount}",
                    style = MaterialTheme.typography.titleSmall,
                )
                account?.let {
                    Text("Aplicado a: ${it.officialNumber ?: it.provisionalNumber}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            if (assignments.isNotEmpty()) {
                item { Text("Historial de asignaciones", style = MaterialTheme.typography.titleSmall) }
                items(assignments.sortedBy { it.createdAt }, key = { it.id }) { ev ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                when (ev.action) {
                                    "ASSIGN" -> "Asignado ${ev.amount} a ${accountsById[ev.accountId]?.let { it.officialNumber ?: it.provisionalNumber } ?: ev.accountId}"
                                    "UNASSIGN" -> "Retirado ${ev.amount} de ${accountsById[ev.accountId]?.let { it.officialNumber ?: it.provisionalNumber } ?: ev.accountId}"
                                    else -> "Reasignado ${ev.amount} a ${accountsById[ev.accountId]?.let { it.officialNumber ?: it.provisionalNumber } ?: ev.accountId}"
                                },
                            )
                            Text(ev.reason, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (payment.unassignedAmount.toMoney().signum() > 0 && receivableAccounts.isNotEmpty()) {
                        OutlinedButton(onClick = { assignDialog = true }) { Text("Asignar") }
                    }
                    if (payment.appliedAmount.toMoney().signum() > 0) {
                        OutlinedButton(onClick = { unassignDialog = true }) { Text("Retirar") }
                        OutlinedButton(onClick = { reassignDialog = true }) { Text("Reasignar") }
                    }
                }
            }
        }
    }
}

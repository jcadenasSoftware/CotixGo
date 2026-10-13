package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.data.JobActivityEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialEntity
import com.cotixgo.app.data.JobQuoteEntity
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import java.time.LocalDate

private fun epochDayOrNull(s: String): Long? =
    s.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }

// ---------- Lista ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsListScreen(
    jobs: List<JobEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var showArchived by remember { mutableStateOf(false) }
    var statusFilter by remember { mutableStateOf<JobStatus?>(null) }
    val visible = jobs.filter { it.archived == showArchived }
        .filter { statusFilter == null || it.status == statusFilter!!.name }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Trabajos") }) },
        floatingActionButton = { Button(onClick = onNew) { Text("+ Trabajo") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
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
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf<JobStatus?>(null, JobStatus.BORRADOR, JobStatus.PROGRAMADO, JobStatus.EN_PROCESO)
                    .forEach { s ->
                        TextButton(onClick = { statusFilter = s }) {
                            Text(
                                (s?.name ?: "Todos").replace('_', ' '),
                                color = if (statusFilter == s) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (showArchived) "No hay trabajos archivados." else "Todavía no hay trabajos.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { job ->
                        Card(onClick = { onOpen(job.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    job.officialNumber ?: job.provisionalNumber,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${job.status.replace('_', ' ')} — ${job.clientNameSnapshot}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (job.executionAddress.isNotBlank()) {
                                    Text(job.executionAddress, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Crear / editar ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobFormScreen(
    initial: JobEntity?,
    clients: List<ClientEntity>,
    profile: ProfileEntity?,
    linkableQuotes: List<QuoteEntity>,
    preselectedClientId: String? = null,
    preselectedQuoteId: String? = null,
    // onSave confirma el resultado del repositorio: solo se navega tras
    // un guardado exitoso; un fallo deja el formulario abierto con error.
    onSave: (JobInput, onDone: (String?) -> Unit) -> Unit,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
) {
    // El Trabajo a editar llega por Flow: clavear en initial?.id puebla los
    // campos al cargar y conserva lo que el usuario ya escribió ante
    // emisiones posteriores del mismo Trabajo.
    var clientId by remember(initial?.id) { mutableStateOf(initial?.clientId ?: preselectedClientId ?: "") }
    var startDate by remember(initial?.id) {
        mutableStateOf(initial?.startDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "")
    }
    var estimatedDate by remember(initial?.id) {
        mutableStateOf(initial?.estimatedDateEpochDay?.let { LocalDate.ofEpochDay(it).toString() } ?: "")
    }
    var address by remember(initial?.id) { mutableStateOf(initial?.executionAddress ?: "") }
    var notes by remember(initial?.id) { mutableStateOf(initial?.notes ?: "") }
    var selectedQuotes by remember {
        mutableStateOf(setOfNotNull(preselectedQuoteId))
    }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var clientMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (initial == null) "Nuevo trabajo" else "Editar trabajo") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    OutlinedButton(
                        onClick = { clientMenu = true },
                        enabled = initial == null, // el Cliente fija los vínculos; no se recambia
                        modifier = Modifier.fillMaxWidth().testTag("jobClient"),
                    ) {
                        Text(clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *")
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        clients.filter { !it.archived }.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { clientId = c.id; selectedQuotes = emptySet(); clientMenu = false },
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Perfil: ${profile?.businessName ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                OutlinedTextField(
                    value = startDate, onValueChange = { startDate = it },
                    label = { Text("Fecha de inicio (AAAA-MM-DD)") },
                    modifier = Modifier.fillMaxWidth().testTag("jobStartDate"), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = estimatedDate, onValueChange = { estimatedDate = it },
                    label = { Text("Fecha estimada (AAAA-MM-DD)") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Dirección de ejecución (obligatoria al programar)") },
                    modifier = Modifier.fillMaxWidth().testTag("jobAddress"),
                )
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Observaciones") },
                    modifier = Modifier.fillMaxWidth().testTag("jobNotes"),
                )
            }
            if (initial == null && linkableQuotes.isNotEmpty()) {
                item { Text("Cotizaciones aprobadas del cliente:", style = MaterialTheme.typography.titleSmall) }
                items(linkableQuotes, key = { it.id }) { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = q.id in selectedQuotes,
                            onCheckedChange = {
                                selectedQuotes = if (q.id in selectedQuotes) {
                                    selectedQuotes - q.id
                                } else selectedQuotes + q.id
                            },
                        )
                        Text("${q.officialNumber ?: q.provisionalNumber} — ${q.net} ${q.currency}")
                    }
                }
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        enabled = !saving,
                        onClick = {
                            when {
                                clientId.isBlank() -> error = "Selecciona un cliente."
                                startDate.isNotBlank() && epochDayOrNull(startDate) == null ->
                                    error = "La fecha de inicio no es válida (AAAA-MM-DD)."
                                estimatedDate.isNotBlank() && epochDayOrNull(estimatedDate) == null ->
                                    error = "La fecha estimada no es válida (AAAA-MM-DD)."
                                else -> {
                                    saving = true
                                    onSave(
                                        JobInput(
                                            clientId = clientId,
                                            profileId = profile?.id ?: "",
                                            startDateEpochDay = epochDayOrNull(startDate),
                                            estimatedDateEpochDay = epochDayOrNull(estimatedDate),
                                            executionAddress = address,
                                            notes = notes,
                                            quoteIds = selectedQuotes.toList(),
                                        ),
                                    ) { savedId ->
                                        saving = false
                                        if (savedId != null) {
                                            onSaved(savedId)
                                        } else {
                                            error = "No se pudo guardar el trabajo. Revisa los datos e inténtalo de nuevo."
                                        }
                                    }
                                }
                            }
                        },
                    ) { Text("Guardar") }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

// ---------- Detalle con tabs ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobDetailScreen(
    job: JobEntity?,
    activities: List<JobActivityEntity>,
    materials: List<JobMaterialEntity>,
    links: List<JobQuoteEntity>,
    linkedQuotes: List<QuoteEntity>,
    linkedReports: List<com.cotixgo.app.data.ReportEntity>,
    transitionError: String?,
    onTransition: (JobStatus) -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onAddActivity: (JobActivityInput) -> Unit,
    onActivityStatus: (String, JobActivityStatus) -> Unit,
    onAddMaterial: (JobMaterialInput) -> Unit,
    onLinkQuote: (String) -> Unit,
    linkableQuotes: List<QuoteEntity>,
    onNewReport: () -> Unit,
    onOpenReport: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (job == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    var tab by remember { mutableIntStateOf(0) }
    var activityDialog by remember { mutableStateOf(false) }
    var materialDialog by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf(false) }
    val status = JobStatus.valueOf(job.status)
    val editable = JobTransitions.isEditable(status) && !job.archived

    if (activityDialog) {
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { activityDialog = false },
            title = { Text("Nueva actividad") },
            text = {
                OutlinedTextField(
                    value = desc, onValueChange = { desc = it },
                    label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = {
                    if (desc.isNotBlank()) {
                        onAddActivity(JobActivityInput(jobId = job.id, description = desc))
                        activityDialog = false
                    }
                }) { Text("Agregar") }
            },
            dismissButton = { TextButton(onClick = { activityDialog = false }) { Text("Cancelar") } },
        )
    }

    if (materialDialog) {
        var desc by remember { mutableStateOf("") }
        var qty by remember { mutableStateOf("") }
        var unit by remember { mutableStateOf("") }
        var matNotes by remember { mutableStateOf("") }
        var origin by remember { mutableStateOf(MaterialOrigin.PROFESIONAL) }
        AlertDialog(
            onDismissRequest = { materialDialog = false },
            title = { Text("Nuevo material") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = desc, onValueChange = { desc = it },
                        label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(),
                    )
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        listOf("Del cliente", "Propio").forEachIndexed { i, t ->
                            SegmentedButton(
                                selected = origin == MaterialOrigin.entries[i],
                                onClick = { origin = MaterialOrigin.entries[i] },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                            ) { Text(t) }
                        }
                    }
                    OutlinedTextField(
                        value = qty, onValueChange = { qty = it },
                        label = { Text("Cantidad") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = unit, onValueChange = { unit = it },
                        label = { Text("Unidad") }, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = matNotes, onValueChange = { matNotes = it },
                        label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (desc.isNotBlank()) {
                        onAddMaterial(
                            JobMaterialInput(
                                jobId = job.id, description = desc, origin = origin,
                                quantity = qty, unit = unit, notes = matNotes,
                            ),
                        )
                        materialDialog = false
                    }
                }) { Text("Agregar") }
            },
            dismissButton = { TextButton(onClick = { materialDialog = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(job.officialNumber ?: job.provisionalNumber) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(job.status.replace('_', ' '), style = MaterialTheme.typography.titleMedium)
            Text(job.clientNameSnapshot, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                JobStatus.entries.forEach { target ->
                    if (target != status && JobTransitions.canTransition(status, target)) {
                        OutlinedButton(
                            onClick = { onTransition(target) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 4.dp),
                        ) {
                            Text(
                                target.name.replace('_', ' '),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            transitionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Actividades", "Materiales", "Info").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(t) }
                }
            }

            when (tab) {
                0 -> {
                    if (editable) {
                        OutlinedButton(onClick = { activityDialog = true }) { Text("+ Actividad") }
                    }
                    if (activities.isEmpty()) Text("Sin actividades registradas.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(activities, key = { it.id }) { a ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(a.description)
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(a.status.replace('_', ' '), style = MaterialTheme.typography.bodySmall)
                                        if (editable) {
                                            JobActivityStatus.entries.forEach { s ->
                                                if (s.name != a.status) {
                                                    TextButton(onClick = { onActivityStatus(a.id, s) }) {
                                                        Text("→ ${s.name.replace('_', ' ')}")
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
                1 -> {
                    if (editable) {
                        OutlinedButton(onClick = { materialDialog = true }) { Text("+ Material") }
                    }
                    if (materials.isEmpty()) Text("Sin materiales registrados.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(materials, key = { it.id }) { m ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(m.description)
                                    Text(
                                        buildString {
                                            append(if (m.origin == "CLIENTE") "Del cliente" else "Propio")
                                            if (m.quantity.isNotBlank()) append(" · ${m.quantity} ${m.unit}".trimEnd())
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (m.notes.isNotBlank()) {
                                        Text(m.notes, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        job.startDateEpochDay?.let { Text("Inicio: ${LocalDate.ofEpochDay(it)}") }
                        job.estimatedDateEpochDay?.let { Text("Estimada: ${LocalDate.ofEpochDay(it)}") }
                        job.deliveryDateEpochDay?.let { Text("Entrega: ${LocalDate.ofEpochDay(it)}") }
                        if (job.executionAddress.isNotBlank()) {
                            Text("Dirección de ejecución: ${job.executionAddress}")
                        }
                        if (job.notes.isNotBlank()) Text("Observaciones: ${job.notes}")
                        if (job.archived) {
                            Text("Archivado", color = MaterialTheme.colorScheme.secondary)
                        }
                        if (links.isNotEmpty()) {
                            Text("Cotizaciones asociadas:", style = MaterialTheme.typography.titleSmall)
                            links.forEach { l ->
                                val q = linkedQuotes.firstOrNull { it.id == l.quoteId }
                                Text(
                                    "· ${q?.officialNumber ?: q?.provisionalNumber ?: l.quoteId} (${l.relationType})",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                        if (editable && linkableQuotes.isNotEmpty()) {
                            Box {
                                OutlinedButton(onClick = { linkMenu = true }) { Text("+ Vincular cotización") }
                                DropdownMenu(expanded = linkMenu, onDismissRequest = { linkMenu = false }) {
                                    linkableQuotes.forEach { q ->
                                        DropdownMenuItem(
                                            text = { Text(q.officialNumber ?: q.provisionalNumber) },
                                            onClick = { onLinkQuote(q.id); linkMenu = false },
                                        )
                                    }
                                }
                            }
                        }
                        // Informes (F5): paso opcional de "Documentar"; nunca
                        // requisito para entregar el Trabajo.
                        Text("Informes de trabajo:", style = MaterialTheme.typography.titleSmall)
                        linkedReports.forEach { r ->
                            TextButton(onClick = { onOpenReport(r.id) }) {
                                Text("· ${r.officialNumber ?: r.provisionalNumber} (${r.status})")
                            }
                        }
                        OutlinedButton(onClick = onNewReport) { Text("+ Crear informe con este trabajo") }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (editable) OutlinedButton(onClick = onEdit) { Text("Editar") }
                            OutlinedButton(onClick = { onArchive(!job.archived) }) {
                                Text(if (job.archived) "Desarchivar" else "Archivar")
                            }
                            TextButton(onClick = onBack) { Text("Volver") }
                        }
                    }
                }
            }
        }
    }
}

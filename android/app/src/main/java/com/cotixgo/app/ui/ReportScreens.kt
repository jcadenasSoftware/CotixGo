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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.ReportDestination
import com.cotixgo.app.core.ReportStatus
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.ReportEntity
import com.cotixgo.app.data.ReportInput
import com.cotixgo.app.data.ReportSnapshot
import com.cotixgo.app.data.ReportVersionEntity
import com.cotixgo.app.data.SnapshotJson
import java.time.Instant
import java.time.ZoneId

// ---------- Lista ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsListScreen(
    reports: List<ReportEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var showArchived by remember { mutableStateOf(false) }
    val visible = reports.filter { it.archived == showArchived }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Informes") }) },
        floatingActionButton = { Button(onClick = onNew) { Text("+ Informe") } },
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
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (showArchived) "No hay informes archivados." else "Todavía no hay informes.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { r ->
                        Card(onClick = { onOpen(r.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(
                                    r.officialNumber ?: r.provisionalNumber,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    "${r.status} · ${if (r.destination == "CLIENTE") "Para el cliente" else "Interno"} — ${r.clientNameSnapshot}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Text(r.title, style = MaterialTheme.typography.bodySmall)
                                if (r.versionConflict) {
                                    Text(
                                        "Versiones en conflicto — requiere resolución",
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

// ---------- Crear / editar borrador ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportFormScreen(
    initial: ReportEntity?,
    clients: List<ClientEntity>,
    profile: ProfileEntity?,
    jobs: List<JobEntity>,
    linkedJobs: List<JobEntity>,
    linkableJobs: List<JobEntity>,
    preselectedJobId: String? = null,
    onBuildContent: (List<String>, (String) -> Unit) -> Unit,
    onSave: (ReportInput) -> Unit,
    onLinkJob: (String) -> Unit,
    onBack: () -> Unit,
) {
    // La edición llega por Flow (initial=null en la primera composición):
    // las claves reinician el estado cuando el informe se carga.
    var clientId by remember(initial?.id) { mutableStateOf(initial?.clientId ?: "") }
    var title by remember(initial?.id) { mutableStateOf(initial?.title ?: "") }
    var destination by remember(initial?.id) {
        mutableStateOf(initial?.destination?.let { ReportDestination.valueOf(it) } ?: ReportDestination.CLIENTE)
    }
    var content by remember(initial?.id) { mutableStateOf(initial?.content ?: "") }
    var selectedJobs by remember { mutableStateOf(setOfNotNull(preselectedJobId)) }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }
    var jobMenu by remember { mutableStateOf(false) }

    // Trabajos candidatos al crear: del Cliente elegido y del Perfil activo.
    val candidateJobs = jobs.filter {
        initial == null && it.clientId == clientId && it.profileId == profile?.id && !it.archived
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (initial == null) "Nuevo informe" else "Editar borrador") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Box {
                    OutlinedButton(
                        onClick = { clientMenu = true },
                        // El Cliente fija los vínculos; no se recambia (igual que en Trabajo).
                        enabled = initial == null,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *")
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        clients.filter { !it.archived }.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { clientId = c.id; selectedJobs = emptySet(); clientMenu = false },
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
                    value = title, onValueChange = { title = it },
                    label = { Text("Título *") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ReportDestination.entries.forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = destination == d,
                            onClick = { destination = d },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = i, count = ReportDestination.entries.size,
                            ),
                        ) { Text(if (d == ReportDestination.CLIENTE) "Para el cliente" else "Interno") }
                    }
                }
            }
            if (initial == null) {
                item {
                    Text(
                        "Trabajos a incluir (mismo cliente y perfil):",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
                if (clientId.isBlank()) {
                    item { Text("Elige primero un cliente.", color = MaterialTheme.colorScheme.secondary) }
                } else if (candidateJobs.isEmpty()) {
                    item {
                        Text(
                            "Este cliente no tiene trabajos disponibles con este perfil.",
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                } else {
                    items(candidateJobs, key = { it.id }) { j ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = j.id in selectedJobs,
                                onCheckedChange = {
                                    selectedJobs = if (j.id in selectedJobs) {
                                        selectedJobs - j.id
                                    } else selectedJobs + j.id
                                },
                            )
                            Text(
                                "${j.officialNumber ?: j.provisionalNumber} — ${j.status.replace('_', ' ')}",
                            )
                        }
                    }
                }
            } else {
                item {
                    Text("Trabajos asociados:", style = MaterialTheme.typography.titleSmall)
                    linkedJobs.forEach { j ->
                        Text(
                            "· ${j.officialNumber ?: j.provisionalNumber} — ${j.status.replace('_', ' ')}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (linkableJobs.isNotEmpty()) {
                        Box {
                            OutlinedButton(onClick = { jobMenu = true }) { Text("+ Asociar trabajo") }
                            DropdownMenu(expanded = jobMenu, onDismissRequest = { jobMenu = false }) {
                                linkableJobs.forEach { j ->
                                    DropdownMenuItem(
                                        text = { Text(j.officialNumber ?: j.provisionalNumber) },
                                        onClick = { onLinkJob(j.id); jobMenu = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = content, onValueChange = { content = it },
                    label = { Text("Contenido / observaciones") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                )
            }
            if (initial == null) {
                item {
                    TextButton(onClick = {
                        if (selectedJobs.isNotEmpty()) {
                            onBuildContent(selectedJobs.toList()) { generated -> content = generated }
                        }
                    }) { Text("Generar contenido desde los trabajos seleccionados") }
                }
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        when {
                            clientId.isBlank() -> error = "Selecciona un cliente."
                            title.isBlank() -> error = "El título es obligatorio."
                            initial == null && selectedJobs.isEmpty() ->
                                error = "Asocia al menos un trabajo."
                            else -> onSave(
                                ReportInput(
                                    clientId = clientId,
                                    profileId = profile?.id ?: "",
                                    title = title,
                                    destination = destination,
                                    content = content,
                                    jobIds = selectedJobs.toList(),
                                ),
                            )
                        }
                    }) { Text("Guardar") }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

// ---------- Detalle ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportDetailScreen(
    report: ReportEntity?,
    linkedJobs: List<JobEntity>,
    versions: List<ReportVersionEntity>,
    emitFailed: Boolean,
    onEmit: () -> Unit,
    onCorrect: () -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onOpenVersion: (Int) -> Unit,
    onResolveVersions: () -> Unit,
    onBack: () -> Unit,
) {
    if (report == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    val draft = report.status == ReportStatus.BORRADOR.name

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(report.officialNumber ?: report.provisionalNumber) })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(report.title, style = MaterialTheme.typography.titleMedium)
            Text(
                "${report.status} · ${if (report.destination == "CLIENTE") "Para el cliente" else "Interno"} — ${report.clientNameSnapshot}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (report.archived) {
                Text("Archivado", color = MaterialTheme.colorScheme.secondary)
            }
            if (report.versionConflict) {
                Text(
                    "Versiones concurrentes conservadas. Elige la versión a conservar para resolver.",
                    color = MaterialTheme.colorScheme.error,
                )
                Button(onClick = onResolveVersions) { Text("Resolver versiones") }
            }
            if (emitFailed) {
                Text(
                    "No se pudo emitir: el informe necesita al menos un trabajo asociado.",
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text("Contenido:", style = MaterialTheme.typography.titleSmall)
            Card(Modifier.fillMaxWidth()) {
                Text(
                    report.content.ifBlank { "(sin contenido)" },
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Text("Trabajos asociados:", style = MaterialTheme.typography.titleSmall)
            linkedJobs.forEach { j ->
                Text(
                    "· ${j.officialNumber ?: j.provisionalNumber} — ${j.status.replace('_', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (versions.isNotEmpty()) {
                Text("Versiones emitidas:", style = MaterialTheme.typography.titleSmall)
                versions.sortedByDescending { it.version }.forEach { v ->
                    TextButton(onClick = { onOpenVersion(v.version) }) {
                        Text("Versión ${v.version} · ${Instant.ofEpochMilli(v.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()}")
                    }
                }
            }

            HorizontalDivider()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft && !report.archived) {
                    Button(onClick = onEmit) { Text("Emitir") }
                    OutlinedButton(onClick = onEdit) { Text("Editar") }
                }
                if (report.status == ReportStatus.EMITIDO.name && !report.archived) {
                    OutlinedButton(onClick = onCorrect) { Text("Corregir (reabrir borrador)") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onArchive(!report.archived) }) {
                    Text(if (report.archived) "Desarchivar" else "Archivar")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

// ---------- Versión emitida (snapshot inmutable) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportVersionScreen(
    snapshot: ReportSnapshot?,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("Versión ${snapshot?.version ?: ""} emitida") }) },
    ) { padding ->
        if (snapshot == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    snapshot.officialNumber ?: snapshot.provisionalNumber,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(snapshot.title)
                Text(
                    "${snapshot.client.name} · ${if (snapshot.destination == "CLIENTE") "Para el cliente" else "Interno"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        snapshot.content.ifBlank { "(sin contenido)" },
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                snapshot.jobs.forEach { j ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${j.number} — ${j.status.replace('_', ' ')}",
                                style = MaterialTheme.typography.titleSmall)
                            j.completedActivities.forEach { a ->
                                Text(
                                    "✓ ${a.description}" +
                                        (a.finishedAt?.let {
                                            " (${Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate()})"
                                        } ?: "") +
                                        if (a.notes.isNotBlank()) " — ${a.notes}" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            j.materials.forEach { m ->
                                Text(
                                    "Material: ${m.description}" +
                                        (if (m.quantity.isNotBlank()) " (${m.quantity} ${m.unit})".trimEnd().let { " $it" } else "") +
                                        if (m.origin == "CLIENTE") " [del cliente]" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (j.notes.isNotBlank()) {
                                Text("Observaciones: ${j.notes}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

// ---------- Resolución de versiones de informe ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveReportVersionsScreen(
    reportNumber: String,
    versions: List<ReportVersionEntity>,
    onPick: (versionRowId: String) -> Unit,
    onBack: () -> Unit,
) {
    val ordered = versions.sortedWith(
        compareByDescending<ReportVersionEntity> { it.version }
            .thenByDescending { it.serverOrder ?: 0 }
            .thenByDescending { it.createdAt },
    )
    val duplicatedNumbers = versions.groupingBy { it.version }.eachCount().filter { it.value > 1 }.keys
    Scaffold(topBar = { TopAppBar(title = { Text("Resolver versiones · $reportNumber") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(ordered) { v ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Versión ${v.version}" +
                                (if (v.version in duplicatedNumbers && v.serverOrder != null)
                                    " · orden servidor ${v.serverOrder}" else ""),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        val snap = runCatching { SnapshotJson.decodeReport(v.snapshotJson) }.getOrNull()
                        Text(
                            (snap?.content ?: v.snapshotJson).take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        Button(onClick = { onPick(v.id) }) { Text("Conservar esta versión") }
                    }
                }
            }
            item { TextButton(onClick = onBack) { Text("Volver") } }
        }
    }
}

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.window.Dialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.MaterialCondition
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.data.JobActivityEntity
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialEntity
import com.cotixgo.app.data.JobPhotoEntity
import com.cotixgo.app.data.JobQuoteEntity
import com.cotixgo.app.data.PhotoStore
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import java.time.LocalDate

private fun epochDayOrNull(s: String): Long? =
    s.trim().takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it).toEpochDay() }.getOrNull() }

private val JobCardShape = RoundedCornerShape(16.dp)

/** Encabezado compacto de 52.dp: flecha de regreso opcional + título navy. */
@Composable
private fun JobTopBar(title: String, onBack: (() -> Unit)? = null) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                color = CotixGoNavy,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** Chip de estado del Trabajo, coherente con los colores del listado. */
@Composable
private fun JobStatusChip(status: JobStatus) {
    val (bg, fg) = when (status) {
        JobStatus.PROGRAMADO -> Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
        JobStatus.EN_PROCESO -> Color(0xFFFEF3C7) to Color(0xFFB45309)
        JobStatus.ENTREGADO -> Color(0xFFD1FAE5) to Color(0xFF065F46)
        JobStatus.CANCELADO -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(50), color = bg) {
        Text(
            status.name.replace('_', ' '),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
    }
}

/** Fila etiqueta/valor usada en tarjetas de datos de Trabajos. */
@Composable
private fun JobInfoField(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            modifier = Modifier.width(96.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
        Text(
            value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Estado vacío suave, coherente con los módulos ya pulidos. */
@Composable
private fun JobEmpty(message: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = JobCardShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Text(
            message,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

/** Encabezado de sección dentro del formulario/detalle de Trabajo. */
@Composable
private fun JobSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = CotixGoNavy,
    )
}

/** Celda de la cuadrícula de fotos: miniatura, fecha y marca de pendiente. */
@Composable
private fun JobPhotoCell(photo: JobPhotoEntity, onClick: () -> Unit) {
    val context = LocalContext.current
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photo.id, photo.updatedAt, photo.fileReady) {
        // Decodificación en IO: el hilo principal nunca toca disco.
        value = withContext(Dispatchers.IO) { PhotoStore.thumb(context, photo) }?.asImageBitmap()
    }
    Surface(
        modifier = Modifier.aspectRatio(1f).clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Box(Modifier.fillMaxSize()) {
            if (bmp != null) {
                Image(
                    bitmap = bmp!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Text(
                    when {
                        photo.fileUnavailable -> "Sin archivo"
                        !photo.fileReady -> "Descargando…"
                        // fileReady=1 pero el binario no decodifica: dañado.
                        else -> "Dañado"
                    },
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (photo.fileUnavailable) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.secondary
                    },
                )
            }
            if (photo.pendingSync || !photo.fileSynced) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ) {
                    Text(
                        "pend.",
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
            ) {
                Text(
                    dashboardDayLabel(photo.createdAt / 86_400_000L),
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
        }
    }
}

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
        topBar = { JobTopBar("Trabajos") },
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
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf<JobStatus?>(null, JobStatus.BORRADOR, JobStatus.PROGRAMADO, JobStatus.EN_PROCESO)
                    .forEach { s ->
                        val selected = statusFilter == s
                        Surface(
                            onClick = { statusFilter = s },
                            shape = RoundedCornerShape(50),
                            color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        ) {
                            Text(
                                (s?.name ?: "Todos").replace('_', ' '),
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
            }
            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            when {
                                showArchived -> "No hay trabajos archivados."
                                statusFilter != null -> "Sin trabajos en este filtro."
                                else -> "Todavía no hay trabajos."
                            },
                            style = MaterialTheme.typography.titleSmall,
                            color = CotixGoNavy,
                        )
                        Text(
                            when {
                                showArchived -> "Los trabajos que archives aparecerán aquí."
                                statusFilter != null -> "Prueba con otro estado o Todos."
                                else -> "Toca + Trabajo para crear el primero."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { job ->
                        Surface(
                            onClick = { onOpen(job.id) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = JobCardShape,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                ) {
                                    Icon(
                                        Icons.Filled.Build,
                                        contentDescription = null,
                                        modifier = Modifier.padding(10.dp).size(20.dp),
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        job.officialNumber ?: job.provisionalNumber,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = CotixGoNavy,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        job.clientNameSnapshot,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    listOf(
                                        job.executionAddress.takeIf { it.isNotBlank() },
                                        job.startDateEpochDay?.let { "Inicio: ${dashboardDayLabel(it)}" },
                                    ).filterNotNull().joinToString(" · ").takeIf { it.isNotEmpty() }?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                JobStatusChip(JobStatus.valueOf(job.status))
                                Icon(
                                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                )
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
        topBar = { JobTopBar(if (initial == null) "Nuevo trabajo" else "Editar trabajo", onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { JobSectionTitle("Datos") }
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
                Surface(shape = JobCardShape, color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        JobInfoField("Perfil", profile?.businessName ?: "—")
                    }
                }
            }
            item { JobSectionTitle("Fechas") }
            item {
                OutlinedTextField(
                    value = startDate, onValueChange = { startDate = it },
                    label = { Text("Fecha de inicio (AAAA-MM-DD)") },
                    supportingText = {
                        Text(epochDayOrNull(startDate)?.let { dashboardDayLabel(it) } ?: "Formato AAAA-MM-DD")
                    },
                    modifier = Modifier.fillMaxWidth().testTag("jobStartDate"), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = estimatedDate, onValueChange = { estimatedDate = it },
                    label = { Text("Fecha estimada (AAAA-MM-DD)") },
                    supportingText = {
                        Text(epochDayOrNull(estimatedDate)?.let { dashboardDayLabel(it) } ?: "Formato AAAA-MM-DD")
                    },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item { JobSectionTitle("Dirección y observaciones") }
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
                item { JobSectionTitle("Cotizaciones aprobadas del cliente") }
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
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
                    ) { Text("Guardar trabajo") }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
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
    photos: List<JobPhotoEntity> = emptyList(),
    transitionError: String?,
    onTransition: (JobStatus) -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onAddActivity: (JobActivityInput) -> Unit,
    onActivityStatus: (String, JobActivityStatus) -> Unit,
    onAddMaterial: (JobMaterialInput) -> Unit,
    onPhotoCaptured: (java.io.File) -> Unit = {},
    onDeletePhoto: (String) -> Unit = {},
    onRetryPhotoDownload: (String) -> Unit = {},
    onRepairPhotoDownload: (String) -> Unit = {},
    // F8: materiales inventariables disponibles para consumo explícito.
    inventoryMaterials: List<com.cotixgo.app.data.MaterialEntity> = emptyList(),
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
    var viewPhoto by remember { mutableStateOf<JobPhotoEntity?>(null) }
    var deletePhoto by remember { mutableStateOf<JobPhotoEntity?>(null) }
    var pendingCapture by remember { mutableStateOf<java.io.File?>(null) }
    val status = JobStatus.valueOf(job.status)
    val editable = JobTransitions.isEditable(status) && !job.archived
    val context = LocalContext.current
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) pendingCapture?.let { onPhotoCaptured(it) }
        pendingCapture = null
    }

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
        // F8: vínculo opcional al inventario — solo al crear; genera el
        // movimiento de consumo (append-only) y sus campos quedan fijos.
        var linkInventory by remember { mutableStateOf(false) }
        var linkedMaterialId by remember { mutableStateOf<String?>(null) }
        var linkedCondition by remember { mutableStateOf(MaterialCondition.NUEVO) }
        var linkMenu by remember { mutableStateOf(false) }
        val linkedMaterial = inventoryMaterials.firstOrNull { it.id == linkedMaterialId }
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
                                onClick = {
                                    origin = MaterialOrigin.entries[i]
                                    if (origin != MaterialOrigin.PROFESIONAL) linkInventory = false
                                },
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
                    if (origin == MaterialOrigin.PROFESIONAL && inventoryMaterials.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = linkInventory,
                                onCheckedChange = {
                                    linkInventory = it
                                    if (!it) linkedMaterialId = null
                                },
                            )
                            Text("Descontar del inventario")
                        }
                        if (linkInventory) {
                            Box {
                                OutlinedButton(
                                    onClick = { linkMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(linkedMaterial?.name ?: "Material del catálogo *")
                                }
                                DropdownMenu(
                                    expanded = linkMenu,
                                    onDismissRequest = { linkMenu = false },
                                ) {
                                    inventoryMaterials.forEach { m ->
                                        DropdownMenuItem(
                                            text = { Text("${m.name} (${m.unit})") },
                                            onClick = {
                                                linkedMaterialId = m.id
                                                if (unit.isBlank()) unit = m.unit
                                                if (desc.isBlank()) desc = m.name
                                                linkMenu = false
                                            },
                                        )
                                    }
                                }
                            }
                            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                listOf("Nuevo", "Usado").forEachIndexed { i, t ->
                                    SegmentedButton(
                                        selected = linkedCondition == MaterialCondition.entries[i],
                                        onClick = { linkedCondition = MaterialCondition.entries[i] },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                                    ) { Text(t) }
                                }
                            }
                        }
                    }
                    OutlinedTextField(
                        value = matNotes, onValueChange = { matNotes = it },
                        label = { Text("Observaciones") }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val linked = linkInventory && linkedMaterialId != null
                    if (desc.isNotBlank() && (!linked || qty.isNotBlank())) {
                        onAddMaterial(
                            JobMaterialInput(
                                jobId = job.id, description = desc, origin = origin,
                                quantity = qty, unit = unit, notes = matNotes,
                                materialId = if (linked) linkedMaterialId else null,
                                condition = if (linked) linkedCondition else null,
                            ),
                        )
                        materialDialog = false
                    }
                }) { Text("Agregar") }
            },
            dismissButton = { TextButton(onClick = { materialDialog = false }) { Text("Cancelar") } },
        )
    }

    viewPhoto?.let { p ->
        Dialog(onDismissRequest = { viewPhoto = null }) {
            Surface(shape = JobCardShape, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val full = produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, p.id, p.updatedAt) {
                        value = withContext(Dispatchers.IO) { PhotoStore.full(context, p) }?.asImageBitmap()
                    }.value
                    if (full != null) {
                        Image(
                            bitmap = full,
                            contentDescription = null,
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Fit,
                        )
                    } else if (p.fileUnavailable) {
                        JobEmpty("El archivo no está disponible en el servidor.")
                    } else if (p.fileReady) {
                        JobEmpty("El archivo local está dañado o no se puede abrir.")
                    } else {
                        JobEmpty("La foto no está disponible en este dispositivo todavía.")
                    }
                    Text(
                        dashboardDayLabel(p.createdAt / 86_400_000L),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (p.fileUnavailable) {
                            TextButton(onClick = { onRetryPhotoDownload(p.id); viewPhoto = null }) {
                                Text("Reintentar descarga")
                            }
                        }
                        // Copia local dañada: solo se ofrece re-descarga si el
                        // metadato ya está confirmado en el servidor (la copia
                        // remota puede existir); el archivo corrupto se
                        // reemplaza solo cuando llega uno válido.
                        if (p.fileReady && full == null && !p.fileUnavailable
                            && !p.pendingSync && !p.deleted) {
                            TextButton(onClick = { onRepairPhotoDownload(p.id); viewPhoto = null }) {
                                Text("Recuperar del servidor")
                            }
                        }
                        TextButton(onClick = { deletePhoto = p; viewPhoto = null }) {
                            Text("Borrar", color = MaterialTheme.colorScheme.error)
                        }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { viewPhoto = null }) { Text("Cerrar") }
                    }
                }
            }
        }
    }

    deletePhoto?.let { p ->
        AlertDialog(
            onDismissRequest = { deletePhoto = null },
            title = { Text("Borrar foto") },
            text = { Text("La foto se borrará de este dispositivo y se sincronizará como eliminada en el servidor y los demás dispositivos. ¿Continuar?") },
            confirmButton = {
                Button(onClick = { onDeletePhoto(p.id); deletePhoto = null }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { deletePhoto = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { JobTopBar("Trabajo", onBack) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    job.officialNumber ?: job.provisionalNumber,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = CotixGoNavy,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                JobStatusChip(status)
            }
            Surface(shape = JobCardShape, color = MaterialTheme.colorScheme.surface) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    JobInfoField("Cliente", job.clientNameSnapshot.ifBlank { "—" })
                    job.startDateEpochDay?.let { JobInfoField("Inicio", dashboardDayLabel(it)) }
                    job.estimatedDateEpochDay?.let { JobInfoField("Estimada", dashboardDayLabel(it)) }
                    job.deliveryDateEpochDay?.let { JobInfoField("Entrega", dashboardDayLabel(it)) }
                    if (job.executionAddress.isNotBlank()) {
                        JobInfoField("Dirección", job.executionAddress)
                    }
                    if (job.archived) JobInfoField("Estado", "Archivado")
                }
            }
            val transitionTargets = JobStatus.entries.filter {
                it != status && JobTransitions.canTransition(status, it)
            }
            // La transición de avance (mayor ordinal del flujo) va como
            // primaria; regresiones quedan secundarias y CANCELADO destructiva.
            val forwardTargets = transitionTargets.filter { it != JobStatus.CANCELADO }
            val primaryTarget = forwardTargets.maxByOrNull { it.ordinal }
            if (primaryTarget != null) {
                Button(
                    onClick = { onTransition(primaryTarget) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(primaryTarget.name.replace('_', ' ')) }
            }
            forwardTargets.filter { it != primaryTarget }.let { rest ->
                if (rest.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rest.forEach { target ->
                            OutlinedButton(
                                onClick = { onTransition(target) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp),
                            ) {
                                Text(target.name.replace('_', ' '), textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
            if (JobStatus.CANCELADO in transitionTargets) {
                OutlinedButton(
                    onClick = { onTransition(JobStatus.CANCELADO) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                ) { Text("Cancelar trabajo") }
            }
            transitionError?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                // Peso ~longitud del texto: "Actividades" necesita más ancho
                // para no partirse ("Actividade / s") en pantallas angostas.
                val tabLabels = listOf("Actividades", "Materiales", "Fotos", "Info")
                tabLabels.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = tabLabels.size),
                        modifier = Modifier.weight(t.length.toFloat()),
                    ) { Text(t, maxLines = 1, softWrap = false) }
                }
            }

            when (tab) {
                0 -> {
                    if (editable) {
                        OutlinedButton(onClick = { activityDialog = true }) { Text("+ Actividad") }
                    }
                    if (activities.isEmpty()) JobEmpty("Sin actividades registradas.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(activities, key = { it.id }) { a ->
                            Surface(
                                shape = JobCardShape,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
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
                    if (materials.isEmpty()) JobEmpty("Sin materiales registrados.")
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(materials, key = { it.id }) { m ->
                            Surface(
                                shape = JobCardShape,
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text(m.description)
                                    Text(
                                        buildString {
                                            append(if (m.origin == "CLIENTE") "Del cliente" else "Propio")
                                            if (m.quantity.isNotBlank()) append(" · ${m.quantity} ${m.unit}".trimEnd())
                                            if (m.materialId != null) {
                                                append(" · inventario ${if (m.condition == "USADO") "usado" else "nuevo"}")
                                            }
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
                2 -> {
                    // Fotos de evidencia: captura solo mientras el Trabajo
                    // está EN PROCESO; la consulta queda disponible siempre.
                    if (status == JobStatus.EN_PROCESO && !job.archived) {
                        OutlinedButton(onClick = {
                            val tmp = PhotoStore.newCaptureFile(context)
                            pendingCapture = tmp
                            takePicture.launch(PhotoStore.uriFor(context, tmp))
                        }) { Text("+ Tomar foto") }
                    }
                    if (photos.isEmpty()) {
                        JobEmpty("Sin fotos registradas.")
                    }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(photos, key = { it.id }) { p ->
                            JobPhotoCell(photo = p, onClick = { viewPhoto = p })
                        }
                    }
                }
                else -> {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (job.notes.isNotBlank()) {
                            Surface(shape = JobCardShape, color = MaterialTheme.colorScheme.surface) {
                                Column(
                                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    JobSectionTitle("Observaciones")
                                    Text(job.notes, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        if (links.isNotEmpty()) {
                            JobSectionTitle("Cotizaciones asociadas")
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
                        // requisito para entregar el Trabajo. La sección va
                        // en tarjeta para igualar jerarquía del detalle.
                        Surface(
                            shape = JobCardShape,
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                JobSectionTitle("Informes de trabajo")
                                if (linkedReports.isEmpty()) {
                                    JobEmpty("Sin informes registrados.")
                                }
                        linkedReports.forEach { r ->
                            Surface(
                                onClick = { onOpenReport(r.id) },
                                modifier = Modifier.fillMaxWidth(),
                                shape = JobCardShape,
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        "${r.officialNumber ?: r.provisionalNumber} (${r.status})",
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }
                                // Jerarquía secundaria visible, sin competir
                                // con las acciones destructivas del Trabajo.
                                androidx.compose.material3.FilledTonalButton(
                                    onClick = onNewReport,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("+ Crear informe con este trabajo") }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (editable) {
                                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Editar") }
                            }
                            OutlinedButton(onClick = { onArchive(!job.archived) }, modifier = Modifier.weight(1f)) {
                                Text(if (job.archived) "Desarchivar" else "Archivar")
                            }
                        }
                    }
                }
            }
        }
    }
}

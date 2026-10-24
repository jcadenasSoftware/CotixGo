package com.cotixgo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cotixgo.app.R
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.validateClient
import com.cotixgo.app.core.validateOnboarding
import com.cotixgo.app.data.ClientEntity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// ---------- Onboarding ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(onComplete: (String, String) -> Unit) {
    var userName by remember { mutableStateOf("") }
    var profileName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CotixGo") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Bienvenido. Crea tu usuario local y tu primer perfil profesional para empezar a trabajar sin conexión.")
            OutlinedTextField(
                value = userName,
                onValueChange = { userName = it },
                label = { Text("Tu nombre") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = profileName,
                onValueChange = { profileName = it },
                label = { Text("Nombre comercial del primer perfil") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val issue = validateOnboarding(userName, profileName)
                    if (issue != null) error = issue else onComplete(userName, profileName)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Empezar") }
        }
    }
}

// ---------- Dashboard (docs/04 §2.1) ----------

private val DashboardCardShape = RoundedCornerShape(14.dp)

/** Día corto en español para las filas del Dashboard ("17 oct 2026"). */
internal fun dashboardDayLabel(epochDay: Long): String =
    LocalDate.ofEpochDay(epochDay)
        .format(DateTimeFormatter.ofPattern("d MMM uuuu", Locale("es")))

/** Icono de sección/entidad dentro de una losa tintada. */
@Composable
private fun DashboardIconTile(
    container: Color,
    content: Color,
    contentSlot: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp),
        shape = RoundedCornerShape(10.dp),
        color = container,
    ) {
        Box(contentAlignment = Alignment.Center) { contentSlot() }
    }
}

@Composable
private fun DashboardIconTile(container: Color, icon: ImageVector, tint: Color) {
    DashboardIconTile(container, tint) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** Tarjeta de estado de sincronización; accionable según el estado. */
@Composable
private fun SyncStatusCard(
    pending: Int,
    paused: Int,
    boundEmail: String?,
    onAttention: () -> Unit,
    onAccount: () -> Unit,
) {
    val bg: Color
    val fg: Color
    val icon: ImageVector
    val title: String
    val subtitle: String
    val onClick: (() -> Unit)?
    when {
        paused > 0 -> {
            bg = MaterialTheme.colorScheme.errorContainer
            fg = MaterialTheme.colorScheme.onErrorContainer
            icon = Icons.Filled.Warning
            title = "$paused operaciones requieren atención"
            subtitle = "Toca para revisarlas"
            onClick = onAttention
        }
        boundEmail == null -> {
            bg = MaterialTheme.colorScheme.surfaceVariant
            fg = MaterialTheme.colorScheme.onSurfaceVariant
            icon = Icons.Filled.Info
            title = "Sin cuenta vinculada"
            subtitle = "El trabajo local sigue disponible sin conexión"
            onClick = onAccount
        }
        pending > 0 -> {
            bg = CotixGoAmberSoft
            fg = MaterialTheme.colorScheme.tertiary
            icon = Icons.Filled.Refresh
            title = "$pending operaciones pendientes de sincronizar"
            subtitle = "Se enviarán a tu cuenta al sincronizar"
            onClick = onAccount
        }
        else -> {
            bg = CotixGoGreenSoft
            fg = CotixGoGreen
            icon = Icons.Filled.CheckCircle
            title = "Sincronizado"
            subtitle = "Todo actualizado"
            onClick = null
        }
    }
    val row: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = fg, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = fg, style = MaterialTheme.typography.bodySmall)
            }
            if (onClick != null) {
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null, tint = fg,
                )
            }
        }
    }
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = DashboardCardShape,
            color = bg,
        ) { row() }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = DashboardCardShape,
            color = bg,
        ) { row() }
    }
}

/** Acción primaria del Dashboard: losa de color con icono y etiqueta. */
@Composable
private fun RowScope.DashboardAction(
    label: String,
    container: Color,
    content: Color,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.weight(1f).height(58.dp),
        shape = DashboardCardShape,
        color = container,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            leading()
            Text(
                label,
                color = content,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Fila accionable de una sección del Dashboard → detalle correspondiente. */
@Composable
private fun DashboardItemCard(
    title: String,
    subtitle: String,
    subtitleColor: Color,
    leading: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DashboardCardShape,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading()
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
        }
    }
}

@Composable
private fun DashboardSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onBackground,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    userName: String,
    loading: Boolean,
    items: com.cotixgo.app.core.DashboardItems,
    todayEpochDay: Long,
    syncPending: Int,
    syncPaused: Int,
    boundEmail: String?,
    onNewClient: () -> Unit,
    onNewQuote: () -> Unit,
    onNewJob: () -> Unit,
    onNewAccount: () -> Unit,
    onOpenQuote: (String) -> Unit,
    onOpenJob: (String) -> Unit,
    onOpenReport: (String) -> Unit,
    onOpenAccount: (String) -> Unit,
    onSyncAttention: () -> Unit,
    onSettings: () -> Unit,
    onAccount: () -> Unit = {},
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.cotixgo_logo_horizontal),
                            contentDescription = "CotixGo",
                            modifier = Modifier.height(36.dp),
                            contentScale = ContentScale.Fit,
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onSettings) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Ajustes",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Surface(color = CotixGoNavy) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "Hola, $userName",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Esto es lo que requiere tu atención hoy.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CotixGoBlueSoft,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SyncStatusCard(
                pending = syncPending,
                paused = syncPaused,
                boundEmail = boundEmail,
                onAttention = onSyncAttention,
                onAccount = onAccount,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardAction(
                    label = "Cotización",
                    container = CotixGoAmber,
                    content = CotixGoNavy,
                    leading = {
                        Icon(Icons.Filled.Add, contentDescription = null,
                            tint = CotixGoNavy, modifier = Modifier.size(22.dp))
                    },
                    onClick = onNewQuote,
                )
                DashboardAction(
                    label = "Cliente",
                    container = CotixGoBlue,
                    content = Color.White,
                    leading = {
                        Icon(Icons.Filled.Person, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(22.dp))
                    },
                    onClick = onNewClient,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DashboardAction(
                    label = "Trabajo",
                    container = CotixGoNavy,
                    content = Color.White,
                    leading = {
                        Icon(Icons.Filled.Build, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(20.dp))
                    },
                    onClick = onNewJob,
                )
                DashboardAction(
                    label = "Cuenta de Cobro",
                    container = CotixGoGreen,
                    content = Color.White,
                    leading = {
                        Text(
                            "$",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    onClick = onNewAccount,
                )
            }
            if (loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Text("Cargando…", color = MaterialTheme.colorScheme.secondary)
                }
            } else if (items.isEmpty) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = DashboardCardShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = CotixGoGreen,
                            modifier = Modifier.size(24.dp),
                        )
                        Column {
                            Text(
                                "Sin pendientes por revisar.",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Cuando algo requiera tu atención aparecerá aquí.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
            if (items.attentionQuotes.isNotEmpty()) {
                DashboardSectionHeader("Cotizaciones por vencer y vencidas")
                items.attentionQuotes.forEach { q ->
                    val effective = com.cotixgo.app.core.effectiveQuoteStatus(
                        com.cotixgo.app.core.QuoteStatus.valueOf(q.status),
                        q.expiryDateEpochDay, todayEpochDay,
                    )
                    val vencida = effective == com.cotixgo.app.core.QuoteStatus.VENCIDA
                    val date = dashboardDayLabel(q.expiryDateEpochDay)
                    DashboardItemCard(
                        title = "${q.officialNumber ?: q.provisionalNumber} — ${q.clientNameSnapshot}",
                        subtitle = if (vencida) "Vencida: $date" else "Vence: $date",
                        subtitleColor = if (vencida) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.secondary,
                        leading = {
                            DashboardIconTile(
                                container = if (vencida) MaterialTheme.colorScheme.errorContainer
                                    else CotixGoAmberSoft,
                                icon = Icons.AutoMirrored.Filled.List,
                                tint = if (vencida) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.tertiary,
                            )
                        },
                        onClick = { onOpenQuote(q.id) },
                    )
                }
            }
            if (items.activeJobs.isNotEmpty()) {
                DashboardSectionHeader("Trabajos programados y en proceso")
                items.activeJobs.forEach { j ->
                    val enProceso = j.status == com.cotixgo.app.core.JobStatus.EN_PROCESO.name
                    val start = j.startDateEpochDay?.let { " · ${dashboardDayLabel(it)}" }.orEmpty()
                    DashboardItemCard(
                        title = "${j.officialNumber ?: j.provisionalNumber} — ${j.clientNameSnapshot}",
                        subtitle = if (enProceso) "En proceso" else "Programado$start",
                        subtitleColor = MaterialTheme.colorScheme.secondary,
                        leading = {
                            DashboardIconTile(
                                container = if (enProceso) CotixGoAmberSoft else CotixGoBlueSoft,
                                icon = Icons.Filled.Build,
                                tint = if (enProceso) MaterialTheme.colorScheme.tertiary
                                    else CotixGoBlue,
                            )
                        },
                        onClick = { onOpenJob(j.id) },
                    )
                }
            }
            if (items.draftReports.isNotEmpty()) {
                DashboardSectionHeader("Informes pendientes")
                items.draftReports.forEach { r ->
                    DashboardItemCard(
                        title = "${r.officialNumber ?: r.provisionalNumber} — ${r.title}",
                        subtitle = r.clientNameSnapshot,
                        subtitleColor = MaterialTheme.colorScheme.secondary,
                        leading = {
                            DashboardIconTile(
                                container = CotixGoBlueSoft,
                                icon = Icons.Filled.Create,
                                tint = CotixGoBlue,
                            )
                        },
                        onClick = { onOpenReport(r.id) },
                    )
                }
            }
            if (items.openAccounts.isNotEmpty()) {
                DashboardSectionHeader("Cuentas por cobrar")
                items.openAccounts.forEach { a ->
                    DashboardItemCard(
                        title = "${a.officialNumber ?: a.provisionalNumber} — ${a.clientNameSnapshot}",
                        subtitle = "Saldo: ${a.balance} ${a.currency}",
                        subtitleColor = MaterialTheme.colorScheme.secondary,
                        leading = {
                            DashboardIconTile(CotixGoAmberSoft, MaterialTheme.colorScheme.tertiary) {
                                Text(
                                    "$",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        },
                        onClick = { onOpenAccount(a.id) },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---------- Clientes ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientsScreen(
    clients: List<ClientEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var showArchived by remember { mutableStateOf(false) }
    val visible = clients.filter { it.archived == showArchived }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Clientes") }) },
        floatingActionButton = {
            Button(onClick = onNew) { Text("+ Cliente") }
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
                    Text(if (showArchived) "No hay clientes archivados." else "Todavía no hay clientes.")
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { client ->
                        Card(onClick = { onOpen(client.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text(client.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (client.type == "EMPRESA") "Empresa" else "Persona",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (client.phone.isNotBlank()) {
                                    Text(client.phone, style = MaterialTheme.typography.bodySmall)
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
fun ClientDetailScreen(
    client: ClientEntity?,
    onEdit: () -> Unit,
    onToggleArchive: (Boolean) -> Unit,
    onNewJob: () -> Unit,
    onBack: () -> Unit,
) {
    if (client == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    Scaffold(
        topBar = { TopAppBar(title = { Text(client.name) }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (client.type == "EMPRESA") "Empresa" else "Persona")
            if (client.phone.isNotBlank()) Text("Teléfono: ${client.phone}")
            if (client.email.isNotBlank()) Text("Correo: ${client.email}")
            if (client.address.isNotBlank()) Text("Dirección: ${client.address}")
            if (client.notes.isNotBlank()) Text("Notas: ${client.notes}")
            if (client.archived) {
                Text("Archivado", color = MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.height(8.dp))
            if (!client.archived) {
                Button(onClick = onNewJob) { Text("+ Trabajo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Editar") }
                OutlinedButton(onClick = { onToggleArchive(!client.archived) }) {
                    Text(if (client.archived) "Desarchivar" else "Archivar")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientFormScreen(
    initial: ClientEntity?,
    onSave: (ClientInput) -> Unit,
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var type by remember { mutableStateOf(initial?.type ?: ClientType.PERSONA.name) }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (initial == null) "Nuevo cliente" else "Editar cliente") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = type == ClientType.PERSONA.name,
                        onClick = { type = ClientType.PERSONA.name },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Persona") }
                    SegmentedButton(
                        selected = type == ClientType.EMPRESA.name,
                        onClick = { type = ClientType.EMPRESA.name },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    ) { Text("Empresa") }
                }
            }
            item {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nombre *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = phone, onValueChange = { phone = it },
                    label = { Text("Teléfono") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = email, onValueChange = { email = it },
                    label = { Text("Correo") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
            }
            item {
                OutlinedTextField(
                    value = address, onValueChange = { address = it },
                    label = { Text("Dirección") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Notas") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        val input = ClientInput(
                            type = ClientType.valueOf(type),
                            name = name,
                            phone = phone,
                            email = email,
                            address = address,
                            notes = notes,
                        )
                        val issue = validateClient(input)
                        if (issue != null) error = issue else onSave(input)
                    }) { Text("Guardar") }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

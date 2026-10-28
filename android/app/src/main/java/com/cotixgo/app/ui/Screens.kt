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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cotixgo.app.R
import com.cotixgo.app.core.ClientDocTypes
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
    var query by remember { mutableStateOf("") }
    val trimmedQuery = query.trim()
    val visible = clients
        .filter { it.archived == showArchived }
        .filter { c ->
            trimmedQuery.isEmpty() ||
                c.name.contains(trimmedQuery, ignoreCase = true) ||
                c.docNumber.contains(trimmedQuery, ignoreCase = true) ||
                c.phone.contains(trimmedQuery, ignoreCase = true) ||
                c.email.contains(trimmedQuery, ignoreCase = true)
        }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Clientes",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
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
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Nombre, teléfono o correo") },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Limpiar búsqueda")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
            if (visible.isEmpty()) {
                val (emptyTitle, emptyHint) = when {
                    trimmedQuery.isNotEmpty() ->
                        "Sin resultados para «$trimmedQuery»." to
                            "Prueba con otro nombre, teléfono o correo."
                    showArchived ->
                        "No hay clientes archivados." to
                            "Los clientes que archives aparecerán aquí."
                    else ->
                        "Todavía no hay clientes." to
                            "Toca + Cliente para crear el primero."
                }
                Box(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            emptyTitle,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            emptyHint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.id }) { client ->
                        ClientListCard(client = client, onClick = { onOpen(client.id) })
                    }
                }
            }
        }
    }
}

/** Tarjeta compacta de cliente: iniciales + nombre + tipo y contacto. */
@Composable
private fun ClientListCard(client: ClientEntity, onClick: () -> Unit) {
    val esEmpresa = client.type == "EMPRESA"
    val initials = client.name.trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    val tileColor = if (esEmpresa) CotixGoAmberSoft else CotixGoBlueSoft
    val tileTint = if (esEmpresa) MaterialTheme.colorScheme.tertiary else CotixGoBlue
    val secondary = listOf(
        if (esEmpresa) "Empresa" else "Persona",
        client.phone,
        client.email,
    ).filter { it.isNotBlank() }.joinToString(" · ")

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = DashboardCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DashboardIconTile(tileColor, tileTint) {
                Text(
                    initials,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = tileTint,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    client.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary.isNotBlank()) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
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
    val esEmpresa = client.type == "EMPRESA"
    val initials = client.name.trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifEmpty { "?" }
    val tileColor = if (esEmpresa) CotixGoAmberSoft else CotixGoBlueSoft
    val tileTint = if (esEmpresa) MaterialTheme.colorScheme.tertiary else CotixGoBlue

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Volver",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Cliente",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(14.dp),
                    color = tileColor,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            initials,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = tileTint,
                        )
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        client.name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (esEmpresa) "Empresa" else "Persona",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        if (client.archived) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    "Archivado",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            val contacto = listOfNotNull(
                if (client.docType.isNotBlank() || client.docNumber.isNotBlank()) Triple(
                    Icons.Filled.AccountBox, "Documento",
                    listOf(client.docType, client.docNumber)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                ) else null,
                if (client.phone.isNotBlank()) Triple(Icons.Filled.Phone, "Teléfono", client.phone) else null,
                if (client.email.isNotBlank()) Triple(Icons.Filled.MailOutline, "Correo", client.email) else null,
                if (client.address.isNotBlank()) Triple(Icons.Filled.LocationOn, "Dirección", client.address) else null,
            )
            if (contacto.isNotEmpty()) {
                Surface(
                    shape = DashboardCardShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        contacto.forEach { (icon, label, value) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Icon(
                                    icon,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Column {
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                    Text(
                                        value,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (client.notes.isNotBlank()) {
                Surface(
                    shape = DashboardCardShape,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "Notas",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            client.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            if (!client.archived) {
                Button(
                    onClick = onNewJob,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("+ Trabajo") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Text("Editar")
                }
                OutlinedButton(
                    onClick = { onToggleArchive(!client.archived) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (client.archived) "Desarchivar" else "Archivar")
                }
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
    var docType by remember { mutableStateOf(initial?.docType ?: "") }
    var docNumber by remember { mutableStateOf(initial?.docNumber ?: "") }
    var phone by remember { mutableStateOf(initial?.phone ?: "") }
    var email by remember { mutableStateOf(initial?.email ?: "") }
    var address by remember { mutableStateOf(initial?.address ?: "") }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (initial == null) "Nuevo cliente" else "Editar cliente",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
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
                        onClick = {
                            type = ClientType.PERSONA.name
                            if (docType !in ClientDocTypes.forType(ClientType.PERSONA)) docType = ""
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    ) { Text("Persona") }
                    SegmentedButton(
                        selected = type == ClientType.EMPRESA.name,
                        onClick = {
                            type = ClientType.EMPRESA.name
                            if (docType !in ClientDocTypes.forType(ClientType.EMPRESA)) docType = ""
                        },
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
                val baseOptions = ClientDocTypes.forType(ClientType.valueOf(type))
                // Un tipo ya guardado fuera de la lista (p. ej. de otra app)
                // se conserva como opción para no perderlo al editar.
                val options = if (docType.isNotBlank() && docType !in baseOptions) {
                    baseOptions + docType
                } else {
                    baseOptions
                }
                var docMenuOpen by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = docMenuOpen,
                    onExpandedChange = { docMenuOpen = it },
                ) {
                    OutlinedTextField(
                        value = docType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Tipo de documento *") },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = docMenuOpen)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true),
                        singleLine = true,
                    )
                    ExposedDropdownMenu(
                        expanded = docMenuOpen,
                        onDismissRequest = { docMenuOpen = false },
                    ) {
                        options.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = { docType = opt; docMenuOpen = false },
                            )
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = docNumber, onValueChange = { docNumber = it },
                    label = { Text("Número de documento *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
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
                            docType = docType,
                            docNumber = docNumber,
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

package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ClientType
import com.cotixgo.app.core.validateClient
import com.cotixgo.app.core.validateOnboarding
import com.cotixgo.app.data.ClientEntity

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

@Composable
private fun DashboardItemCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
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
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("CotixGo") },
                actions = { TextButton(onClick = onSettings) { Text("Ajustes") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Hola, $userName", style = MaterialTheme.typography.headlineSmall)
            if (syncPaused > 0) {
                // Enlace accionable a la pantalla de atención de sincronización.
                TextButton(onClick = onSyncAttention) {
                    Text(
                        "$syncPaused operaciones requieren atención",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            } else {
                Text(
                    when {
                        boundEmail == null -> "Sin cuenta vinculada"
                        syncPending > 0 -> "$syncPending operaciones pendientes de sincronizar"
                        else -> "Sincronizado"
                    },
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onNewClient, modifier = Modifier.weight(1f)) {
                    Text("+ Cliente")
                }
                Button(onClick = onNewQuote, modifier = Modifier.weight(1f)) {
                    Text("+ Cotización")
                }
            }
            OutlinedButton(onClick = onNewJob, modifier = Modifier.fillMaxWidth()) {
                Text("+ Trabajo")
            }
            OutlinedButton(onClick = onNewAccount, modifier = Modifier.fillMaxWidth()) {
                Text("+ Cuenta de Cobro")
            }
            if (loading) {
                Text("Cargando…", color = MaterialTheme.colorScheme.secondary)
            } else if (items.isEmpty) {
                Text("Sin pendientes por revisar.", color = MaterialTheme.colorScheme.secondary)
            }
            if (items.attentionQuotes.isNotEmpty()) {
                Text("Cotizaciones por vencer y vencidas", style = MaterialTheme.typography.titleSmall)
                items.attentionQuotes.forEach { q ->
                    val effective = com.cotixgo.app.core.effectiveQuoteStatus(
                        com.cotixgo.app.core.QuoteStatus.valueOf(q.status),
                        q.expiryDateEpochDay, todayEpochDay,
                    )
                    val date = java.time.LocalDate.ofEpochDay(q.expiryDateEpochDay)
                    DashboardItemCard(
                        title = "${q.officialNumber ?: q.provisionalNumber} — ${q.clientNameSnapshot}",
                        subtitle = if (effective == com.cotixgo.app.core.QuoteStatus.VENCIDA)
                            "Vencida: $date" else "Vence: $date",
                        onClick = { onOpenQuote(q.id) },
                    )
                }
            }
            if (items.activeJobs.isNotEmpty()) {
                Text("Trabajos programados y en proceso", style = MaterialTheme.typography.titleSmall)
                items.activeJobs.forEach { j ->
                    DashboardItemCard(
                        title = "${j.officialNumber ?: j.provisionalNumber} — ${j.clientNameSnapshot}",
                        subtitle = if (j.status == com.cotixgo.app.core.JobStatus.EN_PROCESO.name)
                            "En proceso" else "Programado",
                        onClick = { onOpenJob(j.id) },
                    )
                }
            }
            if (items.draftReports.isNotEmpty()) {
                Text("Informes pendientes", style = MaterialTheme.typography.titleSmall)
                items.draftReports.forEach { r ->
                    DashboardItemCard(
                        title = "${r.officialNumber ?: r.provisionalNumber} — ${r.title}",
                        subtitle = r.clientNameSnapshot,
                        onClick = { onOpenReport(r.id) },
                    )
                }
            }
            if (items.openAccounts.isNotEmpty()) {
                Text("Cuentas por cobrar", style = MaterialTheme.typography.titleSmall)
                items.openAccounts.forEach { a ->
                    DashboardItemCard(
                        title = "${a.officialNumber ?: a.provisionalNumber} — ${a.clientNameSnapshot}",
                        subtitle = "Saldo: ${a.balance} ${a.currency}",
                        onClick = { onOpenAccount(a.id) },
                    )
                }
            }
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

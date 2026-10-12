package com.cotixgo.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.cotixgo.app.data.QuoteVersionEntity
import com.cotixgo.app.data.SyncConflictEntity
import com.cotixgo.app.data.SyncOperationEntity

// ---------- Cuenta y sincronización ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountScreen(
    boundEmail: String?,
    pendingOps: Int,
    pausedOps: Int,
    apiBaseUrl: String,
    syncing: Boolean,
    lastMessage: String?,
    onRegister: (name: String, email: String, pass: String) -> Unit,
    onLogin: (email: String, pass: String) -> Unit,
    onRecover: (email: String) -> Unit,
    onSyncNow: () -> Unit,
    onSwitchAccount: (currentPass: String, newEmail: String, newPass: String, confirmed: Boolean) -> Unit,
    onSetBaseUrl: (String) -> Unit,
    onConflicts: () -> Unit,
    onBack: () -> Unit,
) {
    var mode by remember { mutableStateOf("menu") } // menu | register | login | recover | switch
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var newEmail by remember { mutableStateOf("") }
    var newPass by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var baseUrl by remember { mutableStateOf(apiBaseUrl) }

    Scaffold(topBar = { TopAppBar(title = { Text("Cuenta y sincronización") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (boundEmail == null) {
                Text("Sin cuenta vinculada", style = MaterialTheme.typography.titleMedium)
                Text(
                    "El trabajo local sigue disponible sin conexión. Vincular una cuenta activa la sincronización con el servidor.",
                    color = MaterialTheme.colorScheme.secondary,
                )
                when (mode) {
                    "register" -> {
                        OutlinedTextField(value = name, onValueChange = { name = it },
                            label = { Text("Tu nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = email, onValueChange = { email = it },
                            label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = pass, onValueChange = { pass = it },
                            label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { onRegister(name, email, pass) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Crear cuenta y vincular")
                        }
                        TextButton(onClick = { mode = "menu" }) { Text("Volver") }
                    }
                    "login" -> {
                        OutlinedTextField(value = email, onValueChange = { email = it },
                            label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        OutlinedTextField(value = pass, onValueChange = { pass = it },
                            label = { Text("Contraseña") }, visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { onLogin(email, pass) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Entrar y vincular")
                        }
                        TextButton(onClick = { mode = "recover" }) { Text("Recuperar acceso") }
                        TextButton(onClick = { mode = "menu" }) { Text("Volver") }
                    }
                    "recover" -> {
                        Text("Recuperación por correo (simulada en desarrollo local: no se envía correo real).",
                            color = MaterialTheme.colorScheme.secondary)
                        OutlinedTextField(value = email, onValueChange = { email = it },
                            label = { Text("Correo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Button(onClick = { onRecover(email) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Solicitar recuperación")
                        }
                        TextButton(onClick = { mode = "login" }) { Text("Volver") }
                    }
                    else -> {
                        Button(onClick = { mode = "register" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Crear cuenta")
                        }
                        OutlinedButton(onClick = { mode = "login" }, modifier = Modifier.fillMaxWidth()) {
                            Text("Iniciar sesión")
                        }
                    }
                }
            } else {
                Text("Cuenta vinculada", style = MaterialTheme.typography.titleMedium)
                Text(boundEmail, color = MaterialTheme.colorScheme.secondary)
                Text(
                    "Pendientes: $pendingOps · Requieren atención: $pausedOps",
                    color = if (pausedOps > 0) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary,
                )
                Button(
                    onClick = onSyncNow,
                    enabled = !syncing,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (syncing) "Sincronizando…" else "Sincronizar ahora") }
                if (pausedOps > 0) {
                    OutlinedButton(onClick = onConflicts, modifier = Modifier.fillMaxWidth()) {
                        Text("Ver operaciones que requieren atención")
                    }
                }
                HorizontalDivider()
                Text("Cambiar de cuenta", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Exige reautenticación y cola vacía. La copia local se limpia y se descargan los datos de la cuenta nueva.",
                    color = MaterialTheme.colorScheme.secondary,
                )
                if (mode == "switch") {
                    OutlinedTextField(value = pass, onValueChange = { pass = it },
                        label = { Text("Contraseña actual") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = newEmail, onValueChange = { newEmail = it },
                        label = { Text("Correo de la cuenta nueva") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(value = newPass, onValueChange = { newPass = it },
                        label = { Text("Contraseña de la cuenta nueva") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = confirmed, onCheckedChange = { confirmed = it })
                        Text("Confirmo que se limpiarán los datos locales tras la verificación")
                    }
                    Button(
                        onClick = { onSwitchAccount(pass, newEmail, newPass, confirmed) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Verificar y cambiar de cuenta") }
                    TextButton(onClick = { mode = "menu" }) { Text("Cancelar") }
                } else {
                    OutlinedButton(onClick = { mode = "switch" }, modifier = Modifier.fillMaxWidth()) {
                        Text("Cambiar de cuenta…")
                    }
                }
            }
            HorizontalDivider()
            Text("Servidor (desarrollo local)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = baseUrl, onValueChange = { baseUrl = it },
                label = { Text("URL base de la API") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedButton(onClick = { onSetBaseUrl(baseUrl) }) { Text("Guardar URL") }
            lastMessage?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = onBack) { Text("Volver") }
        }
    }
}

// ---------- Conflictos y operaciones pausadas ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictsScreen(
    conflicts: List<SyncConflictEntity>,
    paused: List<SyncOperationEntity>,
    versionConflictQuotes: List<Pair<String, String>>, // (quoteId, número)
    onKeepLocal: (Long) -> Unit,
    onKeepRemote: (Long) -> Unit,
    onRetry: (String) -> Unit,
    onResolveVersions: (quoteId: String) -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Sincronización: atención requerida") }) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(versionConflictQuotes) { (quoteId, number) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Cotización $number", style = MaterialTheme.typography.titleMedium)
                        Text("Versiones concurrentes conservadas. Elige la versión a conservar; se creará una versión nueva de resolución.",
                            color = MaterialTheme.colorScheme.secondary)
                        Button(onClick = { onResolveVersions(quoteId) }) { Text("Resolver versiones") }
                    }
                }
            }
            items(conflicts) { c ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${c.entityType} ${c.entityId.take(8)}…", style = MaterialTheme.typography.titleMedium)
                        Text("El servidor tiene una copia distinta (v${c.remoteServerVersion}). Ambas copias están conservadas.",
                            color = MaterialTheme.colorScheme.secondary)
                        Text("Remota: ${c.remoteJson.take(300)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { onKeepLocal(c.id) }) { Text("Conservar local") }
                            OutlinedButton(onClick = { onKeepRemote(c.id) }) { Text("Conservar remota") }
                        }
                    }
                }
            }
            items(paused) { op ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${op.entityType} · ${op.resultCode ?: "PAUSED"}",
                            style = MaterialTheme.typography.titleMedium)
                        Text("Intentos: ${op.attempts}", color = MaterialTheme.colorScheme.secondary)
                        op.resultJson?.let {
                            Text(it.take(300), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                        OutlinedButton(onClick = { onRetry(op.opId) }) { Text("Reintentar") }
                    }
                }
            }
            item {
                if (conflicts.isEmpty() && paused.isEmpty() && versionConflictQuotes.isEmpty()) {
                    Text("Sin elementos pendientes de atención.")
                }
                TextButton(onClick = onBack) { Text("Volver") }
            }
        }
    }
}

// ---------- Resolución de versiones de cotización ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveVersionsScreen(
    quoteNumber: String,
    versions: List<QuoteVersionEntity>,
    onPick: (versionRowId: String) -> Unit,
    onBack: () -> Unit,
) {
    val ordered = versions.sortedWith(
        compareByDescending<QuoteVersionEntity> { it.version }
            .thenByDescending { it.serverOrder ?: 0 }
            .thenByDescending { it.createdAt },
    )
    val duplicatedNumbers = versions.groupingBy { it.version }.eachCount().filter { it.value > 1 }.keys
    Scaffold(topBar = { TopAppBar(title = { Text("Resolver versiones · $quoteNumber") }) }) { padding ->
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
                        Text(v.snapshotJson.take(200), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary)
                        Button(onClick = { onPick(v.id) }) { Text("Conservar esta versión") }
                    }
                }
            }
            item { TextButton(onClick = onBack) { Text("Volver") } }
        }
    }
}

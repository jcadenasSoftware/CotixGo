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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.produceState
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
    versionConflictReports: List<Pair<String, String>>, // (reportId, número)
    detailOf: suspend (SyncOperationEntity) -> com.cotixgo.app.data.SyncRepository.PausedOpView,
    previewOf: suspend (SyncOperationEntity) -> com.cotixgo.app.data.SyncRepository.DiscardPreview,
    onKeepLocal: (Long) -> Unit,
    onKeepRemote: (Long) -> Unit,
    onRetry: (String) -> Unit,
    onRestoreRemote: (String, Set<String>) -> Unit,
    onAcknowledge: (String) -> Unit,
    onDiscard: (String, Set<String>) -> Unit,
    onResolveVersions: (quoteId: String) -> Unit,
    onResolveReportVersions: (reportId: String) -> Unit,
    onBack: () -> Unit,
) {
    var discardOp by remember { mutableStateOf<SyncOperationEntity?>(null) }
    var restoreWithPending by remember {
        mutableStateOf<com.cotixgo.app.data.SyncRepository.PausedOpView?>(null)
    }
    val restoreTarget = restoreWithPending
    if (restoreTarget != null) {
        // Restaurar descarta las ops locales aún en cola de esa entidad:
        // la confirmación anuncia el conjunto exacto que se perderá.
        AlertDialog(
            onDismissRequest = { restoreWithPending = null },
            title = { Text("Restaurar estado remoto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "La entidad quedará como la tiene el servidor. ${restoreTarget.pendingOpsOnEntity.size} operación(es) local(es) aún en cola se descartarán y no se aplicarán; su contenido queda en el historial de operaciones.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onRestoreRemote(
                        restoreTarget.op.opId,
                        restoreTarget.pendingOpsOnEntity.toSet(),
                    )
                    restoreWithPending = null
                }) { Text("Restaurar y descartar cola") }
            },
            dismissButton = {
                TextButton(onClick = { restoreWithPending = null }) { Text("Cancelar") }
            },
        )
    }
    val discardOpNow = discardOp
    if (discardOpNow != null) {
        val preview by produceState<com.cotixgo.app.data.SyncRepository.DiscardPreview?>(
            null, discardOpNow.opId,
        ) { value = previewOf(discardOpNow) }
        AlertDialog(
            onDismissRequest = { discardOp = null },
            title = { Text("Descartar datos locales") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val pv = preview
                    if (pv == null) {
                        Text("Calculando vista previa…")
                    } else {
                        Text(
                            "Se eliminarán ${pv.rows.size} fila(s) locales de esta entidad y sus dependencias. El historial de operaciones se conserva.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        pv.rows.take(8).forEach { row ->
                            Text(
                                "· ${row.entityType} — ${row.id.take(8)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                        if (pv.rows.size > 8) {
                            Text("… y ${pv.rows.size - 8} más",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (pv.discardedOps.isNotEmpty()) {
                            Text(
                                "Se descartarán ${pv.discardedOps.size} operación(es) en cola de esta entidad y su cascada (su historial se conserva):",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            pv.discardedOps.take(8).forEach { o ->
                                Text(
                                    "· ${o.entityType} — op ${o.opId.take(8)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            if (pv.discardedOps.size > 8) {
                                Text("… y ${pv.discardedOps.size - 8} más",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (pv.waitingDependents.isNotEmpty()) {
                            Text(
                                "${pv.waitingDependents.size} operación(es) que esperaban este dato quedarán marcadas como sin prerequisito.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        pv.blockers.forEach {
                            Text(it, color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                val pv = preview
                Button(
                    enabled = pv != null && pv.blockers.isEmpty(),
                    onClick = {
                        onDiscard(discardOpNow.opId, pv?.signature() ?: emptySet())
                        discardOp = null
                    },
                ) { Text("Descartar definitivamente") }
            },
            dismissButton = {
                TextButton(onClick = { discardOp = null }) { Text("Cancelar") }
            },
        )
    }
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
            items(versionConflictReports) { (reportId, number) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Informe $number", style = MaterialTheme.typography.titleMedium)
                        Text("Versiones concurrentes conservadas. Elige la versión a conservar; se creará una versión nueva de resolución.",
                            color = MaterialTheme.colorScheme.secondary)
                        Button(onClick = { onResolveReportVersions(reportId) }) { Text("Resolver versiones") }
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
                val detail by produceState<com.cotixgo.app.data.SyncRepository.PausedOpView?>(
                    null, op.opId, op.updatedAt,
                ) { value = detailOf(op) }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${op.entityType} · ${detail?.label ?: op.entityId.take(8)}",
                            style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (op.resultCode) {
                                "INTERNAL_ERROR" ->
                                    if (detail?.internalExhausted == true) {
                                        "Fallo interno del servidor tras ${op.attempts} intentos automáticos; no es un rechazo de negocio."
                                    } else {
                                        "Fallo interno del servidor (reintento automático programado)."
                                    }
                                "DEPENDENCY" -> "Espera un prerequisito que aún no se aplica en el servidor."
                                "CONFLICT_BLOCKED" -> "Bloqueada por un conflicto de versiones sin resolver."
                                "VALIDATION_ERROR" -> "El servidor rechazó los datos; corrige la entidad o descarta."
                                "INVALID_TRANSITION" -> "El estado pedido no aplica sobre el estado actual del servidor."
                                "FORBIDDEN_ENTITY" -> "La entidad pertenece a otra cuenta; no se reenvía ni se copia."
                                "IMMUTABLE_FINANCIAL" -> "Evento financiero inmutable: la corrección es un evento nuevo."
                                "IDEMPOTENCY_KEY_REUSED" -> "La operación ya fue procesada con otro contenido."
                                "PREREQUISITE_DISCARDED" -> "Su prerequisito fue descartado; resuélvela por separado."
                                else -> op.resultCode ?: "Pausada"
                            },
                            color = MaterialTheme.colorScheme.secondary,
                        )
                        if ((detail?.waitingDependents ?: 0) > 0) {
                            Text("${detail!!.waitingDependents} operación(es) esperan a esta.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                        op.resultJson?.let {
                            Text(it.take(300), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary)
                        }
                        detail?.let { d ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (d.canReissue || op.resultCode == "INTERNAL_ERROR") {
                                    OutlinedButton(onClick = { onRetry(op.opId) }) {
                                        Text(if (op.resultCode == "INTERNAL_ERROR") "Reintentar ahora" else "Reintentar")
                                    }
                                }
                                if (d.canRestoreRemote) {
                                    OutlinedButton(onClick = {
                                        if (d.pendingOpsOnEntity.isEmpty()) {
                                            onRestoreRemote(op.opId, emptySet())
                                        } else {
                                            restoreWithPending = d
                                        }
                                    }) {
                                        Text("Restaurar remoto")
                                    }
                                }
                                if (d.canAcknowledge) {
                                    OutlinedButton(onClick = { onAcknowledge(op.opId) }) {
                                        Text("Marcar rechazado")
                                    }
                                }
                                if (d.canDiscard) {
                                    TextButton(onClick = { discardOp = op }) {
                                        Text("Descartar…", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                if (conflicts.isEmpty() && paused.isEmpty() && versionConflictQuotes.isEmpty()
                    && versionConflictReports.isEmpty()) {
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

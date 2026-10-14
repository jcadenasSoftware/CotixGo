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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.ConsolidatedCollectionEntity
import com.cotixgo.app.data.ConsolidatedInput
import com.cotixgo.app.data.PaymentEntity
import java.time.LocalDate

private fun consolidatedLabel(status: CollectionAccountStatus): String = when (status) {
    CollectionAccountStatus.PENDIENTE -> "Pendiente"
    CollectionAccountStatus.PARCIAL -> "Parcial"
    CollectionAccountStatus.PAGADA -> "Pagada"
    CollectionAccountStatus.CANCELADA -> "Cancelada"
}

// ---------- Editor de Consolidado ----------

/**
 * Crea/edita la membresía de un Consolidado (F7, docs/03 §6): ≥2 Cuentas
 * del mismo Cliente y moneda, no canceladas y con saldo > 0; una Cuenta
 * en otro Consolidado activo no aparece como elegible. Los importes se
 * derivan de los saldos vigentes (vista previa); el servidor recalcula.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolidatedEditorScreen(
    initial: ConsolidatedCollectionEntity?,
    initialMemberIds: List<String>,
    clients: List<ClientEntity>,
    accounts: List<CollectionAccountEntity>,
    memberAccountIds: Set<String>,
    onSave: (ConsolidatedInput, (String?) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    var clientId by remember { mutableStateOf(initial?.clientId ?: "") }
    var currency by remember { mutableStateOf(initial?.currency ?: "") }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialMemberIds) } }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }

    // Elegibles: mismo Cliente, no canceladas, saldo > 0, sin vínculo a
    // otro Consolidado activo (las propias cuentan). La moneda queda fijada
    // por la primera Cuenta seleccionada.
    val eligible = accounts.filter {
        it.clientId == clientId && !it.archived &&
            it.status != CollectionAccountStatus.CANCELADA.name &&
            it.balance.toMoney().signum() > 0 &&
            (currency.isBlank() || it.currency == currency) &&
            (it.id !in memberAccountIds || it.id in selected)
    }
    val selectedAccounts = eligible.filter { it.id in selected }
    val previewTotal = selectedAccounts.fold(java.math.BigDecimal.ZERO) { acc, a ->
        acc.add(a.balance.toMoney())
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "Nuevo consolidado" else "Editar consolidado") },
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
                    OutlinedButton(
                        onClick = { clientMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = initial == null, // el Cliente fija la membresía; no se cambia al editar
                    ) {
                        Text(clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *")
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        clients.filter { !it.archived }.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = {
                                clientId = c.id; selected.clear(); clientMenu = false
                            })
                        }
                    }
                }
            }
            item {
                Text(
                    "Cuentas elegibles (misma moneda; saldo pendiente > 0; sin consolidado activo)",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (clientId.isBlank()) {
                item { Text("Elige primero el cliente.", style = MaterialTheme.typography.bodySmall) }
            } else if (eligible.isEmpty()) {
                item { Text("No hay cuentas elegibles.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(eligible, key = { it.id }) { a ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Checkbox(
                                checked = a.id in selected,
                                onCheckedChange = {
                                    if (a.id in selected) {
                                        selected.remove(a.id)
                                        if (selected.isEmpty()) currency = ""
                                    } else {
                                        if (currency.isBlank()) currency = a.currency
                                        selected.add(a.id)
                                    }
                                },
                            )
                            Column {
                                Text(a.officialNumber ?: a.provisionalNumber)
                                Text(
                                    "Saldo ${a.currency} ${a.balance} · " +
                                        consolidatedLabel(CollectionAccountStatus.valueOf(a.status)),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
            if (selected.size >= 2) {
                item {
                    Text(
                        "Total consolidado (vista previa): ${currency.ifBlank { "—" }} $previewTotal",
                        style = MaterialTheme.typography.titleSmall,
                    )
                }
            }
            item {
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = {
                        when {
                            clientId.isBlank() -> error = "El cliente es obligatorio."
                            selected.size < 2 -> error = "El consolidado requiere al menos dos cuentas."
                            else -> {
                                error = null
                                // Null = rechazado por elegibilidad/estado: el
                                // usuario permanece en el editor con el aviso.
                                onSave(
                                    ConsolidatedInput(
                                        clientId = clientId,
                                        accountIds = selected.toList(),
                                        currency = currency,
                                    ),
                                ) { savedId ->
                                    if (savedId == null) {
                                        error = "No se pudo guardar el consolidado: " +
                                            "revisa que las cuentas sigan elegibles."
                                    }
                                }
                            }
                        }
                    }) { Text("Guardar") }
                    TextButton(onClick = onBack) { Text("Cancelar") }
                }
            }
        }
    }
}

// ---------- Detalle de Consolidado ----------

/**
 * Detalle del Consolidado: total, aplicado y saldo derivados; membresía
 * histórica (se conserva tras la cancelación) y pagos aplicados. Edición
 * de miembros y cancelación solo en PENDIENTE sin pagos; archivar siempre.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConsolidatedDetailScreen(
    consolidated: ConsolidatedCollectionEntity?,
    memberAccounts: List<CollectionAccountEntity>,
    payments: List<PaymentEntity>,
    onEdit: () -> Unit,
    onCancelConsolidated: ((Boolean) -> Unit) -> Unit,
    onSetArchived: (Boolean) -> Unit,
    onOpenAccount: (String) -> Unit,
    onOpenPayment: (String) -> Unit,
    onBack: () -> Unit,
) {
    if (consolidated == null) {
        Scaffold(topBar = { TopAppBar(title = { Text("Consolidado") }) }) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Consolidado no encontrado.")
            }
        }
        return
    }
    val status = CollectionAccountStatus.valueOf(consolidated.status)
    val editable = status == CollectionAccountStatus.PENDIENTE &&
        consolidated.appliedAmount.toMoney().signum() <= 0
    var confirmCancel by remember { mutableStateOf(false) }
    var cancelError by remember { mutableStateOf<String?>(null) }

    if (confirmCancel) {
        AlertDialog(
            onDismissRequest = { confirmCancel = false; cancelError = null },
            title = { Text("Cancelar consolidado") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("La cancelación libera las cuentas miembro y conserva la membresía histórica. No se puede deshacer.")
                    cancelError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCancelConsolidated { ok ->
                        if (ok) { confirmCancel = false; cancelError = null }
                        else {
                            cancelError = "No se pudo cancelar: solo un consolidado " +
                                "PENDIENTE sin pagos aplicados puede cancelarse."
                        }
                    }
                }) {
                    Text("Cancelar consolidado")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancel = false; cancelError = null }) { Text("Volver") }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(consolidated.officialNumber ?: consolidated.provisionalNumber) },
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
                    "${consolidatedLabel(status)}${if (consolidated.archived) " · Archivado" else ""}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Cliente: ${consolidated.clientNameSnapshot}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Total: ${consolidated.currency} ${consolidated.totalAmount} · " +
                        "Aplicado: ${consolidated.appliedAmount} · Saldo: ${consolidated.balance}",
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            item { Text("Cuentas miembro", style = MaterialTheme.typography.titleSmall) }
            items(memberAccounts, key = { it.id }) { a ->
                Card(onClick = { onOpenAccount(a.id) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(a.officialNumber ?: a.provisionalNumber)
                        Text(
                            "Neto ${a.net} · Saldo ${a.balance} ${a.currency} · " +
                                consolidatedLabel(CollectionAccountStatus.valueOf(a.status)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            if (payments.isNotEmpty()) {
                item { Text("Pagos aplicados", style = MaterialTheme.typography.titleSmall) }
                items(payments, key = { it.id }) { p ->
                    Card(onClick = { onOpenPayment(p.id) }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${p.amount} · ${LocalDate.ofEpochDay(p.paidAtEpochDay)}")
                            Text(
                                "Aplicado aquí: ${p.appliedAmount}" +
                                    (p.reference.takeIf { it.isNotBlank() }?.let { " · Ref. $it" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editable) {
                        OutlinedButton(onClick = onEdit) { Text("Editar miembros") }
                        OutlinedButton(onClick = { confirmCancel = true }) { Text("Cancelar consolidado") }
                    }
                    OutlinedButton(onClick = { onSetArchived(!consolidated.archived) }) {
                        Text(if (consolidated.archived) "Desarchivar" else "Archivar")
                    }
                }
            }
        }
    }
}

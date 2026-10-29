package com.cotixgo.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cotixgo.app.BuildConfig
import com.cotixgo.app.core.ConceptType
import java.math.BigDecimal
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale
import com.cotixgo.app.core.MaterialOrigin
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.effectiveQuoteStatus
import com.cotixgo.app.core.isValidCurrencyCode
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.QuoteLineInput
import com.cotixgo.app.data.QuoteSnapshot
import com.cotixgo.app.data.QuoteVersionEntity
import com.cotixgo.app.data.RetentionConfigEntity
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.ServiceEntity
import com.cotixgo.app.data.ServiceInput
import com.cotixgo.app.data.UserEntity
import java.time.LocalDate

private fun label(status: QuoteStatus): String = when (status) {
    QuoteStatus.BORRADOR -> "Borrador"
    QuoteStatus.EMITIDA -> "Emitida"
    QuoteStatus.APROBADA -> "Aprobada"
    QuoteStatus.RECHAZADA -> "Rechazada"
    QuoteStatus.VENCIDA -> "Vencida"
    QuoteStatus.CANCELADA -> "Cancelada"
}

private fun QuoteEntity.effectiveStatus(today: Long = LocalDate.now().toEpochDay()): QuoteStatus =
    effectiveQuoteStatus(QuoteStatus.valueOf(status), expiryDateEpochDay, today)

// ---------- Lista de Cotizaciones ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuotesListScreen(
    quotes: List<QuoteEntity>,
    onOpen: (String) -> Unit,
    onNew: () -> Unit,
) {
    var tab by remember { mutableStateOf(0) } // 0 Abiertas, 1 Cerradas, 2 Archivadas
    val visible = quotes.filter {
        if (tab == 2) it.archived else !it.archived && when (tab) {
            0 -> it.effectiveStatus() in setOf(QuoteStatus.BORRADOR, QuoteStatus.EMITIDA, QuoteStatus.RECHAZADA)
            else -> it.effectiveStatus() in setOf(QuoteStatus.APROBADA, QuoteStatus.VENCIDA, QuoteStatus.CANCELADA)
        }
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
                        "Cotizaciones",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        floatingActionButton = { Button(onClick = onNew) { Text("+ Cotización") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Abiertas", "Cerradas", "Archivadas").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = tab == i,
                        onClick = { tab = i },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3),
                    ) { Text(t) }
                }
            }
            if (visible.isEmpty()) {
                val (emptyTitle, emptyHint) = when (tab) {
                    0 -> "No hay cotizaciones abiertas." to
                        "Crea la primera con + Cotización."
                    1 -> "No hay cotizaciones cerradas." to
                        "Las aprobadas, vencidas o canceladas aparecerán aquí."
                    else -> "No hay cotizaciones archivadas." to
                        "Las que archives aparecerán aquí."
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
                    items(visible, key = { it.id }) { q ->
                        QuoteListCard(quote = q, onClick = { onOpen(q.id) })
                    }
                }
            }
        }
    }
}

/** Tarjeta compacta de cotización: número, cliente, vencimiento | estado, total. */
@Composable
private fun QuoteListCard(quote: QuoteEntity, onClick: () -> Unit) {
    val effective = quote.effectiveStatus()
    val (statusBg, statusFg) = when (effective) {
        QuoteStatus.EMITIDA -> CotixGoBlueSoft to CotixGoBlue
        QuoteStatus.APROBADA -> CotixGoGreenSoft to CotixGoGreen
        QuoteStatus.RECHAZADA, QuoteStatus.VENCIDA ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceVariant to
            MaterialTheme.colorScheme.onSurfaceVariant
    }
    val number = quote.officialNumber ?: quote.provisionalNumber
    val expiryText = when (effective) {
        QuoteStatus.VENCIDA -> "Vencida: ${dashboardDayLabel(quote.expiryDateEpochDay)}"
        QuoteStatus.BORRADOR, QuoteStatus.EMITIDA, QuoteStatus.RECHAZADA ->
            "Vence: ${dashboardDayLabel(quote.expiryDateEpochDay)}"
        else -> null
    }
    val expiryColor = if (effective == QuoteStatus.VENCIDA) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.secondary
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = QuoteCardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 104.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = RoundedCornerShape(10.dp),
                color = statusBg,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = statusFg,
                    )
                }
            }
            Column(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (quote.title.isBlank()) {
                    Text(
                        "Sin título",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        quote.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    number,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    quote.clientNameSnapshot,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (expiryText != null) {
                    Text(
                        expiryText,
                        style = MaterialTheme.typography.bodySmall,
                        color = expiryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Surface(shape = RoundedCornerShape(50), color = statusBg) {
                    Text(
                        label(effective),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = statusFg,
                    )
                }
                Text(
                    quoteAmountLabel(quote.net, quote.currency),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = CotixGoNavy,
                    maxLines = 1,
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

private val QuoteCardShape = RoundedCornerShape(14.dp)

/** Símbolo corto por código ISO de moneda; vacío si no hay uno conocido. */
private fun quoteCurrencySymbol(code: String): String = when (code) {
    "COP" -> "$"
    "USD" -> "US$"
    "EUR" -> "€"
    else -> ""
}

/** Importe localizado sin moneda: `180.000,00`. Solo presentación. */
private fun fmtQuoteAmount(amount: String): String = try {
    DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale("es", "CO")))
        .format(BigDecimal(amount))
} catch (e: Exception) {
    amount
}

/** Formato visual del importe: `$ 180.000,00 COP`. Solo presentación. */
private fun quoteAmountLabel(amount: String, currency: String): String {
    val formatted = fmtQuoteAmount(amount)
    val symbol = quoteCurrencySymbol(currency)
    return if (symbol.isEmpty()) "$formatted $currency" else "$symbol $formatted $currency"
}

/** Fecha ISO `2026-10-26` → formato localizado `26 oct 2026`. */
private fun fmtIsoDay(iso: String): String = try {
    dashboardDayLabel(LocalDate.parse(iso).toEpochDay())
} catch (e: Exception) {
    iso
}

// ---------- Editor de Cotización ----------

class LineDraft {
    var conceptType by mutableStateOf(ConceptType.SERVICIO)
    var description by mutableStateOf("")
    var quantity by mutableStateOf("1")
    var unit by mutableStateOf("")
    var unitPrice by mutableStateOf("")
    var serviceRef by mutableStateOf<String?>(null)
    var materialOrigin by mutableStateOf<MaterialOrigin?>(null)
    var saveToCatalog by mutableStateOf(false)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteEditorScreen(
    initial: QuoteSnapshot?,
    clients: List<ClientEntity>,
    profile: ProfileEntity?,
    user: UserEntity?,
    retentions: List<RetentionConfigEntity>,
    services: List<ServiceEntity>,
    lacksVersionSnapshot: Boolean = false,
    onSave: (QuoteInput, List<ServiceInput>) -> Unit,
    onBack: () -> Unit,
) {
    var clientId by remember { mutableStateOf(initial?.client?.id ?: "") }
    var title by remember { mutableStateOf(initial?.title ?: "") }
    // Un default histórico inválido (<1) no precarga: el campo queda vacío
    // para que el usuario escriba una vigencia válida (requerida al guardar).
    var validity by remember {
        mutableStateOf(initial?.validityDays?.toString()
            ?: (profile?.vigenciaDias?.takeIf { it >= 1 }?.toString() ?: ""))
    }
    var currency by remember { mutableStateOf(initial?.currency ?: (user?.mainCurrency ?: "")) }
    var notes by remember { mutableStateOf(initial?.notes ?: "") }
    val lines = remember {
        mutableStateListOf<LineDraft>().apply {
            initial?.lines?.forEach { l ->
                add(LineDraft().apply {
                    conceptType = ConceptType.valueOf(l.conceptType)
                    description = l.description
                    quantity = l.quantity
                    unit = l.unit
                    unitPrice = l.unitPrice
                    serviceRef = l.serviceRef
                    materialOrigin = l.materialOrigin?.let(MaterialOrigin::valueOf)
                })
            }
        }
    }
    val selectedRetentions = remember {
        mutableStateListOf<String>().apply {
            initial?.retentions?.forEach { r -> add(r.name) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var clientMenu by remember { mutableStateOf(false) }
    var lineDialog by remember { mutableStateOf<LineDraft?>(null) }
    var editIndex by remember { mutableStateOf(-1) }
    var catalogDialog by remember { mutableStateOf(false) }
    var targetNet by remember { mutableStateOf("") }
    var inverseResult by remember { mutableStateOf<String?>(null) }

    val issueDay = initial?.issueDate?.let { LocalDate.parse(it).toEpochDay() }
        ?: LocalDate.now().toEpochDay()
    val validityDays = validity.toIntOrNull() ?: 0
    val expiryDay = issueDay + validityDays - 1

    val calcLines = lines.map {
        com.cotixgo.app.core.CalcLine(it.conceptType, it.quantity.toQty(), it.unitPrice.toMoney())
    }
    val calcSpecs = retentions.filter { it.name in selectedRetentions }.map {
        com.cotixgo.app.core.RetentionSpec(
            it.name, it.ratePercent.toQty(),
            it.appliesTo.split(",").map(com.cotixgo.app.core.ConceptType::valueOf).toSet(),
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
                        label = { Text("Precio bruto") }, modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    if (draft.conceptType == ConceptType.MATERIAL) {
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf("Del cliente", "Propio").forEachIndexed { i, t ->
                                SegmentedButton(
                                    selected = draft.materialOrigin == MaterialOrigin.entries[i],
                                    onClick = { draft.materialOrigin = MaterialOrigin.entries[i] },
                                    shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                                ) { Text(t) }
                            }
                        }
                    }
                    if (draft.conceptType == ConceptType.SERVICIO && draft.serviceRef == null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = draft.saveToCatalog, onCheckedChange = { draft.saveToCatalog = it })
                            Text("Guardar en el catálogo")
                        }
                    }
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

    if (catalogDialog) {
        val active = services.filter { !it.archived }
        AlertDialog(
            onDismissRequest = { catalogDialog = false },
            title = { Text("Servicio del catálogo") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (active.isEmpty()) Text("El catálogo no tiene servicios activos.")
                    active.forEach { s ->
                        TextButton(onClick = {
                            lines.add(LineDraft().apply {
                                conceptType = ConceptType.SERVICIO
                                description = s.name
                                unitPrice = s.suggestedPrice
                                serviceRef = s.id
                            })
                            catalogDialog = false
                        }) { Text(s.name) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { catalogDialog = false }) { Text("Cerrar") } },
        )
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                    Text(
                        if (initial == null) "Nueva cotización" else "Editar cotización",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { QuoteFormSectionTitle("Datos") }
            item {
                Box {
                    OutlinedButton(onClick = { clientMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            clients.firstOrNull { it.id == clientId }?.name ?: "Cliente *",
                        )
                    }
                    DropdownMenu(expanded = clientMenu, onDismissRequest = { clientMenu = false }) {
                        if (clients.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text("No hay clientes disponibles") },
                                onClick = {}, enabled = false,
                            )
                        }
                        clients.forEach { c ->
                            DropdownMenuItem(text = { Text(c.name) }, onClick = { clientId = c.id; clientMenu = false })
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = title, onValueChange = { title = it },
                    label = { Text("Título de la cotización *") },
                    placeholder = { Text("Ej.: Instalación de cámaras") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
            item { QuoteFormSectionTitle("Vigencia y moneda") }
            item {
                Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        QuoteDetailField("Perfil", profile?.businessName ?: "—")
                        QuoteDetailField("Emisión", dashboardDayLabel(issueDay))
                        if (validityDays > 0) {
                            QuoteDetailField("Vence", dashboardDayLabel(expiryDay))
                        }
                    }
                }
            }
            item {
                OutlinedTextField(
                    value = validity, onValueChange = { validity = it.filter(Char::isDigit) },
                    label = { Text("Vigencia (días) *") }, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            item {
                OutlinedTextField(
                    value = currency, onValueChange = { currency = it.uppercase().take(3) },
                    label = { Text("Moneda") },
                    supportingText = { Text("Código de moneda, como COP o USD") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { QuoteFormSectionTitle("Conceptos") }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { lineDialog = LineDraft() }, modifier = Modifier.weight(1f)) { Text("+ Línea manual") }
                    OutlinedButton(onClick = { catalogDialog = true }, modifier = Modifier.weight(1f)) { Text("Desde catálogo") }
                }
            }
            if (lines.isEmpty()) {
                item { QuoteDetailEmpty("Sin conceptos todavía. Agrega una línea manual o desde el catálogo.") }
            }
            items(lines.size) { i ->
                val l = lines[i]
                Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    l.description.ifBlank { "(sin descripción)" },
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "[${l.conceptType.name}] · ${l.quantity} ${l.unit} × $ ${fmtQuoteAmount(l.unitPrice)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Text(
                                "$ ${fmtQuoteAmount(QuoteCalc.format(l.quantity.toQty().multiply(l.unitPrice.toMoney())))}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = CotixGoNavy,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { editIndex = i; lineDialog = l }) { Text("Editar") }
                            TextButton(onClick = { lines.removeAt(i) }) { Text("Quitar") }
                        }
                    }
                }
            }
            if (retentions.any { !it.archived }) {
                item { QuoteFormSectionTitle("Retenciones") }
                item {
                    Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
                        ) {
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
                }
            }
            item { QuoteFormSectionTitle("Totales") }
            item {
                Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        QuoteTotalRow("Bruto", quoteAmountLabel(QuoteCalc.format(calc.gross), currency))
                        calc.retentions.forEach {
                            QuoteTotalRow("- ${it.name} (${it.ratePercent}%)", "- ${quoteAmountLabel(QuoteCalc.format(it.amount), currency)}")
                        }
                        QuoteTotalRow("Neto", quoteAmountLabel(QuoteCalc.format(calc.net), currency), emphasis = true)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = targetNet, onValueChange = { targetNet = it },
                            label = { Text("Neto que quiero recibir") },
                            supportingText = { Text("Calcula el bruto necesario para alcanzar el neto indicado") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                        OutlinedButton(onClick = {
                            val serviceRates = calcSpecs.filter { ConceptType.SERVICIO in it.appliesTo }.map { it.ratePercent }
                            inverseResult = QuoteCalc.requiredGrossForNet(targetNet.toMoney(), serviceRates)
                                ?.let { "Bruto requerido: ${QuoteCalc.format(it)}" }
                                ?: "No se puede calcular (tasas >= 100%)"
                        }) { Text("Calcular bruto") }
                    }
                    inverseResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            item { QuoteFormSectionTitle("Condiciones") }
            item {
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it },
                    label = { Text("Condiciones / observaciones") }, modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Button(
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        onClick = {
                        when {
                            clientId.isBlank() -> error = "El cliente es obligatorio."
                            title.isBlank() -> error = "El título de la cotización es obligatorio."
                            lacksVersionSnapshot && lines.isEmpty() ->
                                error = "Esta cotización no tiene líneas guardadas para reconstruir: guardarla así pondría sus importes en cero. Agrega las líneas o toca Cancelar para volver sin guardar."
                            validityDays <= 0 -> error = "La vigencia en días es obligatoria."
                            currency.length != 3 -> error = "La moneda debe ser un código ISO 4217."
                            lines.any { it.description.isBlank() } -> error = "Toda línea necesita descripción."
                            else -> {
                                error = null
                                onSave(
                                    QuoteInput(
                                        clientId = clientId,
                                        profileId = profile?.id ?: "",
                                        title = title,
                                        issueDateEpochDay = issueDay,
                                        validityDays = validityDays,
                                        currency = currency,
                                        notes = notes,
                                        lines = lines.map {
                                            QuoteLineInput(
                                                conceptType = it.conceptType,
                                                description = it.description,
                                                quantity = it.quantity,
                                                unit = it.unit,
                                                unitPrice = it.unitPrice,
                                                serviceRef = it.serviceRef,
                                                materialOrigin = it.materialOrigin,
                                            )
                                        },
                                        retentions = retentions.filter { it.name in selectedRetentions }.map {
                                            RetentionInput(
                                                name = it.name,
                                                ratePercent = it.ratePercent,
                                                appliesTo = it.appliesTo.split(",").map(ConceptType::valueOf).toSet(),
                                            )
                                        },
                                    ),
                                    lines.filter { it.saveToCatalog }.map {
                                        ServiceInput(
                                            profileId = profile?.id ?: "",
                                            name = it.description,
                                            suggestedPrice = it.unitPrice,
                                        )
                                    },
                                )
                            }
                        }
                    }) { Text("Guardar cotización") }
                    TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("Cancelar") }
                }
            }
        }
    }
}

// ---------- Detalle de Cotización ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteDetailScreen(
    quote: QuoteEntity?,
    versions: List<QuoteVersionEntity>,
    latestSnapshot: QuoteSnapshot?,
    profileName: String? = null,
    emitFailed: Boolean,
    onEmit: () -> Unit,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onRevertReject: () -> Unit,
    onCancel: () -> Unit,
    onRevalidate: (Int) -> Unit,
    onEdit: () -> Unit,
    onArchive: (Boolean) -> Unit,
    onOpenVersion: (Int) -> Unit,
    onCreateJob: () -> Unit,
    onBack: () -> Unit,
) {
    if (quote == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val effective = quote.effectiveStatus()
    var revalidateDialog by remember { mutableStateOf(false) }
    var revalidateDays by remember { mutableStateOf(latestSnapshot?.validityDays?.toString() ?: "") }

    if (revalidateDialog) {
        AlertDialog(
            onDismissRequest = { revalidateDialog = false },
            title = { Text("Revalidar cotización") },
            text = {
                Column {
                    Text("Se crea una cotización nueva a partir de esta. La vencida permanece intacta.")
                    OutlinedTextField(
                        value = revalidateDays, onValueChange = { revalidateDays = it.filter(Char::isDigit) },
                        label = { Text("Vigencia (días) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    (revalidateDays.toIntOrNull() ?: 0).let { if (it > 0) { revalidateDialog = false; onRevalidate(it) } }
                }) { Text("Revalidar") }
            },
            dismissButton = { TextButton(onClick = { revalidateDialog = false }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Row(
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Volver")
                    }
                    Text(
                        "Cotización",
                        style = MaterialTheme.typography.titleLarge,
                        color = CotixGoNavy,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                val (chipBg, chipFg) = when (effective) {
                    QuoteStatus.EMITIDA -> Color(0xFFDBEAFE) to Color(0xFF1D4ED8)
                    QuoteStatus.APROBADA -> Color(0xFFD1FAE5) to Color(0xFF065F46)
                    QuoteStatus.RECHAZADA -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
                    QuoteStatus.VENCIDA -> Color(0xFFFEE2E2) to Color(0xFFB91C1C)
                    else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            quote.title.ifBlank { "Sin título" },
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = CotixGoNavy,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "N° ${quote.officialNumber ?: quote.provisionalNumber} · versión ${quote.currentVersion}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                    Surface(shape = RoundedCornerShape(50), color = chipBg) {
                        Text(
                            label(effective),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = chipFg,
                        )
                    }
                }
            }
            val s = latestSnapshot
            if (s != null) {
                item {
                    Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QuoteDetailField("Cliente", s.client.name)
                            QuoteDetailField("Perfil", s.profile.businessName)
                            QuoteDetailField("Emisión", fmtIsoDay(s.issueDate))
                            QuoteDetailField("Vigencia", "${s.validityDays} días")
                            QuoteDetailField("Vence", fmtIsoDay(s.expiryDate))
                        }
                    }
                }
                item {
                    Text(
                        "Conceptos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CotixGoNavy,
                    )
                }
                if (s.lines.isEmpty()) {
                    item { QuoteDetailEmpty("Sin conceptos registrados.") }
                } else {
                    items(s.lines) { l ->
                        Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        l.description,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        "[${l.conceptType}] · ${l.quantity} ${l.unit} × $ ${fmtQuoteAmount(l.unitPrice)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                                Text(
                                    "$ ${fmtQuoteAmount(l.lineTotal)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CotixGoNavy,
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Retenciones",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CotixGoNavy,
                    )
                }
                if (s.retentions.isEmpty()) {
                    item { QuoteDetailEmpty("Sin retenciones aplicadas.") }
                } else {
                    items(s.retentions) { r ->
                        Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${r.name} (${r.ratePercent}%)",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "- $ ${fmtQuoteAmount(r.amount)}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = CotixGoNavy,
                                )
                            }
                        }
                    }
                }
                item { QuoteTotalsCard(s.gross, s.totalRetained, s.net, s.currency) }
            } else {
                // Cotización sin snapshot (p. ej. datos sembrados o migrados):
                // respaldo con los datos reales de QuoteEntity; el detalle de
                // líneas y retenciones no está persistido fuera de versiones.
                item {
                    Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            QuoteDetailField("Cliente", quote.clientNameSnapshot.ifBlank { "—" })
                            profileName?.let { QuoteDetailField("Perfil", it) }
                            QuoteDetailField("Emisión", dashboardDayLabel(quote.issueDateEpochDay))
                            QuoteDetailField("Vigencia", "${quote.validityDays} días")
                            QuoteDetailField("Vence", dashboardDayLabel(quote.expiryDateEpochDay))
                            QuoteDetailField("Moneda", quote.currency)
                            if (quote.notes.isNotBlank()) QuoteDetailField("Notas", quote.notes)
                        }
                    }
                }
                item {
                    Text(
                        "Conceptos",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CotixGoNavy,
                    )
                }
                item {
                    QuoteDetailEmpty(
                        "Los conceptos y retenciones de esta cotización no están disponibles: no tiene líneas persistidas en el historial.",
                    )
                }
                item { QuoteTotalsCard(quote.gross, quote.totalRetained, quote.net, quote.currency) }
            }
            if (emitFailed) {
                item { Text("La cotización necesita al menos una línea para emitirse.", color = MaterialTheme.colorScheme.error) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (effective) {
                        QuoteStatus.BORRADOR -> {
                            Button(onClick = onEmit, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Emitir") }
                        }
                        QuoteStatus.EMITIDA -> {
                            Button(onClick = onApprove, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Aprobar") }
                        }
                        QuoteStatus.RECHAZADA -> {
                            Button(onClick = onRevertReject, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Revertir a emitida") }
                        }
                        QuoteStatus.VENCIDA -> {
                            Button(onClick = { revalidateDialog = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Revalidar") }
                        }
                        else -> {}
                    }
                    // Creación manual del Trabajo desde APROBADA (F4):
                    // nunca automática — la acción la decide el usuario.
                    if (effective == QuoteStatus.APROBADA) {
                        Button(onClick = onCreateJob, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Crear trabajo") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (effective == QuoteStatus.BORRADOR || effective == QuoteStatus.EMITIDA) {
                            OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Editar") }
                        }
                        if (effective == QuoteStatus.EMITIDA) {
                            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f)) { Text("Rechazar") }
                        }
                        OutlinedButton(onClick = { onArchive(!quote.archived) }, modifier = Modifier.weight(1f)) {
                            Text(if (quote.archived) "Desarchivar" else "Archivar")
                        }
                    }
                    if (effective == QuoteStatus.BORRADOR || effective == QuoteStatus.EMITIDA) {
                        OutlinedButton(
                            onClick = onCancel,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        ) { Text("Cancelar cotización") }
                    }
                }
            }
            if (versions.isNotEmpty()) {
                item {
                    Text(
                        "Versiones",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = CotixGoNavy,
                    )
                }
                items(versions) { v ->
                    Surface(
                        onClick = { onOpenVersion(v.version) },
                        shape = QuoteCardShape,
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Versión ${v.version}",
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (v.version == quote.emittedVersion) {
                                Text(
                                    "emitida",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Fila etiqueta/valor para la tarjeta de datos del detalle de Cotización. */
@Composable
private fun QuoteDetailField(label: String, value: String) {
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

/** Encabezado de sección del formulario de cotización. */
@Composable
private fun QuoteFormSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = CotixGoNavy,
    )
}

/** Tarjeta de totales del detalle (misma estructura con snapshot o entidad). */
@Composable
private fun QuoteTotalsCard(gross: String, totalRetained: String, net: String, currency: String) {
    Surface(shape = QuoteCardShape, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            QuoteTotalRow("Bruto", "$ ${fmtQuoteAmount(gross)} $currency")
            QuoteTotalRow("Retenido", "- $ ${fmtQuoteAmount(totalRetained)} $currency")
            QuoteTotalRow("Neto", "$ ${fmtQuoteAmount(net)} $currency", emphasis = true)
        }
    }
}

@Composable
private fun QuoteTotalRow(label: String, value: String, emphasis: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) CotixGoNavy else MaterialTheme.colorScheme.secondary,
        )
        Text(
            value,
            style = if (emphasis) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasis) FontWeight.Bold else FontWeight.Normal,
            color = if (emphasis) CotixGoNavy else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Estado vacío suave para secciones del detalle sin contenido persistido. */
@Composable
private fun QuoteDetailEmpty(message: String) {
    Surface(
        shape = QuoteCardShape,
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

// ---------- Vista de una versión (histórico inmutable) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuoteVersionScreen(
    snapshot: QuoteSnapshot?,
    onFetchLogo: suspend (String) -> Boolean = { false },
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text(snapshot?.let { "${it.provisionalNumber} v${it.version}" } ?: "Versión") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            snapshot?.let { s ->
                item { Text("Cliente: ${s.client.name}") }
                item { Text("Perfil: ${s.profile.businessName}", style = MaterialTheme.typography.bodySmall) }
                item { Text("Emisión ${s.issueDate} · vence ${s.expiryDate} · ${s.currency}") }
                items(s.lines) { l ->
                    Text("[${l.conceptType}] ${l.description} — ${l.quantity} ${l.unit} × ${l.unitPrice} = ${l.lineTotal}")
                }
                item { Text("Bruto: ${s.gross} · Retenido: ${s.totalRetained} · Neto: ${s.net}") }
            }
            item {
                val context = androidx.compose.ui.platform.LocalContext.current
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                snapshot?.let { s ->
                    val logoRef = s.profile.logoRef
                    // El PDF solo usa el archivo de la referencia congelada;
                    // si aún no llegó a este dispositivo, no se sustituye
                    // por el logo actual — se ofrece reintento de descarga.
                    var logoMissing by remember(logoRef) {
                        mutableStateOf(logoRef != null &&
                            !com.cotixgo.app.data.LogoStore.exists(context, logoRef))
                    }
                    if (logoMissing) {
                        Text(
                            "Vista previa no disponible: el logo referenciado por esta" +
                                " versión aún no se descargó a este dispositivo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = {
                            scope.launch {
                                if (logoRef != null && onFetchLogo(logoRef)) logoMissing = false
                            }
                        }) { Text("Reintentar descarga") }
                    } else {
                        Button(onClick = { printQuotePdf(context, s) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Vista previa (PDF)")
                        }
                    }
                }
            }
            item { TextButton(onClick = onBack) { Text("Volver") } }
        }
    }
}

// ---------- Catálogo de servicios ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServicesScreen(
    services: List<ServiceEntity>,
    currency: String,
    onSave: (ServiceInput, String?) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var dialog by remember { mutableStateOf<ServiceEntity?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    val visible = services.filter { it.archived == showArchived }

    if (showDialog) {
        var name by remember { mutableStateOf(dialog?.name ?: "") }
        var desc by remember { mutableStateOf(dialog?.description ?: "") }
        var price by remember { mutableStateOf(dialog?.suggestedPrice ?: "") }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialog = null },
            title = { Text(if (dialog == null) "Nuevo servicio" else "Editar servicio") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre *") })
                    OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") })
                    OutlinedTextField(
                        value = price, onValueChange = { price = it }, label = { Text("Precio sugerido") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        supportingText = { Text("En $currency (moneda principal del perfil)") },
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank()) {
                        onSave(ServiceInput(profileId = "", name = name, description = desc, suggestedPrice = price), dialog?.id)
                        showDialog = false; dialog = null
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false; dialog = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Catálogo de servicios") }) },
        floatingActionButton = { Button(onClick = { showDialog = true }) { Text("+ Servicio") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Activos", "Archivados").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = showArchived == (i == 1), onClick = { showArchived = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(t) }
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Card {
                        Text(
                            if (!showArchived) {
                                "Sin servicios en el catálogo. Usa + Servicio para crear el primero."
                            } else {
                                "Sin servicios archivados."
                            },
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { s ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.name)
                            if (s.suggestedPrice.isNotBlank()) Text("Precio sugerido: ${s.suggestedPrice}", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = s; showDialog = true }) { Text("Editar") }
                                TextButton(onClick = { onArchive(s.id, !s.archived) }) {
                                    Text(if (s.archived) "Desarchivar" else "Archivar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Configuración de retenciones (global Usuario/Titular) ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RetentionsScreen(
    retentions: List<RetentionConfigEntity>,
    onSave: (RetentionInput, String?) -> Unit,
    onArchive: (String, Boolean) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<RetentionConfigEntity?>(null) }
    var showArchived by remember { mutableStateOf(false) }
    val visible = retentions.filter { it.archived == showArchived }

    if (showDialog) {
        var name by remember { mutableStateOf(dialog?.name ?: "") }
        var rate by remember { mutableStateOf(dialog?.ratePercent ?: "") }
        val applies = remember {
            mutableStateListOf<ConceptType>().apply {
                dialog?.appliesTo?.split(",")?.map(ConceptType::valueOf)?.let { addAll(it) }
                    ?: add(ConceptType.SERVICIO)
            }
        }
        AlertDialog(
            onDismissRequest = { showDialog = false; dialog = null },
            title = { Text(if (dialog == null) "Nueva retención" else "Editar retención") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre *") })
                    OutlinedTextField(
                        value = rate, onValueChange = { rate = it }, label = { Text("Tasa % *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    Text("Aplica a", style = MaterialTheme.typography.titleSmall)
                    ConceptType.entries.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = t in applies,
                                onCheckedChange = { if (it) applies.add(t) else applies.remove(t) },
                            )
                            Text(t.name)
                        }
                    }
                    Text(
                        "En cada cotización debes seleccionar esta retención. " +
                            "Se calcula sobre la suma de las líneas de los tipos " +
                            "marcados y reduce el neto. Cada versión guardada " +
                            "conserva los valores calculados.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (name.isNotBlank() && rate.toBigDecimalOrNull() != null && applies.isNotEmpty()) {
                        onSave(RetentionInput(name = name, ratePercent = rate, appliesTo = applies.toSet()), dialog?.id)
                        showDialog = false; dialog = null
                    }
                }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { showDialog = false; dialog = null }) { Text("Cancelar") } },
        )
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Retenciones") }) },
        floatingActionButton = { Button(onClick = { showDialog = true }) { Text("+ Retención") } },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                listOf("Activas", "Archivadas").forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = showArchived == (i == 1), onClick = { showArchived = i == 1 },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 2),
                    ) { Text(t) }
                }
            }
            if (visible.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Card {
                        Text(
                            if (!showArchived) {
                                "Sin retenciones. Usa + Retención para crear la primera."
                            } else {
                                "Sin retenciones archivadas."
                            },
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id }) { r ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${r.name} — ${r.ratePercent}% sobre ${r.appliesTo}")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { dialog = r; showDialog = true }) { Text("Editar") }
                                TextButton(onClick = { onArchive(r.id, !r.archived) }) {
                                    Text(if (r.archived) "Desarchivar" else "Archivar")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------- Perfil Profesional ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    profile: ProfileEntity?,
    onSave: (String, String, String, Int?) -> Unit,
    onSetLogo: (String?) -> Unit = {},
    onBack: () -> Unit,
) {
    var name by remember { mutableStateOf(profile?.businessName ?: "") }
    var desc by remember { mutableStateOf(profile?.description ?: "") }
    var info by remember { mutableStateOf(profile?.commercialInfo ?: "") }
    var vigencia by remember { mutableStateOf(profile?.vigenciaDias?.toString() ?: "") }
    // Un valor almacenado inválido se muestra como error al abrir; vacío=sin
    // default (válido), con valor debe ser >= 1.
    var vigenciaError by remember {
        mutableStateOf(vigencia.isNotEmpty() && (vigencia.toIntOrNull() ?: 0) < 1)
    }
    // Logo del perfil — referencia inmutable en almacenamiento privado.
    val logoCtx = androidx.compose.ui.platform.LocalContext.current
    var logoRef by remember { mutableStateOf(profile?.logoRef) }
    var logoBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(logoRef) {
        logoBitmap = logoRef?.let { com.cotixgo.app.data.LogoStore.decode(logoCtx, it) }
    }
    val pickLogo = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        uri?.let { com.cotixgo.app.data.LogoStore.import(logoCtx, it) }
            ?.also { ref -> logoRef = ref; onSetLogo(ref) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Perfil profesional") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nombre comercial *") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = info, onValueChange = { info = it }, label = { Text("Información comercial") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = vigencia,
                onValueChange = {
                    vigencia = it.filter(Char::isDigit)
                    vigenciaError = vigencia.isNotEmpty() && (vigencia.toIntOrNull() ?: 0) < 1
                },
                label = { Text("Vigencia predeterminada de cotizaciones (días)") },
                modifier = Modifier.fillMaxWidth(),
                isError = vigenciaError,
                supportingText = {
                    if (vigenciaError) {
                        Text("La vigencia predeterminada debe ser de al menos 1 día.")
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Logo — selector del sistema (sin permiso de cámara); se guarda
            // como archivo inmutable y los documentos congelan su referencia.
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                logoBitmap?.let {
                    Image(bitmap = it.asImageBitmap(), contentDescription = "Logo del perfil",
                        modifier = Modifier.height(56.dp))
                }
                OutlinedButton(onClick = { pickLogo.launch("image/*") }) {
                    Text(if (logoRef == null) "Elegir logo" else "Cambiar logo")
                }
                if (logoRef != null) {
                    TextButton(onClick = { logoRef = null; onSetLogo(null) }) { Text("Quitar") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = {
                    val invalid = vigencia.isNotEmpty() && (vigencia.toIntOrNull() ?: 0) < 1
                    if (invalid) {
                        vigenciaError = true
                    } else if (name.isNotBlank()) {
                        onSave(name, desc, info, vigencia.toIntOrNull())
                    }
                }) { Text("Guardar") }
                TextButton(onClick = onBack) { Text("Volver") }
            }

        }
    }
}

// ---------- Ajustes ----------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    user: UserEntity?,
    appLockEnabled: Boolean,
    onToggleAppLock: (Boolean) -> Unit,
    onCurrency: (String) -> Unit,
    onServices: () -> Unit,
    onRetentions: () -> Unit,
    onProfile: () -> Unit,
    onAccount: () -> Unit,
    onBack: () -> Unit,
) {
    var currency by remember { mutableStateOf(user?.mainCurrency ?: "") }
    Scaffold(topBar = { TopAppBar(title = { Text("Ajustes") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            var currencyError by remember { mutableStateOf(false) }
            fun currencyValid(c: String): Boolean = isValidCurrencyCode(c)
            OutlinedTextField(
                value = currency, onValueChange = { currency = it.uppercase().take(3); currencyError = false },
                label = { Text("Moneda principal") }, modifier = Modifier.fillMaxWidth(),
                isError = currencyError,
                supportingText = {
                    Text(
                        if (currencyError) "Código ISO 4217 no válido — usa uno real (COP, USD, EUR…)."
                        else "Código ISO 4217 de 3 letras — por ejemplo COP o USD."
                    )
                },
            )
            Button(onClick = {
                if (currencyValid(currency)) onCurrency(currency) else currencyError = true
            }) { Text("Guardar moneda") }
            @Composable
            fun navRow(label: String, onClick: () -> Unit) {
                OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                }
            }
            navRow("Catálogo de servicios", onServices)
            navRow("Retenciones", onRetentions)
            navRow("Perfil profesional", onProfile)
            navRow("Cuenta y sincronización", onAccount)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Bloqueo de interfaz")
                    Text(
                        "Pide biometría o PIN al abrir la app. Es una barrera de " +
                            "interfaz: no es cifrado adicional ni autenticación del " +
                            "almacén, y no afecta a la sincronización en segundo plano.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Checkbox(
                    checked = appLockEnabled,
                    onCheckedChange = { onToggleAppLock(it) },
                )
            }
            TextButton(onClick = onBack) { Text("Volver") }
        }
    }
}

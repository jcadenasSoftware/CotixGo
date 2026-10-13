package com.cotixgo.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Snapshot inmutable del contenido de una Cotización en una versión.
 * Al emitir, la versión vigente funciona como el histórico del documento:
 * Cliente, Perfil (incl. referencia de logo), líneas, cantidades, unidades,
 * precios, moneda, retenciones aplicadas y totales — nunca se recalcula con
 * la configuración vigente posterior.
 */

@Serializable
data class ClientSnapshot(
    val id: String,
    val name: String,
    val type: String,
    val phone: String,
    val email: String,
    val address: String,
    val notes: String,
)

@Serializable
data class ProfileSnapshot(
    val id: String,
    val businessName: String,
    val description: String,
    val commercialInfo: String,
    val logoRef: String?,
)

@Serializable
data class QuoteLineSnapshot(
    val conceptType: String,
    val description: String,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val lineTotal: String,
    val serviceRef: String? = null,
    val materialOrigin: String? = null,
)

@Serializable
data class AppliedRetentionSnapshot(
    val name: String,
    val ratePercent: String,
    val appliesTo: List<String>,
    val base: String,
    val amount: String,
)

@Serializable
data class QuoteSnapshot(
    val version: Int,
    val provisionalNumber: String,
    val client: ClientSnapshot,
    val profile: ProfileSnapshot,
    val issueDate: String,
    val validityDays: Int,
    val expiryDate: String,
    val currency: String,
    val notes: String,
    val lines: List<QuoteLineSnapshot>,
    val retentions: List<AppliedRetentionSnapshot>,
    val gross: String,
    val totalRetained: String,
    val net: String,
    val savedAtEpochMs: Long,
)

// ---------- Informe de Trabajo (F5) ----------

/**
 * Snapshot inmutable del documento emitido del Informe: cabecera (Cliente,
 * Perfil, título, destino, números), contenido tal como quedó al emitir y
 * los Trabajos incluidos con su ejecución — solo actividades COMPLETADA se
 * presentan como realizadas; materiales y observaciones son descriptivos,
 * nunca movimientos de inventario.
 */
@Serializable
data class ReportJobActivitySnapshot(
    val description: String,
    val finishedAt: Long? = null,
    val notes: String,
)

@Serializable
data class ReportJobMaterialSnapshot(
    val description: String,
    val origin: String,
    val quantity: String,
    val unit: String,
    val notes: String,
)

@Serializable
data class ReportJobSnapshot(
    val id: String,
    val number: String,
    val status: String,
    val notes: String,
    val completedActivities: List<ReportJobActivitySnapshot>,
    val materials: List<ReportJobMaterialSnapshot>,
)

@Serializable
data class ReportSnapshot(
    val version: Int,
    val provisionalNumber: String,
    val officialNumber: String? = null,
    val client: ClientSnapshot,
    val profile: ProfileSnapshot,
    val title: String,
    val destination: String,
    val content: String,
    val jobs: List<ReportJobSnapshot>,
    val savedAtEpochMs: Long,
)

object SnapshotJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(snapshot: QuoteSnapshot): String = json.encodeToString(QuoteSnapshot.serializer(), snapshot)

    fun decodeQuote(raw: String): QuoteSnapshot = json.decodeFromString(QuoteSnapshot.serializer(), raw)

    fun encodeReport(snapshot: ReportSnapshot): String =
        json.encodeToString(ReportSnapshot.serializer(), snapshot)

    fun decodeReport(raw: String): ReportSnapshot =
        json.decodeFromString(ReportSnapshot.serializer(), raw)

    fun encodeClient(snapshot: ClientSnapshot): String =
        json.encodeToString(ClientSnapshot.serializer(), snapshot)

    fun encodeProfile(snapshot: ProfileSnapshot): String =
        json.encodeToString(ProfileSnapshot.serializer(), snapshot)

    fun encodeRetentions(list: List<AppliedRetentionSnapshot>): String =
        json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(AppliedRetentionSnapshot.serializer()),
            list,
        )

    fun decodeRetentions(raw: String): List<AppliedRetentionSnapshot> =
        if (raw.isBlank()) emptyList() else json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(AppliedRetentionSnapshot.serializer()),
            raw,
        )
}

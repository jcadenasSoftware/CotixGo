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

object SnapshotJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(snapshot: QuoteSnapshot): String = json.encodeToString(QuoteSnapshot.serializer(), snapshot)

    fun decodeQuote(raw: String): QuoteSnapshot = json.decodeFromString(QuoteSnapshot.serializer(), raw)
}

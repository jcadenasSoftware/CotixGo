package com.cotixgo.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import com.cotixgo.app.data.security.SessionCrypto
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.net.ApiClient
import com.cotixgo.app.net.ApiException
import com.cotixgo.app.net.boolOr
import com.cotixgo.app.net.intOr
import com.cotixgo.app.net.isTransient
import com.cotixgo.app.net.longOr
import com.cotixgo.app.net.str
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Tipos de entidad sincronizables del contrato F3. */
object EntityTypes {
    const val USER = "user"
    const val PROFILE = "profile"
    const val CLIENT = "client"
    const val SERVICE = "service"
    const val RETENTION = "retention_config"
    const val QUOTE = "quote"
    const val QUOTE_VERSION = "quote_version"
    const val JOB = "job"
    const val JOB_QUOTE = "job_quote"
    const val JOB_ACTIVITY = "job_activity"
    const val JOB_MATERIAL = "job_material"
    const val REPORT = "report"
    const val REPORT_JOB = "report_job"
    const val REPORT_VERSION = "report_version"
    const val COLLECTION_ACCOUNT = "collection_account"
    const val COLLECTION_METHOD = "collection_method"
    const val PAYMENT = "payment"
    const val PAYMENT_ASSIGNMENT = "payment_assignment"
    const val CONSOLIDATED_COLLECTION = "consolidated_collection"
    const val PAYMENT_ADJUSTMENT = "payment_adjustment"
    const val PAYMENT_REFUND = "payment_refund"
    // F8
    const val MATERIAL = "material"
    const val PURCHASE = "purchase"
    const val INVENTORY_MOVEMENT = "inventory_movement"
    // Proyección derivada del servidor (solo lectura; el cliente nunca la encola).
    const val INVENTORY_STOCK = "inventory_stock"
}

/**
 * Resolución de operaciones rechazadas (PAUSED) — decisiones aprobadas:
 * INTERNAL_ERROR se reintenta automáticamente con backoff acotado (máx.
 * 5) conservando el operation_id (el backend no memoriza ese fallo);
 * DEPENDENCY/CONFLICT_BLOCKED esperan al prerequisito y se reemiten con
 * operation_id nuevo cuando este se resuelve (los rechazos ordinarios sí
 * quedan memorizados en `processed_operations`); una pausada de tipo
 * snapshot queda SUPERSEDED solo cuando una op posterior de la misma
 * entidad aplicó con payload completo; el resto se resuelve por acción
 * explícita del usuario conservando payload y resultado en el historial.
 */
object Rejections {
    const val INTERNAL_ERROR = "INTERNAL_ERROR"
    const val DEPENDENCY = "DEPENDENCY"
    const val CONFLICT_BLOCKED = "CONFLICT_BLOCKED"
    const val VALIDATION_ERROR = "VALIDATION_ERROR"
    const val INVALID_TRANSITION = "INVALID_TRANSITION"
    const val FORBIDDEN_ENTITY = "FORBIDDEN_ENTITY"
    const val IMMUTABLE_FINANCIAL = "IMMUTABLE_FINANCIAL"
    const val IDEMPOTENCY_KEY_REUSED = "IDEMPOTENCY_KEY_REUSED"
    const val STALE_BASE_VERSION = "STALE_BASE_VERSION"

    // Códigos de resolución con los que una pausada queda DONE (historial).
    const val RES_SUPERSEDED = "SUPERSEDED"
    const val RES_REISSUED = "REISSUED"
    const val RES_RESTORED = "RESTORED_FROM_REMOTE"
    const val RES_DISCARDED = "DISCARDED_LOCAL"
    const val RES_CASCADE_DISCARDED = "DISCARDED_CASCADE"
    const val RES_ACKNOWLEDGED = "REJECTED_ACKNOWLEDGED"
    const val RES_PREREQ_DISCARDED = "PREREQUISITE_DISCARDED"

    /** Reintentos automáticos por fallo interno antes de atención manual. */
    const val MAX_INTERNAL_ATTEMPTS = 5
    val INTERNAL_BACKOFF_MS = longArrayOf(15_000L, 60_000L, 240_000L, 900_000L, 3_600_000L)

    /**
     * Tipos cuyo payload es el estado COMPLETO de la fila (verificado en
     * SyncPayloads campo a campo): una op posterior aplicada subsume una
     * pausada anterior de la misma entidad. Quedan excluidos los eventos
     * append-only/financieros (payment*, inventory_movement), las versiones
     * (quote_version/report_version) y los vínculos (job_quote/report_job:
     * cada fila es el vínculo, no un estado mutable del documento).
     */
    val SNAPSHOT_TYPES = setOf(
        EntityTypes.USER, EntityTypes.PROFILE, EntityTypes.CLIENT,
        EntityTypes.SERVICE, EntityTypes.RETENTION, EntityTypes.QUOTE,
        EntityTypes.JOB, EntityTypes.JOB_ACTIVITY, EntityTypes.JOB_MATERIAL,
        EntityTypes.REPORT, EntityTypes.COLLECTION_ACCOUNT,
        EntityTypes.COLLECTION_METHOD, EntityTypes.CONSOLIDATED_COLLECTION,
        EntityTypes.MATERIAL, EntityTypes.PURCHASE,
    )

    /** Códigos que pueden liberarse solos cuando el prerequisito quedó resuelto. */
    val RELEASABLE_CODES = setOf(DEPENDENCY, CONFLICT_BLOCKED)

    /**
     * Referencias declaradas por el contrato de payload (espejo del mapa
     * DEPENDENCIES del servidor más las validadas por sus handlers):
     * entityType → (depType, campo escalar del payload). Los campos nulos
     * o vacíos no bloquean: el servidor decide entonces.
     */
    val SCALAR_DEPS: Map<String, List<Pair<String, String>>> = mapOf(
        EntityTypes.SERVICE to listOf(EntityTypes.PROFILE to "profileId"),
        EntityTypes.QUOTE to listOf(EntityTypes.CLIENT to "clientId", EntityTypes.PROFILE to "profileId"),
        EntityTypes.QUOTE_VERSION to listOf(EntityTypes.QUOTE to "quoteId"),
        EntityTypes.JOB to listOf(EntityTypes.CLIENT to "clientId", EntityTypes.PROFILE to "profileId"),
        EntityTypes.JOB_QUOTE to listOf(EntityTypes.JOB to "jobId", EntityTypes.QUOTE to "quoteId"),
        EntityTypes.JOB_ACTIVITY to listOf(EntityTypes.JOB to "jobId"),
        EntityTypes.JOB_MATERIAL to listOf(EntityTypes.JOB to "jobId", EntityTypes.MATERIAL to "materialId"),
        EntityTypes.REPORT to listOf(EntityTypes.CLIENT to "clientId", EntityTypes.PROFILE to "profileId"),
        EntityTypes.REPORT_JOB to listOf(EntityTypes.REPORT to "reportId", EntityTypes.JOB to "jobId"),
        EntityTypes.REPORT_VERSION to listOf(EntityTypes.REPORT to "reportId"),
        EntityTypes.COLLECTION_ACCOUNT to listOf(
            EntityTypes.CLIENT to "clientId", EntityTypes.PROFILE to "profileId",
        ),
        EntityTypes.CONSOLIDATED_COLLECTION to listOf(EntityTypes.CLIENT to "clientId"),
        EntityTypes.PAYMENT to listOf(
            EntityTypes.CLIENT to "clientId",
            EntityTypes.COLLECTION_METHOD to "collectionMethodId",
        ),
        EntityTypes.PAYMENT_ASSIGNMENT to listOf(
            EntityTypes.PAYMENT to "paymentId",
            EntityTypes.COLLECTION_ACCOUNT to "accountId",
            EntityTypes.COLLECTION_ACCOUNT to "fromAccountId",
            EntityTypes.CONSOLIDATED_COLLECTION to "consolidatedId",
            EntityTypes.CONSOLIDATED_COLLECTION to "fromConsolidatedId",
        ),
        EntityTypes.PAYMENT_ADJUSTMENT to listOf(EntityTypes.PAYMENT to "paymentId"),
        EntityTypes.PAYMENT_REFUND to listOf(
            EntityTypes.PAYMENT to "paymentId",
            EntityTypes.COLLECTION_METHOD to "collectionMethodId",
        ),
        EntityTypes.INVENTORY_MOVEMENT to listOf(
            EntityTypes.MATERIAL to "materialId",
            EntityTypes.JOB to "jobId",
            EntityTypes.PURCHASE to "purchaseId",
            EntityTypes.JOB_MATERIAL to "jobMaterialId",
        ),
    )

    /** Referencias en arreglos del payload: entityType → (depType, campo arreglo de ids). */
    val ARRAY_DEPS: Map<String, List<Pair<String, String>>> = mapOf(
        EntityTypes.COLLECTION_ACCOUNT to listOf(
            EntityTypes.JOB to "jobIds", EntityTypes.QUOTE to "quoteIds",
        ),
        EntityTypes.CONSOLIDATED_COLLECTION to listOf(
            EntityTypes.COLLECTION_ACCOUNT to "accountIds",
        ),
    )

    /**
     * Cascada de descarte: entityType → (tabla hija, columna padre). Las
     * filas hijas se eliminan con la raíz; la misma tabla puede aparecer
     * bajo varios padres por columnas distintas.
     */
    val CHILD_TABLES: Map<String, List<Pair<String, String>>> = mapOf(
        EntityTypes.QUOTE to listOf("quote_versions" to "quoteId"),
        EntityTypes.JOB to listOf(
            "job_activities" to "jobId", "job_materials" to "jobId",
            "job_quotes" to "jobId", "inventory_movements" to "jobId",
        ),
        EntityTypes.JOB_MATERIAL to listOf("inventory_movements" to "jobMaterialId"),
        EntityTypes.REPORT to listOf("report_jobs" to "reportId", "report_versions" to "reportId"),
        EntityTypes.COLLECTION_ACCOUNT to listOf(
            "collection_account_items" to "accountId",
            "collection_account_jobs" to "accountId",
            "collection_account_quotes" to "accountId",
            "payment_assignments" to "accountId",
            "payment_assignments" to "fromAccountId",
        ),
        EntityTypes.CONSOLIDATED_COLLECTION to listOf(
            "consolidated_collection_accounts" to "consolidatedId",
            "payment_assignments" to "consolidatedId",
            "payment_assignments" to "fromConsolidatedId",
        ),
        EntityTypes.PAYMENT to listOf(
            "payment_assignments" to "paymentId",
            "payment_adjustments" to "paymentId",
            "payment_refunds" to "paymentId",
        ),
        EntityTypes.PURCHASE to listOf(
            "purchase_items" to "purchaseId", "inventory_movements" to "purchaseId",
        ),
    )

    /**
     * Referencias que BLOQUEAN el descarte (filas ajenas a la cascada que
     * apuntan a la entidad): entityType → (tabla, columna). Se reportan en
     * la vista previa y el descarte se niega mientras existan.
     */
    val REF_BLOCKS: Map<String, List<Pair<String, String>>> = mapOf(
        EntityTypes.CLIENT to listOf(
            "quotes" to "clientId", "jobs" to "clientId", "reports" to "clientId",
            "collection_accounts" to "clientId",
            "consolidated_collections" to "clientId", "payments" to "clientId",
        ),
        EntityTypes.PROFILE to listOf(
            "services" to "profileId", "quotes" to "profileId", "jobs" to "profileId",
            "reports" to "profileId", "collection_accounts" to "profileId",
        ),
        EntityTypes.QUOTE to listOf(
            "job_quotes" to "quoteId", "collection_account_quotes" to "quoteId",
        ),
        EntityTypes.JOB to listOf(
            "report_jobs" to "jobId", "collection_account_jobs" to "jobId",
        ),
        EntityTypes.MATERIAL to listOf(
            "job_materials" to "materialId", "purchase_items" to "materialId",
            "inventory_movements" to "materialId",
        ),
        EntityTypes.COLLECTION_METHOD to listOf(
            "payments" to "collectionMethodId", "payment_refunds" to "collectionMethodId",
        ),
        EntityTypes.COLLECTION_ACCOUNT to listOf(
            "consolidated_collection_accounts" to "accountId",
        ),
    )

    /** Tabla física por tipo de entidad (descarte / limpieza de pendingSync). */
    val TABLE_BY_TYPE: Map<String, String> = mapOf(
        EntityTypes.USER to "users",
        EntityTypes.PROFILE to "professional_profiles",
        EntityTypes.CLIENT to "clients",
        EntityTypes.SERVICE to "services",
        EntityTypes.RETENTION to "retention_configs",
        EntityTypes.QUOTE to "quotes",
        EntityTypes.QUOTE_VERSION to "quote_versions",
        EntityTypes.JOB to "jobs",
        EntityTypes.JOB_QUOTE to "job_quotes",
        EntityTypes.JOB_ACTIVITY to "job_activities",
        EntityTypes.JOB_MATERIAL to "job_materials",
        EntityTypes.REPORT to "reports",
        EntityTypes.REPORT_JOB to "report_jobs",
        EntityTypes.REPORT_VERSION to "report_versions",
        EntityTypes.COLLECTION_ACCOUNT to "collection_accounts",
        EntityTypes.COLLECTION_METHOD to "collection_methods",
        EntityTypes.PAYMENT to "payments",
        EntityTypes.PAYMENT_ASSIGNMENT to "payment_assignments",
        EntityTypes.CONSOLIDATED_COLLECTION to "consolidated_collections",
        EntityTypes.PAYMENT_ADJUSTMENT to "payment_adjustments",
        EntityTypes.PAYMENT_REFUND to "payment_refunds",
        EntityTypes.MATERIAL to "materials",
        EntityTypes.PURCHASE to "purchases",
        EntityTypes.INVENTORY_MOVEMENT to "inventory_movements",
    )

    /** Tipo de entidad por tabla hija (para marcar sus operaciones). */
    val TYPE_BY_TABLE: Map<String, String> = TABLE_BY_TYPE.entries.associate { (t, tab) -> tab to t }
}

/**
 * Sesión local de sincronización: token como blob AES/GCM de
 * AndroidKeyStore (SessionCrypto) en preferencias planas, identidad de
 * cuenta y device_id en preferencias, cursor en `sync_meta` (se
 * reinicia al cambiar de cuenta o limpiar datos). La librería
 * `androidx.security:security-crypto` quedó sustituida por APIs
 * directas de Keystore (deprecada en 1.1.0 estable). Los tokens
 * guardados con EncryptedSharedPreferences en versiones anteriores no
 * se migran: tras la actualización el usuario inicia sesión de nuevo.
 */
class Session(
    context: Context,
    private val db: AppDatabase,
    prefsName: String = PREFS_NAME,
) {
    private val appContext = context.applicationContext

    /** Contexto de aplicación (WorkManager para reintentos con backoff). */
    val context: Context get() = appContext
    private val plain: SharedPreferences =
        appContext.getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    init {
        // Limpieza del archivo de preferencias cifradas legado
        // (EncryptedSharedPreferences de security-crypto, ya sin uso).
        // Solo aplica a la sesión de producción: una sesión con nombre de
        // prueba no debe tocar ningún archivo real de la app.
        if (prefsName == PREFS_NAME) appContext.deleteSharedPreferences("cotixgo.secure")
    }

    var token: String?
        get() = SessionCrypto.decryptOrNull(plain.getString("token_enc", null))
        set(v) = plain.edit().putString("token_enc", SessionCrypto.encrypt(v)).apply()

    var accountEmail: String?
        get() = plain.getString("account_email", null)
        set(v) = plain.edit().putString("account_email", v).apply()

    var accountUserId: String?
        get() = plain.getString("account_user_id", null)
        set(v) = plain.edit().putString("account_user_id", v).apply()

    val deviceId: String
        get() = plain.getString("device_id", null) ?: Ids.new().also {
            plain.edit().putString("device_id", it).apply()
        }

    var apiBaseUrl: String
        get() = plain.getString("api_base_url", null) ?: DEFAULT_API_BASE
        set(v) = plain.edit().putString("api_base_url", v).apply()

    suspend fun cursor(): Long = db.syncDao().meta("sync_cursor")?.toLongOrNull() ?: 0L
    suspend fun setCursor(v: Long) = db.syncDao().putMeta("sync_cursor", v.toString())

    val isBound: Boolean get() = accountUserId != null && token != null

    fun clearSession() {
        token = null; accountEmail = null; accountUserId = null
    }

    companion object {
        // Emulador Android → máquina anfitriona. Dispositivo físico/dev:
        // configurar la IP del equipo en Ajustes (solo desarrollo local).
        const val DEFAULT_API_BASE = "http://10.0.2.2:8080"

        /** SharedPreferences de la sesión de producción. Las pruebas
         *  instrumentadas deben usar otro nombre (ver TestIsolation). */
        const val PREFS_NAME = "cotixgo.session"
    }
}

/** Payloads de entidad para la cola (payload inmutable por operación). */
object SyncPayloads {
    fun user(e: UserEntity) = buildJsonObject {
        put("id", e.id); put("name", e.name); put("mainCurrency", e.mainCurrency)
        put("email", e.email); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun profile(e: ProfileEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("businessName", e.businessName)
        put("description", e.description); put("commercialInfo", e.commercialInfo)
        put("logoRef", e.logoRef); put("vigenciaDias", e.vigenciaDias)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun client(e: ClientEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("type", e.type); put("name", e.name)
        put("phone", e.phone); put("email", e.email); put("address", e.address)
        put("notes", e.notes); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun service(e: ServiceEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("profileId", e.profileId)
        put("name", e.name); put("description", e.description)
        put("suggestedPrice", e.suggestedPrice); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun retention(e: RetentionConfigEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("name", e.name)
        put("ratePercent", e.ratePercent); put("appliesTo", e.appliesTo)
        put("archived", e.archived); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun quote(e: QuoteEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("profileId", e.profileId); put("provisionalNumber", e.provisionalNumber)
        put("status", e.status); put("issueDateEpochDay", e.issueDateEpochDay)
        put("validityDays", e.validityDays); put("expiryDateEpochDay", e.expiryDateEpochDay)
        put("currency", e.currency); put("notes", e.notes)
        put("currentVersion", e.currentVersion); put("emittedVersion", e.emittedVersion)
        put("gross", e.gross); put("totalRetained", e.totalRetained); put("net", e.net)
        put("clientNameSnapshot", e.clientNameSnapshot); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun quoteVersion(e: QuoteVersionEntity) = buildJsonObject {
        put("id", e.id); put("quoteId", e.quoteId); put("version", e.version)
        put("snapshotJson", e.snapshotJson); put("createdAt", e.createdAt)
    }
    fun job(e: JobEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("profileId", e.profileId); put("provisionalNumber", e.provisionalNumber)
        put("status", e.status); put("startDateEpochDay", e.startDateEpochDay)
        put("estimatedDateEpochDay", e.estimatedDateEpochDay)
        put("deliveryDateEpochDay", e.deliveryDateEpochDay)
        put("executionAddress", e.executionAddress); put("notes", e.notes)
        put("clientNameSnapshot", e.clientNameSnapshot); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun jobQuote(e: JobQuoteEntity) = buildJsonObject {
        put("id", e.id); put("jobId", e.jobId); put("quoteId", e.quoteId)
        put("relationType", e.relationType); put("createdAt", e.createdAt)
    }
    fun jobActivity(e: JobActivityEntity) = buildJsonObject {
        put("id", e.id); put("jobId", e.jobId); put("description", e.description)
        put("status", e.status); put("position", e.position)
        put("startedAt", e.startedAt); put("finishedAt", e.finishedAt)
        put("notes", e.notes); put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun jobMaterial(e: JobMaterialEntity) = buildJsonObject {
        put("id", e.id); put("jobId", e.jobId); put("description", e.description)
        put("origin", e.origin); put("quantity", e.quantity); put("unit", e.unit)
        put("notes", e.notes)
        // F8: vínculo opcional al catálogo (solo registros nuevos).
        put("materialId", e.materialId); put("condition", e.condition)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun report(e: ReportEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("profileId", e.profileId); put("provisionalNumber", e.provisionalNumber)
        put("status", e.status); put("title", e.title); put("destination", e.destination)
        put("content", e.content); put("currentVersion", e.currentVersion)
        put("emittedVersion", e.emittedVersion)
        put("clientNameSnapshot", e.clientNameSnapshot); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun reportJob(e: ReportJobEntity) = buildJsonObject {
        put("id", e.id); put("reportId", e.reportId); put("jobId", e.jobId)
        put("position", e.position); put("createdAt", e.createdAt)
    }
    fun reportVersion(e: ReportVersionEntity) = buildJsonObject {
        put("id", e.id); put("reportId", e.reportId); put("version", e.version)
        put("snapshotJson", e.snapshotJson); put("previousVersionId", e.previousVersionId)
        put("createdAt", e.createdAt)
    }
    /**
     * Documento F6 completo: las líneas y los vínculos (jobIds/quoteIds)
     * viajan dentro del payload — la Cuenta y su contenido se aplican
     * atómicamente y el servidor valida cada referencia con datos
     * persistidos. `appliedAmount`/`balance` son derivados mantenidos por
     * los eventos de pago; el servidor los recalcula siempre.
     */
    fun collectionAccount(
        e: CollectionAccountEntity,
        items: List<CollectionAccountItemEntity>,
        jobIds: List<String>,
        quoteIds: List<String>,
    ) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("profileId", e.profileId); put("provisionalNumber", e.provisionalNumber)
        put("status", e.status); put("currency", e.currency)
        put("gross", e.gross); put("totalRetained", e.totalRetained)
        put("net", e.net); put("appliedAmount", e.appliedAmount); put("balance", e.balance)
        put("issuedAt", e.issuedAt); put("dueDateEpochDay", e.dueDateEpochDay)
        put("conditions", e.conditions)
        put("clientSnapshotJson", e.clientSnapshotJson)
        put("profileSnapshotJson", e.profileSnapshotJson)
        put("retentionsSnapshotJson", e.retentionsSnapshotJson)
        put("clientNameSnapshot", e.clientNameSnapshot); put("archived", e.archived)
        put("items", kotlinx.serialization.json.JsonArray(items.map { item ->
            buildJsonObject {
                put("id", item.id); put("position", item.position)
                put("conceptType", item.conceptType); put("description", item.description)
                put("quantity", item.quantity); put("unit", item.unit)
                put("unitPrice", item.unitPrice); put("lineTotal", item.lineTotal)
            }
        }))
        put("jobIds", kotlinx.serialization.json.JsonArray(jobIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("quoteIds", kotlinx.serialization.json.JsonArray(quoteIds.map { kotlinx.serialization.json.JsonPrimitive(it) }))
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun collectionMethod(e: CollectionMethodEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("name", e.name)
        put("detail", e.detail); put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun payment(e: PaymentEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("collectionMethodId", e.collectionMethodId)
        put("amount", e.amount); put("paidAtEpochDay", e.paidAtEpochDay)
        put("reference", e.reference); put("notes", e.notes)
        put("appliedAmount", e.appliedAmount); put("unassignedAmount", e.unassignedAmount)
        // F7: el servidor recalcula estos derivados siempre; viajan para
        // mantener el payload completo.
        put("effectiveAmount", e.effectiveAmount); put("refundedAmount", e.refundedAmount)
        put("annulled", e.annulled)
        put("assignedTargetType", e.assignedTargetType?.lowercase())
        put("assignedAccountId", e.assignedAccountId)
        put("assignedConsolidatedId", e.assignedConsolidatedId)
        put("assignmentStatus", e.assignmentStatus)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    fun paymentAssignment(e: PaymentAssignmentEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("paymentId", e.paymentId)
        put("action", e.action.lowercase())
        put("targetType", e.targetType.lowercase())
        put("accountId", e.accountId); put("consolidatedId", e.consolidatedId)
        put("fromAccountId", e.fromAccountId); put("fromConsolidatedId", e.fromConsolidatedId)
        put("amount", e.amount)
        put("releasedAmount", e.releasedAmount); put("reason", e.reason)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /**
     * F7 — Consolidado: documento completo (membresía como lista de ids;
     * los totales los recalcula el servidor desde sus propias filas).
     */
    fun consolidatedCollection(
        e: ConsolidatedCollectionEntity,
        accountIds: List<String>,
    ) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("clientId", e.clientId)
        put("provisionalNumber", e.provisionalNumber); put("status", e.status)
        put("currency", e.currency)
        put("totalAmount", e.totalAmount); put("appliedAmount", e.appliedAmount)
        put("balance", e.balance); put("clientNameSnapshot", e.clientNameSnapshot)
        put("archived", e.archived)
        put("accountIds", kotlinx.serialization.json.JsonArray(accountIds.map {
            kotlinx.serialization.json.JsonPrimitive(it)
        }))
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /** F7 — ajuste append-only: kind CORRECTION|ANNULMENT + motivo. */
    fun paymentAdjustment(e: PaymentAdjustmentEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("paymentId", e.paymentId)
        put("kind", e.kind); put("reason", e.reason)
        put("newEffectiveAmount", e.newEffectiveAmount)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /** F7 — reembolso append-only: monto, fecha, método y motivo. */
    fun paymentRefund(e: PaymentRefundEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("paymentId", e.paymentId)
        put("amount", e.amount); put("refundedAtEpochDay", e.refundedAtEpochDay)
        put("collectionMethodId", e.collectionMethodId); put("reason", e.reason)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /** F8 — Material del catálogo global del titular (documental). */
    fun material(e: MaterialEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("name", e.name)
        put("unit", e.unit); put("description", e.description)
        put("archived", e.archived)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /**
     * F8 — Compra documental completa: las líneas viajan dentro del
     * payload (mismo patrón que la Cuenta de Cobro) y el servidor las
     * revalida con datos persistidos; el total y la moneda son derivados
     * allí — el total local es solo referencia.
     */
    fun purchase(
        e: PurchaseEntity,
        items: List<PurchaseItemEntity>,
    ) = buildJsonObject {
        put("id", e.id); put("userId", e.userId)
        put("provisionalNumber", e.provisionalNumber)
        put("status", e.status); put("purchaseDateEpochDay", e.purchaseDateEpochDay)
        put("supplierPlace", e.supplierPlace); put("reference", e.reference)
        put("notes", e.notes); put("currency", e.currency)
        put("totalAmount", e.totalAmount); put("archived", e.archived)
        put("items", kotlinx.serialization.json.JsonArray(items.map { item ->
            buildJsonObject {
                put("id", item.id); put("position", item.position)
                put("materialId", item.materialId)
                put("quantity", item.quantity); put("unit", item.unit)
                put("unitPrice", item.unitPrice); put("lineTotal", item.lineTotal)
                put("condition", item.condition)
                put("createdAt", item.createdAt)
            }
        }))
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
    /**
     * F8 — Movimiento append-only creado por el cliente (consumo en
     * Trabajo, sobrante o ajuste manual). Los movimientos de origen COMPRA
     * los deriva el servidor y llegan por pull.
     */
    fun inventoryMovement(e: InventoryMovementEntity) = buildJsonObject {
        put("id", e.id); put("userId", e.userId); put("materialId", e.materialId)
        put("condition", e.condition); put("movementType", e.movementType)
        put("origin", e.origin); put("quantity", e.quantity); put("unit", e.unit)
        put("unitCost", e.unitCost); put("purchaseId", e.purchaseId)
        put("jobId", e.jobId); put("jobMaterialId", e.jobMaterialId)
        put("note", e.note); put("occurredAt", e.occurredAt)
        put("createdAt", e.createdAt); put("updatedAt", e.updatedAt)
    }
}

/**
 * Motor de sincronización F3. Push en orden de cola con resultado por
 * operación; pull incremental por cursor; ambas copias preservadas en
 * conflicto; vinculación transaccional de la identidad canónica.
 */
class SyncRepository(
    private val db: AppDatabase,
    val session: Session,
    apiOverride: com.cotixgo.app.net.SyncApi? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val apiOverride = apiOverride
    private val api get() = apiOverride ?: ApiClient(session.apiBaseUrl) { session.token }

    fun observePendingCount() = db.syncDao().observePendingCount()
    fun observePausedCount() = db.syncDao().observePausedCount()
    fun observePaused() = db.syncDao().observePaused()
    fun observeConflicts() = db.syncDao().observeConflicts()

    // ---------- Encolado (llamar dentro de withTransaction del escritor) ----------

    suspend fun enqueue(
        entityType: String,
        entityId: String,
        payload: JsonObject,
        baseServerVersion: Int,
        opType: String = "upsert",
        supersedes: String? = null,
    ) {
        val now = nowEpochMillis()
        db.syncDao().insertOp(
            SyncOperationEntity(
                opId = Ids.new(),
                seq = db.syncDao().nextSeq(),
                entityType = entityType,
                entityId = entityId,
                opType = opType,
                baseServerVersion = baseServerVersion,
                payloadJson = payload.toString(),
                supersedesOpId = supersedes,
                deviceId = session.deviceId,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    // ---------- Push ----------

    /**
     * Ejecuta un ciclo: push de pendientes (resultado por operación) + pull
     * incremental. Devuelve el número de operaciones que quedaron pausadas.
     * Lanza excepción solo si el fallo transitorio impidió completar el ciclo.
     */
    suspend fun syncNow(): SyncSummary {
        require(session.isBound) { "No hay cuenta vinculada." }
        var pushed = 0; var paused = 0; var pulled = 0
        // Push por tandas en orden de cola. Solo la primera operación pendiente
        // de cada entidad viaja en el lote: las posteriores se escribieron
        // sobre el estado local y su base correcta es la versión que el
        // servidor acabe de aplicar (rebase tras cada resultado APPLIED).
        while (true) {
            val batch = db.syncDao().pendingOps()
                .groupBy { it.entityType to it.entityId }
                .map { it.value.first() }
                .filter { pushableNow(it) && !backoffWaiting(it) }
                .take(100)
            if (batch.isEmpty()) break
            val ops = batch.map { op ->
                buildJsonObject {
                    put("operation_id", op.opId)
                    put("entity_type", op.entityType)
                    put("entity_id", op.entityId)
                    put("type", op.opType)
                    put("base_server_version", op.baseServerVersion)
                    put("payload", json.parseToJsonElement(op.payloadJson))
                    op.supersedesOpId?.let { put("supersedes", it) }
                }
            }
            val res = try {
                api.push(session.deviceId, ops)
            } catch (e: ApiException) {
                if (e.isTransient()) throw e
                throw e // errores 4xx de protocolo: también detiene el ciclo
            }
            val results = res["results"]?.jsonArray ?: break
            results.forEachIndexed { i, el ->
                val r = el.jsonObject
                val op = batch[i]
                when (r.str("status")) {
                    "APPLIED" -> {
                        pushed++
                        applyApplied(op, r)
                    }
                    "CONFLICT" -> { paused++; applyConflict(op, r) }
                    else -> {
                        // INTERNAL_ERROR no se memoriza en el servidor: la
                        // misma operation_id se reintenta con backoff hasta
                        // MAX_INTERNAL_ATTEMPTS reintentos automáticos; solo
                        // entonces pasa a atención manual marcada como fallo
                        // interno.
                        if (r.str("code") == Rejections.INTERNAL_ERROR &&
                            op.attempts < Rejections.MAX_INTERNAL_ATTEMPTS
                        ) {
                            scheduleInternalRetry(op, r)
                        } else {
                            paused++
                            applyRejected(op, r)
                        }
                    }
                }
            }
            // Tras cada tanda: liberar lo que esperaba un prerequisito ya
            // resuelto y registrar la cascada de lo recién pausado.
            releaseReadyPaused()
        }
        pulled += pullAll()
        return SyncSummary(pushed = pushed, paused = paused, pulled = pulled)
    }

    /** Programa el reintento automático de un INTERNAL_ERROR (mismo opId). */
    private suspend fun scheduleInternalRetry(op: SyncOperationEntity, r: JsonObject) {
        val now = nowEpochMillis()
        val attempts = op.attempts + 1
        val delay = Rejections.INTERNAL_BACKOFF_MS[
            (attempts - 1).coerceAtMost(Rejections.INTERNAL_BACKOFF_MS.lastIndex),
        ]
        val rj = buildJsonObject {
            r.forEach { (k, v) -> put(k, v) }
            put("nextRetryAt", now + delay)
        }
        db.syncDao().rescheduleOp(op.opId, rj.toString(), now)
        db.syncDao().bumpOpAttempts(op.opId, now)
        // El reintento es automático: se agenda un worker one-shot con el
        // delay del backoff (el periódico de 30 min sería demasiado tardío).
        // Un fallo de WorkManager no rompe el ciclo: el reintento queda
        // persistido y el próximo sync lo recoge.
        runCatching { com.cotixgo.app.sync.SyncScheduler.requestDelayed(session.context, delay) }
    }

    /**
     * Reemite una pausada con operation_id NUEVO (los rechazos ordinarios
     * están memorizados: reenviar el mismo id devolvería el rechazo
     * guardado sin reevaluar). La nueva op lleva el estado local vigente
     * de la fila si existe — nunca un snapshot obsoleto— y queda vinculada
     * por `supersedes` a la original, que se cierra como REISSUED con su
     * payload y resultado conservados.
     */
    private suspend fun reissuePausedAsNew(op: SyncOperationEntity, now: Long) {
        val payload = currentPayload(op.entityType, op.entityId)
            ?: runCatching { json.parseToJsonElement(op.payloadJson).jsonObject }.getOrNull()
            ?: return
        val base = op.resultJson?.let {
            runCatching {
                json.parseToJsonElement(it).jsonObject["server_state"]
                    ?.jsonObject?.intOr("server_version")
            }.getOrNull()
        }?.takeIf { it > 0 } ?: op.baseServerVersion
        db.syncDao().updateOpResult(op.opId, "DONE", resolutionResult(op, "REISSUED", now), Rejections.RES_REISSUED, now)
        enqueue(op.entityType, op.entityId, payload, base, opType = op.opType, supersedes = op.opId)
    }

    /** Payload del estado local vigente de la fila (para la reemisión). */
    private suspend fun currentPayload(type: String, id: String): JsonObject? = when (type) {
        EntityTypes.USER -> db.userDao().get()?.let { SyncPayloads.user(it) }
        EntityTypes.PROFILE -> db.profileDao().get(id)?.let { SyncPayloads.profile(it) }
        EntityTypes.CLIENT -> db.clientDao().get(id)?.let { SyncPayloads.client(it) }
        EntityTypes.SERVICE -> db.serviceDao().get(id)?.let { SyncPayloads.service(it) }
        EntityTypes.RETENTION -> db.retentionDao().get(id)?.let { SyncPayloads.retention(it) }
        EntityTypes.QUOTE -> db.quoteDao().get(id)?.let { SyncPayloads.quote(it) }
        EntityTypes.QUOTE_VERSION -> db.quoteVersionDao().get(id)?.let { SyncPayloads.quoteVersion(it) }
        EntityTypes.JOB -> db.jobDao().get(id)?.let { SyncPayloads.job(it) }
        EntityTypes.JOB_QUOTE -> db.jobQuoteDao().get(id)?.let { SyncPayloads.jobQuote(it) }
        EntityTypes.JOB_ACTIVITY -> db.jobActivityDao().get(id)?.let { SyncPayloads.jobActivity(it) }
        EntityTypes.JOB_MATERIAL -> db.jobMaterialDao().get(id)?.let { SyncPayloads.jobMaterial(it) }
        EntityTypes.REPORT -> db.reportDao().get(id)?.let { SyncPayloads.report(it) }
        EntityTypes.REPORT_JOB -> db.reportJobDao().get(id)?.let { SyncPayloads.reportJob(it) }
        EntityTypes.REPORT_VERSION -> db.reportVersionDao().get(id)?.let { SyncPayloads.reportVersion(it) }
        EntityTypes.COLLECTION_ACCOUNT -> db.collectionAccountDao().get(id)?.let { acc ->
            SyncPayloads.collectionAccount(
                acc,
                db.collectionAccountItemDao().forAccount(id),
                db.collectionAccountJobDao().forAccount(id).map { it.jobId },
                db.collectionAccountQuoteDao().forAccount(id).map { it.quoteId },
            )
        }
        EntityTypes.COLLECTION_METHOD -> db.collectionMethodDao().get(id)?.let { SyncPayloads.collectionMethod(it) }
        EntityTypes.PAYMENT -> db.paymentDao().get(id)?.let { SyncPayloads.payment(it) }
        EntityTypes.PAYMENT_ASSIGNMENT -> db.paymentAssignmentDao().get(id)?.let { SyncPayloads.paymentAssignment(it) }
        EntityTypes.CONSOLIDATED_COLLECTION -> db.consolidatedCollectionDao().get(id)?.let { c ->
            SyncPayloads.consolidatedCollection(
                c,
                db.consolidatedCollectionAccountDao().forConsolidated(c.id).map { it.accountId },
            )
        }
        EntityTypes.PAYMENT_ADJUSTMENT -> db.paymentAdjustmentDao().get(id)?.let { SyncPayloads.paymentAdjustment(it) }
        EntityTypes.PAYMENT_REFUND -> db.paymentRefundDao().get(id)?.let { SyncPayloads.paymentRefund(it) }
        EntityTypes.MATERIAL -> db.materialDao().get(id)?.let { SyncPayloads.material(it) }
        EntityTypes.PURCHASE -> db.purchaseDao().get(id)?.let { p ->
            SyncPayloads.purchase(p, db.purchaseItemDao().forPurchase(id))
        }
        EntityTypes.INVENTORY_MOVEMENT -> db.inventoryMovementDao().get(id)?.let { SyncPayloads.inventoryMovement(it) }
        else -> null
    }

    /**
     * Historial: el resultado de una op pausada que se resuelve se conserva
     * anidado bajo `original` junto al código de resolución y su causa.
     */
    private fun resolutionResult(
        op: SyncOperationEntity,
        code: String,
        now: Long,
        detail: String? = null,
    ): String = buildJsonObject {
        put("resolution", code)
        put("resolvedAt", now)
        detail?.let { put("detail", it) }
        op.resultJson?.let {
            runCatching { json.parseToJsonElement(it) }.getOrNull()?.let { put("original", it) }
        }
    }.toString()

    /**
     * Liberación de dependientes: una pausada DEPENDENCY/CONFLICT_BLOCKED
     * se reemite (opId nuevo) cuando todas sus referencias ya no tienen
     * operaciones sin resolver y sus filas locales están confirmadas, la
     * entidad ya no tiene un conflicto abierto ni otras ops por delante,
     * Y hay evidencia de progreso posterior a la pausa (sin ella se
     * reemitiría en bucle contra el mismo rechazo memorizado).
     */
    private suspend fun releaseReadyPaused() {
        val now = nowEpochMillis()
        for (op in db.syncDao().pausedOpsNow()) {
            if (op.resultCode !in Rejections.RELEASABLE_CODES) continue
            if (waitReasonResolved(op, requireProgress = true)) {
                db.withTransaction { reissuePausedAsNew(op, now) }
            }
        }
    }

    /**
     * ¿Se resolvió el motivo de espera de una pausada DEPENDENCY/
     * CONFLICT_BLOCKED? Con `requireProgress` se exige además que algo
     * cambié DESPUÉS de la pausa (la entidad o una referencia aplicó o se
     * actualizó por pull/restauración); si no, el mismo rechazo se
     * repetiría en bucle — la op queda para atención manual.
     */
    private suspend fun waitReasonResolved(
        op: SyncOperationEntity,
        requireProgress: Boolean,
    ): Boolean {
        if (db.syncDao().pendingOpsForEntity(op.entityType, op.entityId)
                .any { it.opId != op.opId }
        ) {
            return false
        }
        if (op.resultCode == Rejections.CONFLICT_BLOCKED) {
            val openConflict = db.syncDao().observeConflicts().first()
                .any { it.entityType == op.entityType && it.entityId == op.entityId }
            if (openConflict) return false
        }
        var progressed = db.syncDao().allOpsForEntity(op.entityType, op.entityId)
            .any { it.state == "DONE" && it.updatedAt > op.updatedAt }
        for ((depType, depId) in dependencyRefs(op)) {
            // Solo importan las ops del referido anteriores a la pausada:
            // una posterior no puede haber causado su rechazo.
            if (db.syncDao().pendingOpsForEntity(depType, depId).any { it.seq < op.seq }) {
                return false
            }
            val local = getLocal(depType, depId)
            if (local != null && local.second == 0) return false
            // Fila referida que ya no existe localmente ni en el servidor:
            // su descarte se registró; el dependiente no puede liberarse.
            if (local == null && wasDiscardedLocally(depType, depId)) {
                return false
            }
            if (!progressed) progressed = depProgressedAfter(depType, depId, op.updatedAt)
        }
        // Sin referencias (o sin progreso demostrable) una liberación
        // automática solo reintentaría en bucle el mismo rechazo.
        return !requireProgress || progressed
    }

    /** La referencia produjo evidencia nueva tras `since` (op aplicada o fila actualizada). */
    private suspend fun depProgressedAfter(type: String, id: String, since: Long): Boolean {
        if (db.syncDao().allOpsForEntity(type, id)
                .any { it.state == "DONE" && it.updatedAt > since }
        ) {
            return true
        }
        val table = Rejections.TABLE_BY_TYPE[type] ?: return false
        return runCatching {
            db.openHelper.writableDatabase
                .query("SELECT `updatedAt` FROM `$table` WHERE `id` = ?", arrayOf(id))
                .use { c -> c.moveToFirst() && c.getLong(0) > since }
        }.getOrDefault(false)
    }

    /** La entidad fue descartada explícitamente (última op DISCARDED_*). */
    private suspend fun wasDiscardedLocally(type: String, id: String): Boolean =
        db.syncDao().allOpsForEntity(type, id).lastOrNull()?.resultCode
            ?.let { it == Rejections.RES_DISCARDED || it == Rejections.RES_CASCADE_DISCARDED } == true

    /**
     * Supersede: una pausada de tipo snapshot queda DONE/SUPERSEDED cuando
     * una op posterior de la misma entidad aplicó y su payload contiene el
     * estado completo (todas las claves del payload rechazado). Nunca para
     * eventos append-only ni tipos sin semántica snapshot demostrada.
     */
    private suspend fun supersedePausedOps(applied: SyncOperationEntity) {
        if (applied.opType != "upsert" || applied.entityType !in Rejections.SNAPSHOT_TYPES) return
        val laterKeys = runCatching {
            json.parseToJsonElement(applied.payloadJson).jsonObject.keys
        }.getOrNull() ?: return
        val now = nowEpochMillis()
        for (p in db.syncDao().pendingOpsForEntity(applied.entityType, applied.entityId)) {
            if (p.opId == applied.opId || p.state != "PAUSED" || p.seq >= applied.seq) continue
            if (p.opType != "upsert") continue
            val earlierKeys = runCatching {
                json.parseToJsonElement(p.payloadJson).jsonObject.keys
            }.getOrNull() ?: continue
            if (!laterKeys.containsAll(earlierKeys)) continue
            db.syncDao().updateOpResult(
                p.opId, "DONE",
                resolutionResult(p, Rejections.RES_SUPERSEDED, now, "subsume:${applied.opId}"),
                Rejections.RES_SUPERSEDED, now,
            )
        }
    }

    /**
     * Referencias declaradas de una operación: pares (entityType, entityId)
     * que el payload apunta. Espejo del mapa DEPENDENCIES del servidor más
     * las referencias opcionales validadas por sus handlers; los ids vacíos
     * no se devuelven (el servidor decide sobre referencias obligatorias).
     */
    private fun dependencyRefs(op: SyncOperationEntity): List<Pair<String, String>> {
        val p = runCatching {
            json.parseToJsonElement(op.payloadJson).jsonObject
        }.getOrNull() ?: return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        for ((depType, field) in Rejections.SCALAR_DEPS[op.entityType].orEmpty()) {
            p[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { out += depType to it }
        }
        for ((depType, field) in Rejections.ARRAY_DEPS[op.entityType].orEmpty()) {
            p[field]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                ?.forEach { out += depType to it }
        }
        // Las líneas de la Compra referencian Materiales del catálogo.
        if (op.entityType == EntityTypes.PURCHASE) {
            p["items"]?.jsonArray?.mapNotNull {
                it.jsonObject["materialId"]?.jsonPrimitive?.content
            }?.forEach { out += EntityTypes.MATERIAL to it }
        }
        return out
    }

    /**
     * `null` si la operación puede enviarse ahora; de lo contrario una
     * razón de bloqueo. Reglas (decisiones aprobadas para PAUSED):
     * 1. Una operación anterior de la MISMA entidad sin resolver —incluida
     *    una pausada— mantiene al hijo en cola (antes solo contaban las
     *    activas y el hijo se enviaba a un rechazo predecible en cascada).
     * 2. Cada referencia declarada debe existir en el servidor con sus
     *    operaciones ANTERIORES al hijo ya resueltas: una fila local con
     *    serverVersion=0 todavía no existe allá y una op previa pendiente
     *    del referido puede ser justo la que lo crea o la que pone el
     *    estado que el hijo necesita (p.ej. `job_quote` exige la
     *    Cotización ya APROBADA remota). Ops del referido encoladas
     *    DESPUÉS del hijo no bloquean — exigirlas crea espera circular
     *    (`emit` de Informe necesita el vínculo `report_job` aplicado,
     *    y el vínculo esperaría al propio `emit`).
     *    Si la fila no existe localmente se deja pasar: puede existir
     *    solo en el servidor.
     */
    private suspend fun opBlockedReason(op: SyncOperationEntity): String? {
        val siblings = db.syncDao().pendingOpsForEntity(op.entityType, op.entityId)
        if (siblings.any { it.seq < op.seq }) return "entity_queue:${op.entityType}"
        for ((depType, depId) in dependencyRefs(op)) {
            if (db.syncDao().pendingOpsForEntity(depType, depId).any { it.seq < op.seq }) {
                return "dep_unresolved:$depType:$depId"
            }
            val local = getLocal(depType, depId)
            if (local != null && local.second == 0) return "dep_unsynced:$depType:$depId"
        }
        return null
    }

    private suspend fun pushableNow(op: SyncOperationEntity): Boolean =
        opBlockedReason(op) == null

    /** Reintento con backoff tras INTERNAL_ERROR: espera hasta nextRetryAt. */
    private fun backoffWaiting(op: SyncOperationEntity): Boolean {
        if (op.state != "RETRY") return false
        val nextAt = op.resultJson
            ?.let { runCatching { json.parseToJsonElement(it).jsonObject.longOr("nextRetryAt") }.getOrNull() }
            ?: return false
        return nextAt > nowEpochMillis()
    }

    private suspend fun applyApplied(op: SyncOperationEntity, r: JsonObject) {
        val now = nowEpochMillis()
        val sv = r.intOr("server_version")
        val official = r.str("official_number")
        val versionConflict = r.str("code") == "VERSION_CONFLICT"
        db.withTransaction {
            db.syncDao().updateOpResult(op.opId, "DONE", r.toString(), r.str("code"), now)
            // Las operaciones encoladas posteriores de la misma entidad se
            // escribieron sobre el estado local: su base correcta es la
            // versión que el servidor acaba de aplicar.
            if (sv > 0) db.syncDao().rebasePendingOps(op.entityType, op.entityId, sv, now)
            when (op.entityType) {
                EntityTypes.USER -> db.userDao().markSynced(op.entityId, sv, now)
                EntityTypes.PROFILE -> db.profileDao().markSynced(op.entityId, sv, now)
                EntityTypes.CLIENT -> db.clientDao().markSynced(op.entityId, sv, now)
                EntityTypes.SERVICE -> db.serviceDao().markSynced(op.entityId, sv, now)
                EntityTypes.RETENTION -> db.retentionDao().markSynced(op.entityId, sv, now)
                EntityTypes.QUOTE -> {
                    db.quoteDao().markSynced(op.entityId, sv, now)
                    if (official != null || versionConflict) {
                        db.quoteDao().updateSyncFields(
                            op.entityId,
                            official ?: db.quoteDao().get(op.entityId)?.officialNumber,
                            versionConflict || db.quoteDao().get(op.entityId)?.versionConflict == true,
                            now,
                        )
                    }
                }
                EntityTypes.QUOTE_VERSION -> {
                    // Sin pendingSync propio: la cola es su trazabilidad.
                    val p = json.parseToJsonElement(op.payloadJson).jsonObject
                    p.str("quoteId")?.let { qid ->
                        if (versionConflict) {
                            db.quoteDao().updateSyncFields(qid, db.quoteDao().get(qid)?.officialNumber, true, now)
                        } else if (op.opType == "resolve") {
                            db.quoteDao().updateSyncFields(qid, db.quoteDao().get(qid)?.officialNumber, false, now)
                        }
                    }
                }
                EntityTypes.JOB -> {
                    db.jobDao().markSynced(op.entityId, sv, now)
                    if (official != null) db.jobDao().updateOfficial(op.entityId, official, now)
                }
                EntityTypes.JOB_QUOTE -> db.jobQuoteDao().markSynced(op.entityId, sv)
                EntityTypes.JOB_ACTIVITY -> db.jobActivityDao().markSynced(op.entityId, sv, now)
                EntityTypes.JOB_MATERIAL -> db.jobMaterialDao().markSynced(op.entityId, sv, now)
                EntityTypes.REPORT -> {
                    db.reportDao().markSynced(op.entityId, sv, now)
                    if (official != null || versionConflict) {
                        db.reportDao().updateSyncFields(
                            op.entityId,
                            official ?: db.reportDao().get(op.entityId)?.officialNumber,
                            versionConflict || db.reportDao().get(op.entityId)?.versionConflict == true,
                            now,
                        )
                    }
                }
                EntityTypes.REPORT_JOB -> db.reportJobDao().markSynced(op.entityId, sv)
                EntityTypes.REPORT_VERSION -> {
                    // Sin pendingSync propio: la cola es su trazabilidad.
                    val p = json.parseToJsonElement(op.payloadJson).jsonObject
                    p.str("reportId")?.let { rid ->
                        if (versionConflict) {
                            db.reportDao().updateSyncFields(rid, db.reportDao().get(rid)?.officialNumber, true, now)
                        } else if (op.opType == "resolve") {
                            db.reportDao().updateSyncFields(rid, db.reportDao().get(rid)?.officialNumber, false, now)
                        }
                    }
                }
                EntityTypes.COLLECTION_ACCOUNT -> {
                    db.collectionAccountDao().markSynced(op.entityId, sv, now)
                    if (official != null) {
                        db.collectionAccountDao().updateOfficial(op.entityId, official, now)
                    }
                }
                EntityTypes.COLLECTION_METHOD -> db.collectionMethodDao().markSynced(op.entityId, sv, now)
                EntityTypes.PAYMENT -> db.paymentDao().markSynced(op.entityId, sv, now)
                EntityTypes.PAYMENT_ASSIGNMENT -> db.paymentAssignmentDao().markSynced(op.entityId, sv, now)
                EntityTypes.CONSOLIDATED_COLLECTION -> {
                    db.consolidatedCollectionDao().markSynced(op.entityId, sv, now)
                    if (official != null) {
                        db.consolidatedCollectionDao().updateOfficial(op.entityId, official, now)
                    }
                }
                EntityTypes.PAYMENT_ADJUSTMENT -> db.paymentAdjustmentDao().markSynced(op.entityId, sv, now)
                EntityTypes.PAYMENT_REFUND -> db.paymentRefundDao().markSynced(op.entityId, sv, now)
                EntityTypes.MATERIAL -> db.materialDao().markSynced(op.entityId, sv, now)
                EntityTypes.PURCHASE -> {
                    db.purchaseDao().markSynced(op.entityId, sv, now)
                    if (official != null) {
                        db.purchaseDao().updateOfficial(op.entityId, official, now)
                    }
                }
                EntityTypes.INVENTORY_MOVEMENT ->
                    db.inventoryMovementDao().markSynced(op.entityId, sv, now)
            }
            // PAUSED-resolution: una op aplicada de tipo snapshot subsume a
            // las pausadas anteriores de la misma entidad (verificado por
            // cobertura de claves, nunca para eventos append-only).
            supersedePausedOps(op)
            // El estado autoritativo adjunto al resultado se adopta solo si
            // la entidad no tiene más operaciones por resolver (igual que en
            // el pull: con ops abiertas la copia local sigue siendo la de
            // trabajo y la versión remota ya actualizó su base).
            val serverState = r["server_state"]?.jsonObject
            if (sv > 0 && serverState != null &&
                db.syncDao().pendingOpsForEntity(op.entityType, op.entityId).isEmpty()
            ) {
                upsertFromPayload(op.entityType, op.entityId, serverState, sv)
            }
        }
    }

    private suspend fun applyConflict(op: SyncOperationEntity, r: JsonObject) {
        val now = nowEpochMillis()
        db.withTransaction {
            db.syncDao().updateOpResult(op.opId, "PAUSED", r.toString(), "CONFLICT", now)
            r["server_state"]?.jsonObject?.let { remote ->
                db.syncDao().insertConflict(
                    SyncConflictEntity(
                        entityType = op.entityType,
                        entityId = op.entityId,
                        remoteJson = remote.toString(),
                        remoteServerVersion = r.intOr("server_version"),
                        detectedAt = now,
                    ),
                )
            }
        }
    }

    private suspend fun applyRejected(op: SyncOperationEntity, r: JsonObject) {
        db.syncDao().updateOpResult(op.opId, "PAUSED", r.toString(), r.str("code"), nowEpochMillis())
    }

    // ---------- Pull ----------

    suspend fun pullAll(): Int {
        var cursor = session.cursor()
        var applied = 0
        while (true) {
            val res = api.pull(cursor)
            val changes = res["changes"]?.jsonArray ?: break
            if (changes.isEmpty()) break
            for (el in changes) {
                val c = el.jsonObject
                applyRemoteChange(c)
                applied++
            }
            val next = res.longOr("next_cursor") ?: cursor
            session.setCursor(next)
            if (next <= cursor) break
            cursor = next
        }
        return applied
    }

    private suspend fun applyRemoteChange(c: JsonObject) {
        val type = c.str("entity_type") ?: return
        val id = c.str("entity_id") ?: return
        val payload = c["payload"]?.jsonObject ?: return
        val sv = c.intOr("server_version")
        val now = nowEpochMillis()

        if (type == EntityTypes.QUOTE_VERSION) {
            val quoteId = payload.str("quoteId") ?: return
            // Dedupe por entity_id: una versión remota concurrente con el
            // mismo número cliente se conserva como copia separada (D3).
            if (db.quoteVersionDao().get(id) == null) {
                db.quoteVersionDao().insert(
                    QuoteVersionEntity(
                        id = id, quoteId = quoteId, version = payload.intOr("version"),
                        snapshotJson = payload.str("snapshotJson") ?: "",
                        serverOrder = c.intOr("server_order").takeIf { it > 0 },
                        createdAt = payload.longOr("createdAt") ?: now,
                    ),
                )
            }
            return
        }

        if (type == EntityTypes.REPORT_VERSION) {
            val reportId = payload.str("reportId") ?: return
            if (db.reportVersionDao().get(id) == null) {
                db.reportVersionDao().insert(
                    ReportVersionEntity(
                        id = id, reportId = reportId, version = payload.intOr("version"),
                        snapshotJson = payload.str("snapshotJson") ?: "",
                        previousVersionId = payload.str("previousVersionId"),
                        serverOrder = c.intOr("server_order").takeIf { it > 0 },
                        createdAt = payload.longOr("createdAt") ?: now,
                    ),
                )
            }
            return
        }

        val openOps = db.syncDao().openOpsForEntity(type, id)
        val local = getLocal(type, id)
        if (local == null) {
            upsertFromPayload(type, id, payload, sv)
            applyDocMeta(type, id, c)
            return
        }
        val (hasPendingWrites, localSv) = local
        if (openOps > 0 || hasPendingWrites) {
            // Cambio remoto sobre entidad con trabajo local pendiente:
            // preservar ambas copias; la resolución la decide el usuario.
            db.syncDao().insertConflict(
                SyncConflictEntity(
                    entityType = type, entityId = id,
                    remoteJson = remoteWithMeta(payload, c).toString(),
                    remoteServerVersion = sv,
                    detectedAt = now,
                ),
            )
            return
        }
        if (sv <= localSv) return // cambio ya cubierto por el estado local
        upsertFromPayload(type, id, payload, sv)
        applyDocMeta(type, id, c)
    }

    /** Payload remoto + metadatos del servidor (official_number y
     *  has_version_conflict viajan fuera del payload) para que la copia
     *  remota de un conflicto conserve el documento completo. */
    private fun remoteWithMeta(payload: JsonObject, c: JsonObject): JsonObject =
        buildJsonObject {
            payload.forEach { (k, v) -> put(k, v) }
            put("official_number", c["official_number"] ?: JsonNull)
            put("has_version_conflict", c.boolOr("has_version_conflict"))
        }

    private suspend fun applyDocMeta(type: String, id: String, c: JsonObject) {
        if (type == EntityTypes.QUOTE) {
            db.quoteDao().updateSyncFields(
                id, c.str("official_number"),
                c.boolOr("has_version_conflict"), nowEpochMillis(),
            )
        }
        if (type == EntityTypes.JOB) {
            // official_number viaja como columna meta (no dentro del payload).
            db.jobDao().updateOfficial(id, c.str("official_number"), nowEpochMillis())
        }
        if (type == EntityTypes.REPORT) {
            db.reportDao().updateSyncFields(
                id, c.str("official_number"),
                c.boolOr("has_version_conflict"), nowEpochMillis(),
            )
        }
        if (type == EntityTypes.COLLECTION_ACCOUNT) {
            db.collectionAccountDao().updateOfficial(id, c.str("official_number"), nowEpochMillis())
        }
        if (type == EntityTypes.CONSOLIDATED_COLLECTION) {
            db.consolidatedCollectionDao().updateOfficial(id, c.str("official_number"), nowEpochMillis())
        }
        if (type == EntityTypes.PURCHASE) {
            db.purchaseDao().updateOfficial(id, c.str("official_number"), nowEpochMillis())
        }
    }

    /** Devuelve (tiene pendingSync local, serverVersion local) o null si no existe. */
    private suspend fun getLocal(type: String, id: String): Pair<Boolean, Int>? = when (type) {
        EntityTypes.USER -> db.userDao().get()?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PROFILE -> db.profileDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.CLIENT -> db.clientDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.SERVICE -> db.serviceDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.RETENTION -> db.retentionDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.QUOTE -> db.quoteDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.JOB -> db.jobDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.JOB_QUOTE -> db.jobQuoteDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.JOB_ACTIVITY -> db.jobActivityDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.JOB_MATERIAL -> db.jobMaterialDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.REPORT -> db.reportDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.REPORT_JOB -> db.reportJobDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.COLLECTION_ACCOUNT -> db.collectionAccountDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.COLLECTION_METHOD -> db.collectionMethodDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PAYMENT -> db.paymentDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PAYMENT_ASSIGNMENT -> db.paymentAssignmentDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.CONSOLIDATED_COLLECTION -> db.consolidatedCollectionDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PAYMENT_ADJUSTMENT -> db.paymentAdjustmentDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PAYMENT_REFUND -> db.paymentRefundDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.MATERIAL -> db.materialDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.PURCHASE -> db.purchaseDao().get(id)?.let { it.pendingSync to it.serverVersion }
        EntityTypes.INVENTORY_MOVEMENT -> db.inventoryMovementDao().get(id)?.let { it.pendingSync to it.serverVersion }
        // Proyección derivada: nunca tiene escrituras locales pendientes.
        EntityTypes.INVENTORY_STOCK -> db.materialStockDao().get(id)?.let { false to it.serverVersion }
        else -> null
    }

    private suspend fun upsertFromPayload(type: String, id: String, p: JsonObject, sv: Int) {
        val now = nowEpochMillis()
        when (type) {
            EntityTypes.USER -> {
                // El usuario local siempre lleva el id canónico tras la
                // vinculación; actualizar la fila existente en vez de
                // insertar por PK remota (evita usuarios duplicados).
                val cur = db.userDao().get()
                if (cur != null) {
                    db.userDao().insert(
                        cur.copy(
                            name = p.str("name") ?: cur.name,
                            mainCurrency = p.str("mainCurrency") ?: cur.mainCurrency,
                            email = p.str("email") ?: cur.email,
                            updatedAt = now,
                            pendingSync = false,
                            serverVersion = sv,
                        ),
                    )
                } else {
                    db.userDao().insert(
                        UserEntity(
                            id = p.str("id") ?: id,
                            name = p.str("name") ?: "",
                            mainCurrency = p.str("mainCurrency"),
                            email = p.str("email"),
                            createdAt = p.longOr("createdAt") ?: now,
                            updatedAt = now,
                            pendingSync = false,
                            serverVersion = sv,
                        ),
                    )
                }
            }
            EntityTypes.PROFILE -> db.profileDao().insert(
                ProfileEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    businessName = p.str("businessName") ?: "",
                    description = p.str("description") ?: "",
                    commercialInfo = p.str("commercialInfo") ?: "",
                    logoRef = p.str("logoRef"),
                    vigenciaDias = p.longOr("vigenciaDias")?.toInt(),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.CLIENT -> db.clientDao().upsert(
                ClientEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    type = p.str("type") ?: "NATURAL",
                    name = p.str("name") ?: "",
                    phone = p.str("phone") ?: "", email = p.str("email") ?: "",
                    address = p.str("address") ?: "", notes = p.str("notes") ?: "",
                    archived = p.boolOr("archived"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.SERVICE -> db.serviceDao().upsert(
                ServiceEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    profileId = p.str("profileId") ?: "",
                    name = p.str("name") ?: "",
                    description = p.str("description") ?: "",
                    suggestedPrice = p.str("suggestedPrice") ?: "",
                    archived = p.boolOr("archived"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.RETENTION -> db.retentionDao().upsert(
                RetentionConfigEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    name = p.str("name") ?: "",
                    ratePercent = p.str("ratePercent") ?: "0",
                    appliesTo = p.str("appliesTo") ?: "SERVICIO",
                    archived = p.boolOr("archived"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.QUOTE -> db.quoteDao().upsert(
                QuoteEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    clientId = p.str("clientId") ?: "", profileId = p.str("profileId") ?: "",
                    provisionalNumber = p.str("provisionalNumber") ?: "",
                    status = p.str("status") ?: "BORRADOR",
                    issueDateEpochDay = p.longOr("issueDateEpochDay") ?: 0,
                    validityDays = p.longOr("validityDays")?.toInt() ?: 15,
                    expiryDateEpochDay = p.longOr("expiryDateEpochDay") ?: 0,
                    currency = p.str("currency") ?: "COP",
                    notes = p.str("notes") ?: "",
                    currentVersion = p.longOr("currentVersion")?.toInt() ?: 1,
                    emittedVersion = p.longOr("emittedVersion")?.toInt() ?: 0,
                    gross = p.str("gross") ?: "0", totalRetained = p.str("totalRetained") ?: "0",
                    net = p.str("net") ?: "0",
                    clientNameSnapshot = p.str("clientNameSnapshot") ?: "",
                    archived = p.boolOr("archived"),
                    officialNumber = p.str("officialNumber"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.JOB -> db.jobDao().upsert(
                JobEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    clientId = p.str("clientId") ?: "", profileId = p.str("profileId") ?: "",
                    provisionalNumber = p.str("provisionalNumber") ?: "",
                    status = p.str("status") ?: "BORRADOR",
                    startDateEpochDay = p.longOr("startDateEpochDay"),
                    estimatedDateEpochDay = p.longOr("estimatedDateEpochDay"),
                    deliveryDateEpochDay = p.longOr("deliveryDateEpochDay"),
                    executionAddress = p.str("executionAddress") ?: "",
                    notes = p.str("notes") ?: "",
                    clientNameSnapshot = p.str("clientNameSnapshot") ?: "",
                    archived = p.boolOr("archived"),
                    officialNumber = p.str("officialNumber"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.JOB_QUOTE -> db.jobQuoteDao().upsert(
                JobQuoteEntity(
                    id = p.str("id") ?: id,
                    jobId = p.str("jobId") ?: "", quoteId = p.str("quoteId") ?: "",
                    relationType = p.str("relationType") ?: "ADICIONAL",
                    createdAt = p.longOr("createdAt") ?: now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.JOB_ACTIVITY -> db.jobActivityDao().upsert(
                JobActivityEntity(
                    id = p.str("id") ?: id, jobId = p.str("jobId") ?: "",
                    description = p.str("description") ?: "",
                    status = p.str("status") ?: "PENDIENTE",
                    position = p.longOr("position")?.toInt() ?: 0,
                    startedAt = p.longOr("startedAt"), finishedAt = p.longOr("finishedAt"),
                    notes = p.str("notes") ?: "",
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.JOB_MATERIAL -> db.jobMaterialDao().upsert(
                JobMaterialEntity(
                    id = p.str("id") ?: id, jobId = p.str("jobId") ?: "",
                    description = p.str("description") ?: "",
                    origin = p.str("origin") ?: "PROFESIONAL",
                    quantity = p.str("quantity") ?: "", unit = p.str("unit") ?: "",
                    notes = p.str("notes") ?: "",
                    materialId = p.str("materialId"), condition = p.str("condition"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.REPORT -> db.reportDao().upsert(
                ReportEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    clientId = p.str("clientId") ?: "", profileId = p.str("profileId") ?: "",
                    provisionalNumber = p.str("provisionalNumber") ?: "",
                    status = p.str("status") ?: "BORRADOR",
                    title = p.str("title") ?: "",
                    destination = p.str("destination") ?: "INTERNO",
                    content = p.str("content") ?: "",
                    currentVersion = p.longOr("currentVersion")?.toInt() ?: 0,
                    emittedVersion = p.longOr("emittedVersion")?.toInt() ?: 0,
                    clientNameSnapshot = p.str("clientNameSnapshot") ?: "",
                    archived = p.boolOr("archived"),
                    officialNumber = p.str("officialNumber"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.REPORT_JOB -> db.reportJobDao().upsert(
                ReportJobEntity(
                    id = p.str("id") ?: id,
                    reportId = p.str("reportId") ?: "", jobId = p.str("jobId") ?: "",
                    position = p.longOr("position")?.toInt() ?: 0,
                    createdAt = p.longOr("createdAt") ?: now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.COLLECTION_ACCOUNT -> {
                // Documento completo: fila + líneas + vínculos reconstruidos
                // desde el payload autoritativo del servidor.
                val accountId = p.str("id") ?: id
                db.collectionAccountDao().upsert(
                    CollectionAccountEntity(
                        id = accountId, userId = p.str("userId") ?: "",
                        clientId = p.str("clientId") ?: "", profileId = p.str("profileId") ?: "",
                        provisionalNumber = p.str("provisionalNumber") ?: "",
                        status = p.str("status") ?: "PENDIENTE",
                        currency = p.str("currency") ?: "COP",
                        gross = p.str("gross") ?: "0", totalRetained = p.str("totalRetained") ?: "0",
                        net = p.str("net") ?: "0",
                        appliedAmount = p.str("appliedAmount") ?: "0",
                        balance = p.str("balance") ?: "0",
                        // Espejo confirmado: el payload remoto ES lo aplicado.
                        serverAppliedAmount = p.str("appliedAmount") ?: "0",
                        serverBalance = p.str("balance") ?: "0",
                        issuedAt = p.longOr("issuedAt") ?: now,
                        dueDateEpochDay = p.longOr("dueDateEpochDay"),
                        conditions = p.str("conditions") ?: "",
                        clientSnapshotJson = p.str("clientSnapshotJson") ?: "",
                        profileSnapshotJson = p.str("profileSnapshotJson") ?: "",
                        retentionsSnapshotJson = p.str("retentionsSnapshotJson") ?: "",
                        clientNameSnapshot = p.str("clientNameSnapshot") ?: "",
                        archived = p.boolOr("archived"),
                        officialNumber = p.str("officialNumber"),
                        createdAt = p.longOr("createdAt") ?: now,
                        updatedAt = now,
                        pendingSync = false,
                        serverVersion = sv,
                    ),
                )
                val items = p["items"]?.jsonArray?.mapIndexed { i, el ->
                    val it = el.jsonObject
                    CollectionAccountItemEntity(
                        id = it.str("id") ?: Ids.new(), accountId = accountId,
                        position = it.longOr("position")?.toInt() ?: (i + 1),
                        conceptType = it.str("conceptType") ?: "SERVICIO",
                        description = it.str("description") ?: "",
                        quantity = it.str("quantity") ?: "", unit = it.str("unit") ?: "",
                        unitPrice = it.str("unitPrice") ?: "",
                        lineTotal = it.str("lineTotal") ?: "",
                        createdAt = it.longOr("createdAt") ?: now,
                    )
                } ?: emptyList()
                db.collectionAccountItemDao().deleteForAccount(accountId)
                db.collectionAccountItemDao().upsertAll(items)
                val jobIds = p["jobIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val quoteIds = p["quoteIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                db.collectionAccountJobDao().deleteForAccount(accountId)
                db.collectionAccountJobDao().upsertAll(
                    jobIds.mapIndexed { i, jobId ->
                        CollectionAccountJobEntity(
                            id = Ids.new(), accountId = accountId, jobId = jobId,
                            position = i + 1, createdAt = now,
                        )
                    },
                )
                db.collectionAccountQuoteDao().deleteForAccount(accountId)
                db.collectionAccountQuoteDao().upsertAll(
                    quoteIds.mapIndexed { i, quoteId ->
                        CollectionAccountQuoteEntity(
                            id = Ids.new(), accountId = accountId, quoteId = quoteId,
                            position = i + 1, createdAt = now,
                        )
                    },
                )
            }
            EntityTypes.COLLECTION_METHOD -> db.collectionMethodDao().upsert(
                CollectionMethodEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    name = p.str("name") ?: "", detail = p.str("detail") ?: "",
                    archived = p.boolOr("archived"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.PAYMENT -> db.paymentDao().upsert(
                PaymentEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    clientId = p.str("clientId") ?: "",
                    collectionMethodId = p.str("collectionMethodId"),
                    amount = p.str("amount") ?: "0",
                    paidAtEpochDay = p.longOr("paidAtEpochDay") ?: 0,
                    reference = p.str("reference") ?: "", notes = p.str("notes") ?: "",
                    appliedAmount = p.str("appliedAmount") ?: "0",
                    unassignedAmount = p.str("unassignedAmount") ?: "0",
                    // F7: los campos nuevos llegan normalizados por el
                    // servidor; los Pagos F6 sin ellos derivan de amount.
                    effectiveAmount = p.str("effectiveAmount") ?: (p.str("amount") ?: "0"),
                    refundedAmount = p.str("refundedAmount") ?: "0.00",
                    // Espejo confirmado (F-pausadas): derivados tal como el
                    // servidor los reporta.
                    serverAppliedAmount = p.str("appliedAmount") ?: "0",
                    serverUnassignedAmount = p.str("unassignedAmount") ?: "0",
                    serverRefundedAmount = p.str("refundedAmount") ?: "0.00",
                    annulled = p.boolOr("annulled"),
                    assignedAccountId = p.str("assignedAccountId"),
                    assignedConsolidatedId = p.str("assignedConsolidatedId"),
                    assignedTargetType = p.str("assignedTargetType")?.uppercase(),
                    assignmentStatus = p.str("assignmentStatus") ?: "UNASSIGNED",
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.PAYMENT_ASSIGNMENT -> db.paymentAssignmentDao().upsert(
                PaymentAssignmentEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    paymentId = p.str("paymentId") ?: "",
                    action = (p.str("action") ?: "assign").uppercase(),
                    targetType = (p.str("targetType") ?: "account").uppercase(),
                    accountId = p.str("accountId"),
                    consolidatedId = p.str("consolidatedId"),
                    fromAccountId = p.str("fromAccountId"),
                    fromConsolidatedId = p.str("fromConsolidatedId"),
                    amount = p.str("amount") ?: "0",
                    releasedAmount = p.str("releasedAmount") ?: "0",
                    reason = p.str("reason") ?: "",
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.CONSOLIDATED_COLLECTION -> {
                // Documento completo: fila + membresía reconstruida desde
                // el payload autoritativo del servidor.
                val consId = p.str("id") ?: id
                db.consolidatedCollectionDao().upsert(
                    ConsolidatedCollectionEntity(
                        id = consId, userId = p.str("userId") ?: "",
                        clientId = p.str("clientId") ?: "",
                        provisionalNumber = p.str("provisionalNumber") ?: "",
                        status = p.str("status") ?: "PENDIENTE",
                        currency = p.str("currency") ?: "COP",
                        totalAmount = p.str("totalAmount") ?: "0",
                        appliedAmount = p.str("appliedAmount") ?: "0",
                        balance = p.str("balance") ?: "0",
                        serverAppliedAmount = p.str("appliedAmount") ?: "0",
                        serverBalance = p.str("balance") ?: "0",
                        clientNameSnapshot = p.str("clientNameSnapshot") ?: "",
                        archived = p.boolOr("archived"),
                        officialNumber = p.str("officialNumber"),
                        createdAt = p.longOr("createdAt") ?: now,
                        updatedAt = now,
                        pendingSync = false,
                        serverVersion = sv,
                    ),
                )
                val accountIds = p["accountIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                    ?: emptyList()
                db.consolidatedCollectionAccountDao().deleteForConsolidated(consId)
                db.consolidatedCollectionAccountDao().upsertAll(
                    accountIds.mapIndexed { i, accId ->
                        ConsolidatedCollectionAccountEntity(
                            id = Ids.new(), consolidatedId = consId, accountId = accId,
                            position = i + 1, createdAt = now,
                        )
                    },
                )
            }
            EntityTypes.PAYMENT_ADJUSTMENT -> db.paymentAdjustmentDao().upsert(
                PaymentAdjustmentEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    paymentId = p.str("paymentId") ?: "",
                    kind = p.str("kind") ?: "CORRECTION",
                    reason = p.str("reason") ?: "",
                    newEffectiveAmount = p.str("newEffectiveAmount"),
                    effectiveBefore = p.str("effectiveBefore") ?: "",
                    effectiveAfter = p.str("effectiveAfter") ?: "",
                    releasedAmount = p.str("releasedAmount") ?: "0.00",
                    releasedFromType = p.str("releasedFromType")?.uppercase(),
                    releasedFromId = p.str("releasedFromId"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.PAYMENT_REFUND -> db.paymentRefundDao().upsert(
                PaymentRefundEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    paymentId = p.str("paymentId") ?: "",
                    amount = p.str("amount") ?: "0",
                    refundedAtEpochDay = p.longOr("refundedAtEpochDay") ?: 0,
                    collectionMethodId = p.str("collectionMethodId"),
                    reason = p.str("reason") ?: "",
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.MATERIAL -> db.materialDao().upsert(
                MaterialEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    name = p.str("name") ?: "", unit = p.str("unit") ?: "",
                    description = p.str("description") ?: "",
                    archived = p.boolOr("archived"),
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.PURCHASE -> {
                // Documento completo: fila + líneas reconstruidas desde el
                // payload autoritativo del servidor (mismo patrón que la
                // Cuenta de Cobro).
                val purchaseId = p.str("id") ?: id
                db.purchaseDao().upsert(
                    PurchaseEntity(
                        id = purchaseId, userId = p.str("userId") ?: "",
                        provisionalNumber = p.str("provisionalNumber") ?: "",
                        status = p.str("status") ?: "BORRADOR",
                        purchaseDateEpochDay = p.longOr("purchaseDateEpochDay") ?: 0,
                        supplierPlace = p.str("supplierPlace") ?: "",
                        reference = p.str("reference") ?: "",
                        notes = p.str("notes") ?: "",
                        currency = p.str("currency") ?: "COP",
                        totalAmount = p.str("totalAmount") ?: "0.00",
                        archived = p.boolOr("archived"),
                        officialNumber = p.str("officialNumber"),
                        createdAt = p.longOr("createdAt") ?: now,
                        updatedAt = now,
                        pendingSync = false,
                        serverVersion = sv,
                    ),
                )
                val items = p["items"]?.jsonArray?.mapIndexed { i, el ->
                    val it = el.jsonObject
                    PurchaseItemEntity(
                        id = it.str("id") ?: Ids.new(), purchaseId = purchaseId,
                        position = it.longOr("position")?.toInt() ?: (i + 1),
                        materialId = it.str("materialId") ?: "",
                        materialNameSnapshot = it.str("materialNameSnapshot") ?: "",
                        quantity = it.str("quantity") ?: "",
                        unit = it.str("unit") ?: "",
                        unitPrice = it.str("unitPrice") ?: "",
                        lineTotal = it.str("lineTotal") ?: "",
                        condition = it.str("condition") ?: "NUEVO",
                        createdAt = it.longOr("createdAt") ?: now,
                    )
                } ?: emptyList()
                db.purchaseItemDao().deleteForPurchase(purchaseId)
                db.purchaseItemDao().upsertAll(items)
            }
            EntityTypes.INVENTORY_MOVEMENT -> db.inventoryMovementDao().upsert(
                InventoryMovementEntity(
                    id = p.str("id") ?: id, userId = p.str("userId") ?: "",
                    materialId = p.str("materialId") ?: "",
                    condition = p.str("condition") ?: "NUEVO",
                    movementType = p.str("movementType") ?: "ADJUSTMENT_IN",
                    origin = p.str("origin") ?: "AJUSTE",
                    quantity = p.str("quantity") ?: "", unit = p.str("unit") ?: "",
                    unitCost = p.str("unitCost"), purchaseId = p.str("purchaseId"),
                    jobId = p.str("jobId"), jobMaterialId = p.str("jobMaterialId"),
                    note = p.str("note") ?: "",
                    occurredAt = p.longOr("occurredAt") ?: now,
                    createdAt = p.longOr("createdAt") ?: now,
                    updatedAt = now,
                    pendingSync = false,
                    serverVersion = sv,
                ),
            )
            EntityTypes.INVENTORY_STOCK -> db.materialStockDao().upsert(
                MaterialStockEntity(
                    materialId = p.str("materialId") ?: id,
                    stockNew = p.str("stockNew") ?: "0",
                    stockUsed = p.str("stockUsed") ?: "0",
                    updatedAt = now,
                    serverVersion = sv,
                ),
            )
        }
    }

    // ---------- Vinculación de identidad (registro / login en instalación con datos) ----------

    /**
     * Reasigna transaccionalmente el user_id local a la identidad canónica del
     * servidor conservando los UUID de las entidades. Las operaciones
     * encoladas se regeneran con nuevos operation_id (misma secuencia,
     * supersedes → operación original) y el historial queda preservado.
     *
     * Guarda de titularidad (D3): la adopción local solo es válida para datos
     * que nunca se sincronizaron. Si la copia local ya fue reconocida por el
     * servidor bajo una cuenta distinta (entidades con serverVersion > 0 o
     * cursor de pull consumido), vincular otra cuenta las haría aparecer como
     * propias mientras el servidor las sigue atribuyendo a la cuenta original;
     * las escrituras posteriores serían rechazadas con FORBIDDEN_ENTITY y
     * quedarían pausadas sin vía de resolución. En ese estado el vínculo a
     * otra cuenta se rechaza sin tocar filas, cola ni sesión: el usuario debe
     * recuperar la sesión de la cuenta original (mismo userId: siempre
     * permitido) y desde ella ejecutar el flujo seguro de Cambiar de cuenta.
     * Esto también cubre el estado residual de un cierre inesperado durante
     * switchAccount entre clearSession() y wipeLocalData().
     *
     * Devuelve null en éxito o el mensaje de bloqueo para la interfaz.
     */
    suspend fun bindAccount(userId: String, email: String, token: String): String? {
        val localUser = db.userDao().get()
        val oldId = localUser?.id
        if (oldId != userId && (session.cursor() > 0L || syncedEntityCount() > 0)) {
            return "Los datos locales ya pertenecen a otra cuenta sincronizada " +
                "y se conservan intactos. Inicia sesión con esa cuenta para " +
                "recuperar la sesión; el cambio de cuenta se realiza desde ella."
        }
        db.withTransaction {
            if (localUser != null && oldId != userId) {
                // Reasignar user_id en todas las tablas sin tocar UUIDs.
                db.openHelper.writableDatabase.execSQL("UPDATE users SET id = ?, email = ? WHERE id = ?", arrayOf(userId, email, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE professional_profiles SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE clients SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE services SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE retention_configs SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE quotes SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE jobs SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE reports SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE collection_accounts SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE collection_methods SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE payments SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE payment_assignments SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                // F7: las tablas nuevas también reasignan su titular.
                db.openHelper.writableDatabase.execSQL("UPDATE consolidated_collections SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE payment_adjustments SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE payment_refunds SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                // F8: catálogo, compras y movimientos también reasignan titular.
                db.openHelper.writableDatabase.execSQL("UPDATE materials SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE purchases SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                db.openHelper.writableDatabase.execSQL("UPDATE inventory_movements SET userId = ? WHERE userId = ?", arrayOf(userId, oldId))
                // Regenerar operaciones pendientes con identidad canónica:
                // reescribe userId en el payload y, para la entidad user,
                // también entityId y payload.id hacia el id canónico.
                val pending = db.syncDao().pendingOps()
                val now = nowEpochMillis()
                for (op in pending) {
                    val obj = json.parseToJsonElement(op.payloadJson).jsonObject
                    val isUserOp = op.entityType == EntityTypes.USER
                    val rewritten = buildJsonObject {
                        for ((k, v) in obj) put(
                            k,
                            when {
                                k == "userId" && v.jsonPrimitive.content == oldId -> kotlinx.serialization.json.JsonPrimitive(userId)
                                isUserOp && k == "id" -> kotlinx.serialization.json.JsonPrimitive(userId)
                                else -> v
                            },
                        )
                    }.toString()
                    db.syncDao().insertOp(
                        op.copy(
                            opId = Ids.new(),
                            entityId = if (isUserOp) userId else op.entityId,
                            payloadJson = rewritten,
                            supersedesOpId = op.opId,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    db.syncDao().updateOpResult(op.opId, "DONE", null, "SUPERSEDED", now)
                }
                // La cuenta nueva puede traer datos de otros dispositivos:
                // reasignar a otra identidad reinicia el pull completo.
                session.setCursor(0)
            } else if (localUser == null) {
                // Fila local para la UI tras login en instalación limpia:
                // pendingSync=false evita un conflicto fantasma cuando el
                // pull entregue la entidad user remota (si existe).
                db.userDao().insert(
                    UserEntity(
                        id = userId, name = email.substringBefore('@'), email = email,
                        createdAt = nowEpochMillis(), updatedAt = nowEpochMillis(),
                        pendingSync = false,
                    ),
                )
                session.setCursor(0)
            } else {
                // Mismo usuario canónico (re-login tras pérdida de sesión):
                // solo se refresca el correo — conservar el cursor evita una
                // descarga completa innecesaria.
                db.userDao().insert(localUser.copy(email = email, updatedAt = nowEpochMillis()))
            }
        }
        session.accountUserId = userId
        session.accountEmail = email
        session.token = token
        return null
    }

    /**
     * Entidades que el servidor ya reconoció (serverVersion > 0): pertenecen
     * a la cuenta que las sincronizó. Las tablas sin serverVersion (versiones,
     * líneas y vínculos) dependen de un padre sí contado — el servidor valida
     * cada referencia — por lo que no pueden divergir solas.
     */
    private suspend fun syncedEntityCount(): Int {
        var n = 0
        for (t in listOf(
            "users", "professional_profiles", "clients", "services", "retention_configs",
            "quotes", "jobs", "job_quotes", "job_activities", "job_materials",
            "reports", "report_jobs",
            "collection_accounts", "collection_methods", "payments", "payment_assignments",
            "consolidated_collections", "payment_adjustments", "payment_refunds",
            "materials", "material_stocks", "purchases", "inventory_movements",
        )) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t WHERE serverVersion > 0").use { c ->
                if (c.moveToFirst()) n += c.getInt(0)
            }
        }
        return n
    }

    // ---------- Cambio de cuenta (regla aprobada) ----------

    suspend fun pendingEntityCount(): Int {
        var n = 0
        for (t in listOf(
            "clients", "services", "retention_configs", "quotes", "professional_profiles",
            "users", "jobs", "job_quotes", "job_activities", "job_materials",
            "reports", "report_jobs",
            "collection_accounts", "collection_methods", "payments", "payment_assignments",
            "consolidated_collections", "payment_adjustments", "payment_refunds",
            "materials", "purchases", "inventory_movements",
        )) {
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $t WHERE pendingSync = 1").use { c ->
                if (c.moveToFirst()) n += c.getInt(0)
            }
        }
        return n
    }

    /** El cambio solo es posible con cola vacía, cero pendientes y sin conflictos abiertos. */
    /**
     * Motivos concretos que mantienen bloqueado el cambio de cuenta:
     * operaciones en cola (incluye las que esperan prerequisitos),
     * operaciones pausadas sin resolver y entidades con pendingSync.
     * El cambio solo se desbloquea cuando todo converge o el usuario
     * resolvió explícitamente la pérdida local prevista (descarte).
     */
    suspend fun switchBlockers(): List<String> {
        val blockers = mutableListOf<String>()
        val pending = db.syncDao().pendingCount()
        if (pending > 0) blockers += "$pending operaciones en cola"
        val paused = db.syncDao().pausedOpsNow().size
        if (paused > 0) blockers += "$paused operaciones rechazadas sin resolver"
        val unsynced = pendingEntityCount()
        if (unsynced > 0) blockers += "$unsynced entidades pendientes de sincronización"
        return blockers
    }

    /** Limpia la copia local tras reautenticación + confirmación + verificación. */
    suspend fun wipeLocalData() {
        db.withTransaction {
            for (t in listOf(
                "inventory_movements", "material_stocks", "purchase_items", "purchases",
                "materials",
                "payment_refunds", "payment_adjustments", "payment_assignments", "payments",
                "consolidated_collection_accounts", "consolidated_collections",
                "collection_account_jobs", "collection_account_quotes",
                "collection_account_items", "collection_accounts", "collection_methods",
                "report_jobs", "report_versions", "reports",
                "job_quotes", "job_activities", "job_materials", "jobs",
                "quote_versions", "quotes", "services", "retention_configs",
                "clients", "professional_profiles", "users",
                "sync_operations", "sync_conflicts", "sync_meta",
            )) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM $t")
            }
        }
        session.setCursor(0)
    }

    // ---------- Resolución de conflictos (decisión del usuario) ----------

    /**
     * El usuario conserva su copia local: la operación pausada queda
     * sustituida por una NUEVA (operation_id propio, supersedes → op
     * original, base corregida al servidor remoto). Así una operación
     * corregida nunca reutiliza el mismo operation_id (regla aprobada).
     */
    suspend fun resolveKeepLocal(conflictId: Long) {
        val conflict = db.syncDao().observeConflicts().first().firstOrNull { it.id == conflictId } ?: return
        val now = nowEpochMillis()
        db.withTransaction {
            val paused = db.syncDao().pendingOpsForEntity(conflict.entityType, conflict.entityId).firstOrNull()
            if (paused != null) {
                db.syncDao().updateOpResult(paused.opId, "DONE", null, "SUPERSEDED", now)
                enqueue(
                    paused.entityType, paused.entityId,
                    json.parseToJsonElement(paused.payloadJson).jsonObject,
                    baseServerVersion = conflict.remoteServerVersion,
                    opType = paused.opType,
                    supersedes = paused.opId,
                )
            }
            db.syncDao().resolveConflict(conflictId)
        }
    }

    /** El usuario conserva la copia remota: la operación local queda sustituida y se aplica el payload remoto. */
    suspend fun resolveKeepRemote(conflictId: Long) {
        val conflict = db.syncDao().observeConflicts().first().firstOrNull { it.id == conflictId } ?: return
        db.withTransaction {
            pendingOpIdFor(conflict)?.let { opId ->
                db.syncDao().updateOpResult(opId, "DONE", null, "SUPERSEDED_BY_REMOTE", nowEpochMillis())
            }
            upsertFromPayload(
                conflict.entityType, conflict.entityId,
                json.parseToJsonElement(conflict.remoteJson).jsonObject,
                conflict.remoteServerVersion,
            )
            // El número oficial y el conflicto de versiones viajan fuera del
            // payload (meta del servidor): reaplicarlos para que conservar
            // remoto no pierda el documento completo.
            val remote = json.parseToJsonElement(conflict.remoteJson).jsonObject
            when (conflict.entityType) {
                EntityTypes.JOB -> db.jobDao().updateOfficial(
                    conflict.entityId, remote.str("official_number"), nowEpochMillis())
                EntityTypes.QUOTE -> db.quoteDao().updateSyncFields(
                    conflict.entityId, remote.str("official_number"),
                    remote.boolOr("has_version_conflict"), nowEpochMillis())
                EntityTypes.REPORT -> db.reportDao().updateSyncFields(
                    conflict.entityId, remote.str("official_number"),
                    remote.boolOr("has_version_conflict"), nowEpochMillis())
                EntityTypes.COLLECTION_ACCOUNT -> db.collectionAccountDao().updateOfficial(
                    conflict.entityId, remote.str("official_number"), nowEpochMillis())
                EntityTypes.CONSOLIDATED_COLLECTION -> db.consolidatedCollectionDao().updateOfficial(
                    conflict.entityId, remote.str("official_number"), nowEpochMillis())
                EntityTypes.PURCHASE -> db.purchaseDao().updateOfficial(
                    conflict.entityId, remote.str("official_number"), nowEpochMillis())
            }
            db.syncDao().resolveConflict(conflictId)
        }
    }

    private suspend fun pendingOpIdFor(conflict: SyncConflictEntity): String? =
        db.syncDao().pendingOpsForEntity(conflict.entityType, conflict.entityId).firstOrNull()?.opId

    /** Reintento manual de una operación pausada (misma operación, mismo payload). */
    // ---------- Resolución de operaciones rechazadas (PAUSED) ----------

    /** Tipos append-only/versionados cuya fila local puede quedar como
     *  registro rechazado tras reconocimiento explícito (no se borran ni
     *  reescriben; el historial conserva el rechazo). */
    private val ACKNOWLEDGEABLE = setOf(
        EntityTypes.PAYMENT, EntityTypes.PAYMENT_ASSIGNMENT,
        EntityTypes.PAYMENT_ADJUSTMENT, EntityTypes.PAYMENT_REFUND,
        EntityTypes.INVENTORY_MOVEMENT, EntityTypes.JOB_QUOTE,
        EntityTypes.REPORT_JOB, EntityTypes.QUOTE_VERSION,
        EntityTypes.REPORT_VERSION,
    )

    /** Tablas con columna serverVersion (las de documento incrustado no la tienen). */
    private val SYNCED_FLAG_TABLES = setOf(
        "users", "professional_profiles", "clients", "services",
        "retention_configs", "quotes", "jobs", "job_quotes", "job_activities",
        "job_materials", "reports", "report_jobs", "collection_accounts",
        "collection_methods", "payments", "payment_assignments",
        "consolidated_collections", "payment_adjustments", "payment_refunds",
        "materials", "purchases", "inventory_movements",
    )

    /** Vista de una operación pausada para la pantalla de atención. */
    data class PausedOpView(
        val op: SyncOperationEntity,
        val label: String,
        val internalExhausted: Boolean,
        val forbiddenEntityScope: String?,
        val canReissue: Boolean,
        val canRestoreRemote: Boolean,
        val canDiscard: Boolean,
        val canAcknowledge: Boolean,
        val waitingDependents: Int,
        /** OpIds PENDING/RETRY de la misma entidad: la restauración remota
         *  los descarta explícitamente (su payload reaplicaría los cambios
         *  que el servidor rechazó). La confirmación revalida el conjunto. */
        val pendingOpsOnEntity: List<String>,
    )

    /** Fila afectada por un descarte (tabla física + id + bandera sincronizada). */
    data class DiscardRow(val table: String, val entityType: String, val id: String, val synced: Boolean)

    data class DiscardPreview(
        val rows: List<DiscardRow>,
        val blockers: List<String>,
        val waitingDependents: List<SyncOperationEntity>,
    ) {
        /**
         * Identidad exacta de lo que se descartaría: "tabla:id" por fila
         * de la cascada más "dep:opId" por cada operación dependiente que
         * quedaría marcada. La confirmación revalida este conjunto dentro
         * de la transacción — un cambio de contenido con el mismo número
         * de filas no pasa desapercibido (revisión del PR #24).
         */
        fun signature(): Set<String> =
            rows.mapTo(mutableSetOf()) { "${it.table}:${it.id}" } +
                waitingDependents.mapTo(mutableSetOf()) { "dep:${it.opId}" }
    }

    private fun opResultJson(op: SyncOperationEntity): JsonObject? = op.resultJson
        ?.let { runCatching { json.parseToJsonElement(it).jsonObject }.getOrNull() }

    private fun forbiddenScope(op: SyncOperationEntity): String? =
        opResultJson(op)?.str("forbidden_scope")

    private fun serverStateOf(op: SyncOperationEntity): JsonObject? =
        opResultJson(op)?.get("server_state")?.let { it as? JsonObject }

    private fun rawIds(table: String, column: String, id: String): List<String> =
        db.openHelper.writableDatabase
            .query("SELECT `id` FROM `$table` WHERE `$column` = ?", arrayOf(id))
            .use { c -> generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList() }

    private fun rawCount(table: String, column: String, id: String): Int =
        db.openHelper.writableDatabase
            .query("SELECT COUNT(*) FROM `$table` WHERE `$column` = ?", arrayOf(id))
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun rowSynced(table: String, id: String): Boolean {
        if (table !in SYNCED_FLAG_TABLES) return false
        return db.openHelper.writableDatabase
            .query("SELECT `serverVersion` FROM `$table` WHERE `id` = ?", arrayOf(id))
            .use { c -> c.moveToFirst() && c.getInt(0) > 0 }
    }

    private fun entityLabel(table: String, id: String): String {
        val labelCol = when (table) {
            "clients", "materials", "services", "collection_methods" -> "name"
            "quotes", "jobs", "reports", "collection_accounts",
            "consolidated_collections", "purchases" -> "provisionalNumber"
            "job_activities", "job_materials" -> "description"
            "payments" -> "amount"
            "inventory_movements" -> "movementType"
            else -> null
        }
        val label = labelCol?.let { col ->
            runCatching {
                db.openHelper.writableDatabase
                    .query("SELECT `$col` FROM `$table` WHERE `id` = ?", arrayOf(id))
                    .use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }.getOrNull()
        }
        return label?.takeIf { it.isNotBlank() } ?: id.take(8)
    }

    /**
     * Filas alcanzables por la cascada de descarte (BFS sobre CHILD_TABLES);
     * la primera es la raíz. Nunca toca filas fuera del mapa de hijos.
     */
    private fun collectCascade(type: String, id: String): List<DiscardRow> {
        val out = mutableListOf<DiscardRow>()
        val seen = mutableSetOf<Pair<String, String>>()
        val queue = ArrayDeque<Pair<String, String>>()
        queue.add(type to id)
        while (queue.isNotEmpty()) {
            val (t, eid) = queue.removeFirst()
            if (!seen.add(t to eid)) continue
            val table = Rejections.TABLE_BY_TYPE[t] ?: continue
            if (rawIds(table, "id", eid).isEmpty()) continue
            out += DiscardRow(table, t, eid, rowSynced(table, eid))
            for ((childTable, col) in Rejections.CHILD_TABLES[t].orEmpty()) {
                val childType = Rejections.TYPE_BY_TABLE[childTable] ?: continue
                for (cid in rawIds(childTable, col, eid)) queue.add(childType to cid)
            }
        }
        return out
    }

    /**
     * Operaciones sin resolver de OTRAS entidades que declaran referencia a
     * alguna de estas (type,id) — la cascada de espera por prerequisito.
     */
    private suspend fun dependentsOf(refs: Set<Pair<String, String>>): List<SyncOperationEntity> =
        db.syncDao().unresolvedOps().filter { op ->
            dependencyRefs(op).any { (t, i) -> (t to i) in refs }
        }

    /** Detalle para la UI de atención: acciones disponibles según el código. */
    suspend fun pausedDetail(op: SyncOperationEntity): PausedOpView {
        val scope = forbiddenScope(op)
        val local = getLocal(op.entityType, op.entityId)
        val state = serverStateOf(op)
        val exhausted = op.resultCode == Rejections.INTERNAL_ERROR &&
            op.attempts >= Rejections.MAX_INTERNAL_ATTEMPTS
        val reissueBlocked = op.resultCode in setOf(
            Rejections.FORBIDDEN_ENTITY, Rejections.IDEMPOTENCY_KEY_REUSED,
            Rejections.IMMUTABLE_FINANCIAL,
        )
        val waiting = dependentsOf(setOf(op.entityType to op.entityId)).size
        val pendingOnEntity = db.syncDao().pendingOpsForEntity(op.entityType, op.entityId)
            .filter { it.state == "PENDING" || it.state == "RETRY" }
            .map { it.opId }
        return PausedOpView(
            op = op,
            label = Rejections.TABLE_BY_TYPE[op.entityType]?.let { entityLabel(it, op.entityId) }
                ?: op.entityId.take(8),
            internalExhausted = exhausted,
            forbiddenEntityScope = scope,
            canReissue = !reissueBlocked && op.resultCode != Rejections.INTERNAL_ERROR,
            // Restaurar: solo con estado del servidor propio y autoritativo.
            canRestoreRemote = state != null && scope != "entity_foreign" &&
                op.entityType in Rejections.SNAPSHOT_TYPES,
            // Descartar: nunca-sincronizado (serverVersion=0) o residuo de
            // otra cuenta. Los eventos append-only propios no se borran: se
            // reconocen (salvo residuo ajeno, que sí se descarta).
            canDiscard = op.entityType != EntityTypes.USER &&
                op.entityType != EntityTypes.QUOTE_VERSION &&
                op.entityType != EntityTypes.REPORT_VERSION &&
                (scope == "entity_foreign" ||
                    (op.entityType !in ACKNOWLEDGEABLE && (local?.second ?: 0) == 0)),
            canAcknowledge = op.entityType in ACKNOWLEDGEABLE &&
                op.resultCode != Rejections.FORBIDDEN_ENTITY,
            waitingDependents = waiting,
            pendingOpsOnEntity = pendingOnEntity,
        )
    }

    /** Vista previa del descarte: filas que se eliminarían, bloqueos y dependientes en espera. */
    suspend fun discardPreview(op: SyncOperationEntity): DiscardPreview {
        val rows = collectCascade(op.entityType, op.entityId)
        val blockers = mutableListOf<String>()
        val scope = forbiddenScope(op)
        val local = getLocal(op.entityType, op.entityId)
        if (scope != "entity_foreign" && local != null && local.second > 0) {
            blockers += "La entidad ya está sincronizada con el servidor: usa «Restaurar estado remoto»."
        }
        val inCascade = rows.map { it.entityType to it.id }.toMutableSet()
        for ((rt, col) in Rejections.REF_BLOCKS[op.entityType].orEmpty()) {
            for (rid in rawIds(rt, col, op.entityId)) {
                val rType = Rejections.TYPE_BY_TABLE[rt] ?: continue
                if ((rType to rid) in inCascade) continue
                blockers += "$rt:$rid referencia a esta entidad"
            }
        }
        val syncedDesc = rows.drop(1).filter { it.synced }
        if (syncedDesc.isNotEmpty()) {
            blockers += "${syncedDesc.size} descendiente(s) ya sincronizados no pueden eliminarse"
        }
        val waiting = dependentsOf(rows.map { it.entityType to it.id }.toSet())
        return DiscardPreview(rows, blockers, waiting)
    }

    /**
     * Descarte explícito de una entidad nunca sincronizada (o residuo
     * ajeno): elimina filas locales y descendientes en cascada tras
     * verificar la vista previa. La confirmación recibe la FIRMA exacta
     * del conjunto (filas "tabla:id" + dependientes "dep:opId") y se
     * revalida DENTRO de la transacción antes de borrar — no basta con
     * el número de filas: si el contenido cambió aunque el total sea
     * igual (concurrencia), se aborta y se pide una vista previa nueva.
     * Nunca borra el historial de operaciones: cada op queda DONE con
     * código descriptivo y su payload conservado. Los dependientes que
     * esperaban se marcan PREREQUISITE_DISCARDED.
     */
    suspend fun discardPaused(opId: String, expectedSignature: Set<String>): String? {
        val op = db.syncDao().opById(opId) ?: return "Operación no encontrada."
        if (op.state != "PAUSED") return "La operación ya no está pausada."
        val preview = discardPreview(op)
        if (preview.blockers.isNotEmpty()) return preview.blockers.joinToString(" ")
        if (preview.signature() != expectedSignature) {
            return "La vista previa quedó desactualizada; revísala de nuevo."
        }
        val now = nowEpochMillis()
        var completed = false
        db.withTransaction {
            // Revalidación atómica del conjunto exacto a borrar dentro de
            // la transacción: la vista previa pudo quedar obsoleta entre
            // el cálculo y la confirmación del usuario.
            val inside = discardPreview(op)
            if (inside.blockers.isNotEmpty() || inside.signature() != expectedSignature) {
                return@withTransaction
            }
            for (row in inside.rows) {
                db.openHelper.writableDatabase
                    .execSQL("DELETE FROM `${row.table}` WHERE `id` = ?", arrayOf(row.id))
                for (childOp in db.syncDao().pendingOpsForEntity(row.entityType, row.id)) {
                    val code = if (row.id == op.entityId) Rejections.RES_DISCARDED
                        else Rejections.RES_CASCADE_DISCARDED
                    db.syncDao().updateOpResult(
                        childOp.opId, "DONE",
                        resolutionResult(childOp, code, now, "descarte de ${op.entityType}:${op.entityId}"),
                        code, now,
                    )
                }
            }
            // El op raíz puede no estar ligada a fila (forbidden/reference):
            if (inside.rows.none { it.entityType == op.entityType && it.id == op.entityId }) {
                db.syncDao().updateOpResult(
                    op.opId, "DONE",
                    resolutionResult(op, Rejections.RES_DISCARDED, now),
                    Rejections.RES_DISCARDED, now,
                )
            }
            val discarded = inside.rows.map { it.entityType to it.id }.toSet() +
                (op.entityType to op.entityId)
            for (dep in dependentsOf(discarded)) {
                if (dep.state == "PAUSED" && dep.resultCode !in Rejections.RELEASABLE_CODES) continue
                db.syncDao().updateOpResult(
                    dep.opId, "PAUSED",
                    resolutionResult(dep, Rejections.RES_PREREQ_DISCARDED, now, op.entityId),
                    Rejections.RES_PREREQ_DISCARDED, now,
                )
            }
            completed = true
        }
        // La revalidación dentro de la transacción abortó sin tocar nada.
        if (!completed) {
            return "La vista previa quedó desactualizada; revísala de nuevo."
        }
        return null
    }

    /**
     * Restauración explícita: aplica el `server_state` autoritativo del
     * resultado (fila propia del titular autenticado) y cierra la op como
     * RESTORED_FROM_REMOTE. Rechaza el intento cuando no hay estado o la
     * fila es de otra identidad.
     * Operaciones PENDING/RETRY posteriores de la misma entidad: su
     * payload reaplicaría los cambios que el servidor acaba de rechazar,
     * así que la restauración las descarta como decisión explícita del
     * usuario — la UI las anuncia en la confirmación y se revalida el
     * conjunto exacto (`expectedPendingOps`) dentro de la transacción;
     * si cambió, se aborta sin tocar nada (revisión del PR #24).
     */
    suspend fun restorePausedRemote(
        opId: String,
        expectedPendingOps: Set<String> = emptySet(),
    ): String? {
        val op = db.syncDao().opById(opId) ?: return "Operación no encontrada."
        if (op.state != "PAUSED") return "La operación ya no está pausada."
        if (forbiddenScope(op) == "entity_foreign") {
            return "La entidad pertenece a otra cuenta: no hay estado remoto propio que restaurar."
        }
        val state = serverStateOf(op)
            ?: return "El rechazo no incluyó un estado del servidor para restaurar."
        if (op.entityType !in Rejections.SNAPSHOT_TYPES) {
            return "Este tipo no tiene un estado restaur-able: usa reconocer o descartar."
        }
        val sv = state.intOr("server_version")?.takeIf { it > 0 }
            ?: return "El estado remoto no trae versión verificable."
        var changed = false
        val now = nowEpochMillis()
        db.withTransaction {
            val pending = db.syncDao().pendingOpsForEntity(op.entityType, op.entityId)
                .filter { it.state == "PENDING" || it.state == "RETRY" }
            if (pending.map { it.opId }.toSet() != expectedPendingOps) {
                // El conjunto en cola cambió desde la confirmación:
                // abortar y pedir una decisión informada nueva.
                changed = true
                return@withTransaction
            }
            upsertFromPayload(op.entityType, op.entityId, state, sv)
            db.syncDao().updateOpResult(
                op.opId, "DONE",
                resolutionResult(op, Rejections.RES_RESTORED, now),
                Rejections.RES_RESTORED, now,
            )
            // Las ops activas posteriores quedan descartadas (decisión
            // informada del usuario): historial intacto, código propio.
            for (p in pending) {
                db.syncDao().updateOpResult(
                    p.opId, "DONE",
                    resolutionResult(p, Rejections.RES_DISCARDED, now, "restauración:${op.opId}"),
                    Rejections.RES_DISCARDED, now,
                )
            }
            // Las demás pausadas de la misma entidad quedan subsumidas por
            // el estado remoto restaurado (misma regla que la supersede).
            for (p in db.syncDao().pendingOpsForEntity(op.entityType, op.entityId)) {
                if (p.opId == op.opId || p.state != "PAUSED" || p.opType != "upsert") continue
                db.syncDao().updateOpResult(
                    p.opId, "DONE",
                    resolutionResult(p, Rejections.RES_SUPERSEDED, now, "restored:${op.opId}"),
                    Rejections.RES_SUPERSEDED, now,
                )
            }
        }
        return if (changed) {
            "La cola de la entidad cambió; revisa de nuevo antes de restaurar."
        } else {
            null
        }
    }

    /**
     * Reconocimiento explícito de un evento append-only rechazado: la fila
     * local queda registrada pero no confirmada (pendingSync=0 con marca de
     * rechazo en su última op); jamás se borra ni reescribe. El efecto
     * optimista deja de contarse como pendiente y pasa a mostrarse como
     * efecto rechazado — los saldos confirmados no lo incluyen.
     */
    suspend fun acknowledgeRejectedOp(opId: String): String? {
        val op = db.syncDao().opById(opId) ?: return "Operación no encontrada."
        if (op.state != "PAUSED") return "La operación ya no está pausada."
        if (op.entityType !in ACKNOWLEDGEABLE) {
            return "Este tipo no admite reconocimiento: edita, restaura o descarta."
        }
        if (forbiddenScope(op) == "entity_foreign") {
            return "La fila es de otra cuenta: usa descarte con vista previa."
        }
        val now = nowEpochMillis()
        db.withTransaction {
            val table = Rejections.TABLE_BY_TYPE[op.entityType]
            if (table != null && table in SYNCED_FLAG_TABLES) {
                db.openHelper.writableDatabase.execSQL(
                    "UPDATE `$table` SET `pendingSync` = 0 WHERE `id` = ?",
                    arrayOf(op.entityId),
                )
            }
            db.syncDao().updateOpResult(
                op.opId, "DONE",
                resolutionResult(op, Rejections.RES_ACKNOWLEDGED, now),
                Rejections.RES_ACKNOWLEDGED, now,
            )
        }
        return null
    }

    /**
     * «Reintentar» sobre una pausada:
     * - INTERNAL_ERROR: mismo operation_id (el servidor no lo memorizó);
     *   reinicia el presupuesto de reintentos automáticos.
     * - Otros códigos: op NUEVA con el estado local vigente (los rechazos
     *   ordinarios están memorizados y un snapshot obsoleto no se reenvía).
     * - FORBIDDEN_ENTITY / IDEMPOTENCY_KEY_REUSED / IMMUTABLE_FINANCIAL:
     *   nunca se reenvían.
     */
    suspend fun retryPaused(opId: String): String? {
        val op = db.syncDao().opById(opId) ?: return "Operación no encontrada."
        if (op.state != "PAUSED") return "La operación ya no está pausada."
        return when (op.resultCode) {
            Rejections.FORBIDDEN_ENTITY ->
                "La entidad pertenece a otra cuenta: esta operación no se reenvía."
            Rejections.IDEMPOTENCY_KEY_REUSED ->
                "La operación ya fue procesada con otro contenido: no se reenvía."
            Rejections.IMMUTABLE_FINANCIAL ->
                "Evento financiero inmutable: las correcciones son eventos nuevos."
            Rejections.INTERNAL_ERROR -> {
                val now = nowEpochMillis()
                val rj = opResultJson(op)?.let {
                    buildJsonObject {
                        it.forEach { (k, v) -> if (k != "nextRetryAt") put(k, v) }
                    }
                }
                // Reintento manual con el mismo operation_id y presupuesto
                // de reintentos renovado.
                db.syncDao().resetOpAttempts(op.opId, now)
                db.syncDao().rescheduleOp(op.opId, rj?.toString(), now)
                null
            }
            Rejections.DEPENDENCY, Rejections.CONFLICT_BLOCKED -> {
                if (!waitReasonResolved(op, requireProgress = false)) {
                    "El prerequisito sigue sin resolverse: espera o resuélvelo primero."
                } else {
                    db.withTransaction { reissuePausedAsNew(op, nowEpochMillis()) }
                    null
                }
            }
            else -> {
                // VALIDATION_ERROR, INVALID_TRANSITION y demás: reemisión
                // con el estado actual de la fila (incluye la corrección
                // si el usuario editó la entidad antes de reintentar).
                db.withTransaction { reissuePausedAsNew(op, nowEpochMillis()) }
                null
            }
        }
    }

    /**
     * Resolución de conflicto de versiones de Cotización (D3): crea una
     * versión NUEVA con el contenido elegido; snapshots previos intactos.
     * `keepVersionRowId` es el id de la FILA elegida — versiones
     * concurrentes pueden compartir número y distinguirse por id.
     */
    suspend fun resolveQuoteVersions(quoteId: String, keepVersionRowId: String): Boolean {
        val quote = db.quoteDao().get(quoteId) ?: return false
        val kept = db.quoteVersionDao().get(keepVersionRowId) ?: return false
        val latest = db.quoteVersionDao().getLatest(quoteId) ?: return false
        val newVersion = latest.version + 1
        val now = nowEpochMillis()
        db.withTransaction {
            val versionEntity = QuoteVersionEntity(
                id = Ids.new(), quoteId = quoteId, version = newVersion,
                snapshotJson = kept.snapshotJson, createdAt = now,
            )
            db.quoteVersionDao().insert(versionEntity)
            db.quoteDao().upsert(
                quote.copy(
                    currentVersion = newVersion,
                    emittedVersion = if (quote.emittedVersion > 0) newVersion else 0,
                    updatedAt = now,
                ),
            )
            enqueue(
                EntityTypes.QUOTE_VERSION, versionEntity.id,
                SyncPayloads.quoteVersion(versionEntity),
                baseServerVersion = 0,
                opType = "resolve",
            )
        }
        return true
    }

    /**
     * Resolución de conflicto de versiones de Informe (misma regla que
     * Cotización): crea una versión NUEVA con el contenido elegido
     * enlazada por `previousVersionId`; snapshots previos intactos y
     * nunca una sobreescritura silenciosa.
     */
    suspend fun resolveReportVersions(reportId: String, keepVersionRowId: String): Boolean {
        val report = db.reportDao().get(reportId) ?: return false
        val kept = db.reportVersionDao().get(keepVersionRowId) ?: return false
        if (kept.reportId != reportId) return false
        val latest = db.reportVersionDao().getLatest(reportId) ?: return false
        val newVersion = latest.version + 1
        val now = nowEpochMillis()
        db.withTransaction {
            val versionEntity = ReportVersionEntity(
                id = Ids.new(), reportId = reportId, version = newVersion,
                snapshotJson = kept.snapshotJson,
                previousVersionId = latest.id, createdAt = now,
            )
            db.reportVersionDao().insert(versionEntity)
            db.reportDao().upsert(
                report.copy(
                    currentVersion = newVersion,
                    emittedVersion = if (report.emittedVersion > 0) newVersion else 0,
                    updatedAt = now,
                ),
            )
            enqueue(
                EntityTypes.REPORT_VERSION, versionEntity.id,
                SyncPayloads.reportVersion(versionEntity),
                baseServerVersion = 0,
                opType = "resolve",
            )
        }
        return true
    }
}

data class SyncSummary(val pushed: Int, val paused: Int, val pulled: Int)

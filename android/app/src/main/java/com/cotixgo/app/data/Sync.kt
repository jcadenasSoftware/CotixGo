package com.cotixgo.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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
 * Sesión local de sincronización: token en EncryptedSharedPreferences
 * (Keystore), identidad de cuenta y device_id en preferencias, cursor en
 * `sync_meta` (se reinicia al cambiar de cuenta o limpiar datos).
 */
class Session(context: Context, private val db: AppDatabase) {
    private val appContext = context.applicationContext
    private val plain: SharedPreferences =
        appContext.getSharedPreferences("cotixgo.session", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "cotixgo.secure",
            MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var token: String?
        get() = secure.getString("token", null)
        set(v) = secure.edit().putString("token", v).apply()

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
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val api get() = ApiClient(session.apiBaseUrl) { session.token }

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
                .filter { pushableNow(it) }
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
                    else -> { paused++; applyRejected(op, r) }
                }
            }
        }
        pulled += pullAll()
        return SyncSummary(pushed = pushed, paused = paused, pulled = pulled)
    }

    /**
     * `job_quote` no solo necesita que la Cotización exista en el servidor:
     * la regla aprobada exige que esté APROBADA, lo que puede requerir
     * varias operaciones previas suyas (emitir, aprobar) aún en cola. Como
     * cada tanda lleva una sola operación por entidad, el vínculo espera a
     * que la Cotización no tenga operaciones activas; si las suyas quedan
     * pausadas, el vínculo se envía y el rechazo del servidor queda visible.
     * Misma regla F6: la Cuenta espera a que sus orígenes (Trabajos,
     * Cotizaciones, Cliente, Perfil) no tengan operaciones activas, y un
     * evento de asignación espera a su Pago y a las Cuentas referenciadas.
     */
    private suspend fun pushableNow(op: SyncOperationEntity): Boolean {
        val p = json.parseToJsonElement(op.payloadJson).jsonObject
        suspend fun free(type: String, id: String?): Boolean =
            id == null || db.syncDao().activeOpsForEntity(type, id) == 0
        return when (op.entityType) {
            EntityTypes.JOB_QUOTE -> free(EntityTypes.QUOTE, p.str("quoteId"))
            EntityTypes.COLLECTION_ACCOUNT -> {
                val jobs = p["jobIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                val quotes = p["quoteIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                free(EntityTypes.CLIENT, p.str("clientId")) &&
                    free(EntityTypes.PROFILE, p.str("profileId")) &&
                    jobs.all { free(EntityTypes.JOB, it) } &&
                    quotes.all { free(EntityTypes.QUOTE, it) }
            }
            EntityTypes.PAYMENT -> free(EntityTypes.CLIENT, p.str("clientId")) &&
                free(EntityTypes.COLLECTION_METHOD, p.str("collectionMethodId"))
            EntityTypes.PAYMENT_ASSIGNMENT ->
                free(EntityTypes.PAYMENT, p.str("paymentId")) &&
                    free(EntityTypes.COLLECTION_ACCOUNT, p.str("accountId")) &&
                    free(EntityTypes.COLLECTION_ACCOUNT, p.str("fromAccountId")) &&
                    free(EntityTypes.CONSOLIDATED_COLLECTION, p.str("consolidatedId")) &&
                    free(EntityTypes.CONSOLIDATED_COLLECTION, p.str("fromConsolidatedId"))
            // F7: el Consolidado espera a su Cliente y a sus Cuentas
            // miembro; ajuste y reembolso esperan a su Pago.
            EntityTypes.CONSOLIDATED_COLLECTION -> {
                val accounts = p["accountIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }
                    ?: emptyList()
                free(EntityTypes.CLIENT, p.str("clientId")) &&
                    accounts.all { free(EntityTypes.COLLECTION_ACCOUNT, it) }
            }
            EntityTypes.PAYMENT_ADJUSTMENT, EntityTypes.PAYMENT_REFUND ->
                free(EntityTypes.PAYMENT, p.str("paymentId"))
            // F8: un consumo vinculado espera a que su Material exista en el
            // servidor (los descriptivos sin materialId no esperan nada).
            EntityTypes.JOB_MATERIAL -> free(EntityTypes.MATERIAL, p.str("materialId"))
            // La Compra espera a que todos los Materiales de sus líneas no
            // tengan operaciones activas.
            EntityTypes.PURCHASE -> {
                val mats = p["items"]?.jsonArray?.mapNotNull {
                    it.jsonObject["materialId"]?.jsonPrimitive?.content
                } ?: emptyList()
                mats.all { free(EntityTypes.MATERIAL, it) }
            }
            // El movimiento espera a sus referencias declaradas; los de
            // origen COMPRA los deriva el servidor (nunca se encolan).
            EntityTypes.INVENTORY_MOVEMENT ->
                free(EntityTypes.MATERIAL, p.str("materialId")) &&
                    free(EntityTypes.JOB, p.str("jobId")) &&
                    free(EntityTypes.JOB_MATERIAL, p.str("jobMaterialId"))
            else -> true
        }
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
     */
    suspend fun bindAccount(userId: String, email: String, token: String) {
        val localUser = db.userDao().get()
        val oldId = localUser?.id
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
            } else {
                db.userDao().insert(localUser.copy(email = email, updatedAt = nowEpochMillis()))
            }
            session.setCursor(0) // la cuenta puede traer datos de otros dispositivos
        }
        session.accountUserId = userId
        session.accountEmail = email
        session.token = token
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
    suspend fun switchBlockers(): List<String> {
        val blockers = mutableListOf<String>()
        val pending = db.syncDao().pendingCount()
        if (pending > 0) blockers += "$pending operaciones en cola"
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
    suspend fun retryPaused(opId: String) {
        db.syncDao().updateOpState(opId, "PENDING", nowEpochMillis())
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

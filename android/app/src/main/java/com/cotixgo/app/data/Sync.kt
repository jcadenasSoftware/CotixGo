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
            val version = payload.intOr("version")
            if (db.quoteVersionDao().getByVersion(quoteId, version) == null) {
                db.quoteVersionDao().insert(
                    QuoteVersionEntity(
                        id = id, quoteId = quoteId, version = version,
                        snapshotJson = payload.str("snapshotJson") ?: "",
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
            applyQuoteMeta(type, id, c)
            return
        }
        val (hasPendingWrites, localSv) = local
        if (openOps > 0 || hasPendingWrites) {
            // Cambio remoto sobre entidad con trabajo local pendiente:
            // preservar ambas copias; la resolución la decide el usuario.
            db.syncDao().insertConflict(
                SyncConflictEntity(
                    entityType = type, entityId = id,
                    remoteJson = payload.toString(), remoteServerVersion = sv,
                    detectedAt = now,
                ),
            )
            return
        }
        if (sv <= localSv) return // cambio ya cubierto por el estado local
        upsertFromPayload(type, id, payload, sv)
        applyQuoteMeta(type, id, c)
    }

    private suspend fun applyQuoteMeta(type: String, id: String, c: JsonObject) {
        if (type == EntityTypes.QUOTE) {
            db.quoteDao().updateSyncFields(
                id, c.str("official_number"),
                c.boolOr("has_version_conflict"), nowEpochMillis(),
            )
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
        else -> null
    }

    private suspend fun upsertFromPayload(type: String, id: String, p: JsonObject, sv: Int) {
        val now = nowEpochMillis()
        when (type) {
            EntityTypes.USER -> db.userDao().insert(
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
                // Regenerar operaciones pendientes con identidad canónica.
                val pending = db.syncDao().pendingOps()
                val now = nowEpochMillis()
                for (op in pending) {
                    val rewritten = op.payloadJson.replace("\"userId\":\"$oldId\"", "\"userId\":\"$userId\"")
                    db.syncDao().insertOp(
                        op.copy(
                            opId = Ids.new(),
                            payloadJson = rewritten,
                            supersedesOpId = op.opId,
                            createdAt = now,
                            updatedAt = now,
                        ),
                    )
                    db.syncDao().updateOpResult(op.opId, "DONE", null, "SUPERSEDED", now)
                }
            } else if (localUser == null) {
                db.userDao().insert(
                    UserEntity(
                        id = userId, name = email.substringBefore('@'), email = email,
                        createdAt = nowEpochMillis(), updatedAt = nowEpochMillis(),
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
        for (t in listOf("clients", "services", "retention_configs", "quotes", "professional_profiles", "users")) {
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

    /** El usuario conserva su copia local: la operación reintenta con la base actual del servidor. */
    suspend fun resolveKeepLocal(conflictId: Long) {
        val conflict = db.syncDao().observeConflicts().first().firstOrNull { it.id == conflictId } ?: return
        val now = nowEpochMillis()
        db.withTransaction {
            pendingOpIdFor(conflict)?.let { opId ->
                db.syncDao().updateOpBase(opId, conflict.remoteServerVersion, now)
                db.syncDao().updateOpState(opId, "PENDING", now)
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
     */
    suspend fun resolveQuoteVersions(quoteId: String, keepVersion: Int): Boolean {
        val quote = db.quoteDao().get(quoteId) ?: return false
        val kept = db.quoteVersionDao().getByVersion(quoteId, keepVersion) ?: return false
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
}

data class SyncSummary(val pushed: Int, val paused: Int, val pulled: Int)

package com.cotixgo.app.data

import androidx.room.withTransaction
import com.cotixgo.app.core.CalcLine
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.QuoteTransitions
import com.cotixgo.app.core.RetentionSpec
import com.cotixgo.app.core.effectiveQuoteStatus
import com.cotixgo.app.core.expiryEpochDay
import com.cotixgo.app.core.newProvisionalNumber
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class AppRepository(private val db: AppDatabase, private val sync: SyncRepository? = null) {

    fun observeUser(): Flow<UserEntity?> = db.userDao().observe()

    fun observeProfiles(userId: String): Flow<List<ProfileEntity>> =
        db.profileDao().observeForUser(userId)

    fun observeClients(userId: String): Flow<List<ClientEntity>> =
        db.clientDao().observeForUser(userId)

    fun observeClient(id: String): Flow<ClientEntity?> = db.clientDao().observeOne(id)

    suspend fun completeOnboarding(userName: String, profileName: String) {
        val now = nowEpochMillis()
        val user = UserEntity(
            id = Ids.new(),
            name = userName.trim(),
            createdAt = now,
            updatedAt = now,
        )
        val profile = ProfileEntity(
            id = Ids.new(),
            userId = user.id,
            businessName = profileName.trim(),
            createdAt = now,
            updatedAt = now,
        )
        // Transacción: si alguna inserción falla no queda un Usuario
        // sin su primer Perfil ni se omite el onboarding al reiniciar.
        // F3: la escritura y sus operaciones de sincronización van juntas.
        db.withTransaction {
            db.userDao().insert(user)
            db.profileDao().insert(profile)
            sync?.enqueue(EntityTypes.USER, user.id, SyncPayloads.user(user), 0)
            sync?.enqueue(EntityTypes.PROFILE, profile.id, SyncPayloads.profile(profile), 0)
        }
    }

    suspend fun saveClient(input: ClientInput, existingId: String? = null) {
        val user = db.userDao().observe().first() ?: return
        val now = nowEpochMillis()
        val existing = existingId?.let { db.clientDao().get(it) }
        val client = ClientEntity(
            id = existing?.id ?: Ids.new(),
            userId = existing?.userId ?: user.id,
            type = input.type.name,
            name = input.name.trim(),
            phone = input.phone.trim(),
            email = input.email.trim(),
            address = input.address.trim(),
            notes = input.notes.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.clientDao().upsert(client)
            sync?.enqueue(EntityTypes.CLIENT, client.id, SyncPayloads.client(client), existing?.serverVersion ?: 0)
        }
    }

    suspend fun setClientArchived(id: String, archived: Boolean) {
        val client = db.clientDao().get(id) ?: return
        val updated = client.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.clientDao().upsert(updated)
            sync?.enqueue(EntityTypes.CLIENT, updated.id, SyncPayloads.client(updated), client.serverVersion)
        }
    }

    // ---------- Usuario / Perfil (F2) ----------

    suspend fun setMainCurrency(code: String) {
        val user = db.userDao().observe().first() ?: return
        val updated = user.copy(mainCurrency = code.uppercase(), updatedAt = nowEpochMillis())
        db.withTransaction {
            db.userDao().insert(updated)
            sync?.enqueue(EntityTypes.USER, updated.id, SyncPayloads.user(updated), user.serverVersion)
        }
    }

    suspend fun updateProfile(id: String, businessName: String, description: String, commercialInfo: String, vigenciaDias: Int?) {
        val profile = db.profileDao().observeForUser(requireUser().id).first()
            .firstOrNull { it.id == id } ?: return
        val updated = profile.copy(
            businessName = businessName.trim(),
            description = description.trim(),
            commercialInfo = commercialInfo.trim(),
            vigenciaDias = vigenciaDias,
            updatedAt = nowEpochMillis(),
        )
        db.withTransaction {
            db.profileDao().insert(updated)
            sync?.enqueue(EntityTypes.PROFILE, updated.id, SyncPayloads.profile(updated), profile.serverVersion)
        }
    }

    // ---------- Catálogo de servicios (por Perfil) ----------

    fun observeServices(profileId: String): Flow<List<ServiceEntity>> =
        db.serviceDao().observeForProfile(profileId)

    suspend fun saveService(input: ServiceInput, existingId: String? = null) {
        val user = requireUser()
        val now = nowEpochMillis()
        val existing = existingId?.let { db.serviceDao().get(it) }
        val service = ServiceEntity(
            id = existing?.id ?: Ids.new(),
            userId = existing?.userId ?: user.id,
            profileId = input.profileId,
            name = input.name.trim(),
            description = input.description.trim(),
            suggestedPrice = input.suggestedPrice.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.serviceDao().upsert(service)
            sync?.enqueue(EntityTypes.SERVICE, service.id, SyncPayloads.service(service), existing?.serverVersion ?: 0)
        }
    }

    suspend fun setServiceArchived(id: String, archived: Boolean) {
        val service = db.serviceDao().get(id) ?: return
        val updated = service.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.serviceDao().upsert(updated)
            sync?.enqueue(EntityTypes.SERVICE, updated.id, SyncPayloads.service(updated), service.serverVersion)
        }
    }

    // ---------- Configuración de retenciones (global Usuario/Titular) ----------

    fun observeRetentions(userId: String): Flow<List<RetentionConfigEntity>> =
        db.retentionDao().observeForUser(userId)

    suspend fun saveRetention(input: RetentionInput, existingId: String? = null) {
        val user = requireUser()
        val now = nowEpochMillis()
        val existing = existingId?.let { db.retentionDao().get(it) }
        val config = RetentionConfigEntity(
            id = existing?.id ?: Ids.new(),
            userId = existing?.userId ?: user.id,
            name = input.name.trim(),
            ratePercent = input.ratePercent.trim(),
            appliesTo = input.appliesTo.sorted().joinToString(",") { it.name },
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.retentionDao().upsert(config)
            sync?.enqueue(EntityTypes.RETENTION, config.id, SyncPayloads.retention(config), existing?.serverVersion ?: 0)
        }
    }

    suspend fun setRetentionArchived(id: String, archived: Boolean) {
        val config = db.retentionDao().get(id) ?: return
        val updated = config.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.retentionDao().upsert(updated)
            sync?.enqueue(EntityTypes.RETENTION, updated.id, SyncPayloads.retention(updated), config.serverVersion)
        }
    }

    // ---------- Cotizaciones ----------

    fun observeQuotes(userId: String): Flow<List<QuoteEntity>> =
        db.quoteDao().observeForUser(userId)

    fun observeVersionConflictQuotes(userId: String): Flow<List<QuoteEntity>> =
        db.quoteDao().observeVersionConflicts(userId)

    fun observeQuote(id: String): Flow<QuoteEntity?> = db.quoteDao().observeOne(id)

    fun observeQuoteVersions(quoteId: String): Flow<List<QuoteVersionEntity>> =
        db.quoteVersionDao().observeForQuote(quoteId)

    suspend fun getQuoteSnapshot(quoteId: String): QuoteSnapshot? =
        db.quoteVersionDao().getLatest(quoteId)?.let { SnapshotJson.decodeQuote(it.snapshotJson) }

    /** Número provisional único entre las Cotizaciones del usuario. */
    private suspend fun uniqueProvisionalNumber(): String {
        var candidate = newProvisionalNumber("COT")
        while (db.quoteDao().provisionalNumberExists(candidate)) {
            candidate = newProvisionalNumber("COT")
        }
        return candidate
    }

    /**
     * Crea o edita una Cotización. Cada guardado inserta una versión interna
     * append-only con el snapshot completo (cabecera + Cliente + Perfil +
     * líneas + retenciones aplicadas + totales). La edición no cambia el
     * estado; solo BORRADOR y EMITIDA se pueden editar antes del vencimiento.
     */
    suspend fun saveQuote(input: QuoteInput, existingId: String? = null): String? {
        val user = requireUser()
        val client = db.clientDao().get(input.clientId) ?: return null
        val profile = db.profileDao().observeForUser(user.id).first()
            .firstOrNull { it.id == input.profileId } ?: return null

        val now = nowEpochMillis()
        val today = LocalDate.now().toEpochDay()
        val existing = existingId?.let { db.quoteDao().get(it) }

        val expiry = expiryEpochDay(input.issueDateEpochDay, input.validityDays)
        val lines = input.lines.map {
            CalcLine(it.conceptType, it.quantity.toQty(), it.unitPrice.toMoney())
        }
        val specs = input.retentions.map {
            RetentionSpec(it.name, it.ratePercent.toQty(), it.appliesTo)
        }
        val calc = QuoteCalc.compute(lines, specs)

        val snapshot = QuoteSnapshot(
            version = (existing?.currentVersion ?: 0) + 1,
            provisionalNumber = existing?.provisionalNumber ?: uniqueProvisionalNumber(),
            client = ClientSnapshot(client.id, client.name, client.type, client.phone, client.email, client.address, client.notes),
            profile = ProfileSnapshot(profile.id, profile.businessName, profile.description, profile.commercialInfo, profile.logoRef),
            issueDate = LocalDate.ofEpochDay(input.issueDateEpochDay).toString(),
            validityDays = input.validityDays,
            expiryDate = LocalDate.ofEpochDay(expiry).toString(),
            currency = input.currency.uppercase(),
            notes = input.notes.trim(),
            lines = input.lines.mapIndexed { i, l ->
                QuoteLineSnapshot(
                    conceptType = l.conceptType.name,
                    description = l.description.trim(),
                    quantity = l.quantity.trim(),
                    unit = l.unit.trim(),
                    unitPrice = QuoteCalc.format(l.unitPrice.toMoney()),
                    lineTotal = QuoteCalc.format(calc.lineTotals[i]),
                    serviceRef = l.serviceRef,
                    materialOrigin = l.materialOrigin?.name,
                )
            },
            retentions = calc.retentions.map {
                AppliedRetentionSnapshot(
                    name = it.name,
                    ratePercent = it.ratePercent.stripTrailingZeros().toPlainString(),
                    appliesTo = it.appliesTo.sorted().map(ConceptType::name),
                    base = QuoteCalc.format(it.base),
                    amount = QuoteCalc.format(it.amount),
                )
            },
            gross = QuoteCalc.format(calc.gross),
            totalRetained = QuoteCalc.format(calc.totalRetained),
            net = QuoteCalc.format(calc.net),
            savedAtEpochMs = now,
        )

        // La editabilidad se evalúa con la fecha de vencimiento GUARDADA,
        // antes de aplicar cualquier dato nuevo del formulario: cambiar la
        // vigencia o la fecha no puede reactivar una Cotización vencida.
        val effective = existing?.let {
            effectiveQuoteStatus(QuoteStatus.valueOf(it.status), it.expiryDateEpochDay, today)
        } ?: QuoteStatus.BORRADOR
        if (existing != null && !QuoteTransitions.isEditable(effective)) {
            if (effective.name != existing.status) {
                // Materializa el vencimiento derivado y lo encola para el
                // servidor: la regla aprobada vence desde cualquier estado
                // no terminal (BORRADOR/EMITIDA/RECHAZADA); APROBADA y
                // CANCELADA son terminales y nunca llegan aquí.
                val vencida = existing.copy(status = effective.name, updatedAt = now,
                    pendingSync = true)
                db.withTransaction {
                    db.quoteDao().upsert(vencida)
                    sync?.enqueue(EntityTypes.QUOTE, vencida.id,
                        SyncPayloads.quote(vencida), existing.serverVersion)
                }
            }
            return null
        }
        // Con la edición permitida, el estado persistido se re-deriva con el
        // vencimiento resultante (una vigencia más corta puede vencerla).
        val status = existing?.let {
            effectiveQuoteStatus(QuoteStatus.valueOf(it.status), expiry, today)
        } ?: QuoteStatus.BORRADOR

        val quoteId = existing?.id ?: Ids.new()
        val quoteEntity = QuoteEntity(
            id = quoteId,
            userId = existing?.userId ?: user.id,
            clientId = input.clientId,
            profileId = input.profileId,
            provisionalNumber = snapshot.provisionalNumber,
            status = status.name,
            issueDateEpochDay = input.issueDateEpochDay,
            validityDays = input.validityDays,
            expiryDateEpochDay = expiry,
            currency = snapshot.currency,
            notes = snapshot.notes,
            currentVersion = snapshot.version,
            emittedVersion = if (status == QuoteStatus.EMITIDA) snapshot.version
                else existing?.emittedVersion ?: 0,
            gross = snapshot.gross,
            totalRetained = snapshot.totalRetained,
            net = snapshot.net,
            clientNameSnapshot = client.name,
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            versionConflict = existing?.versionConflict ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        val versionEntity = QuoteVersionEntity(
            id = Ids.new(),
            quoteId = quoteId,
            version = snapshot.version,
            snapshotJson = SnapshotJson.encode(snapshot),
            createdAt = now,
        )
        db.withTransaction {
            db.quoteDao().upsert(quoteEntity)
            db.quoteVersionDao().insert(versionEntity)
            sync?.enqueue(EntityTypes.QUOTE, quoteId, SyncPayloads.quote(quoteEntity), existing?.serverVersion ?: 0)
            sync?.enqueue(EntityTypes.QUOTE_VERSION, versionEntity.id, SyncPayloads.quoteVersion(versionEntity), 0)
        }
        return quoteId
    }

    /**
     * Cambio de estado validado por el sistema. Primero materializa un
     * vencimiento derivado (VENCIDA) si corresponde; luego aplica la
     * transición solo si está permitida desde el estado efectivo.
     */
    suspend fun setQuoteStatus(id: String, to: QuoteStatus): Boolean {
        var quote = db.quoteDao().get(id) ?: return false
        val today = LocalDate.now().toEpochDay()
        val effective = effectiveQuoteStatus(
            QuoteStatus.valueOf(quote.status), quote.expiryDateEpochDay, today,
        )
        if (effective.name != quote.status) {
            // Materializa el vencimiento derivado en la siguiente escritura.
            // El servidor acepta →VENCIDA desde cualquier estado no
            // terminal (regla aprobada): la operación sincroniza el
            // vencimiento igual que una transición manual.
            quote = quote.copy(status = effective.name, updatedAt = nowEpochMillis(),
                pendingSync = true)
            db.withTransaction {
                db.quoteDao().upsert(quote)
                sync?.enqueue(EntityTypes.QUOTE, quote.id,
                    SyncPayloads.quote(quote), quote.serverVersion)
            }
        }
        if (!QuoteTransitions.canTransition(effective, to)) return false
        val updated = quote.copy(
            status = to.name,
            emittedVersion = if (to == QuoteStatus.EMITIDA) quote.currentVersion else quote.emittedVersion,
            updatedAt = nowEpochMillis(),
        )
        db.withTransaction {
            db.quoteDao().upsert(updated)
            sync?.enqueue(EntityTypes.QUOTE, updated.id, SyncPayloads.quote(updated), quote.serverVersion)
        }
        return true
    }

    /** Emitir exige al menos una línea (DP1-F2). */
    suspend fun emitQuote(id: String): Boolean {
        val latest = db.quoteVersionDao().getLatest(id) ?: return false
        val snapshot = SnapshotJson.decodeQuote(latest.snapshotJson)
        if (snapshot.lines.isEmpty()) return false
        return setQuoteStatus(id, QuoteStatus.EMITIDA)
    }

    suspend fun setQuoteArchived(id: String, archived: Boolean) {
        val quote = db.quoteDao().get(id) ?: return
        val updated = quote.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.quoteDao().upsert(updated)
            sync?.enqueue(EntityTypes.QUOTE, updated.id, SyncPayloads.quote(updated), quote.serverVersion)
        }
    }

    /**
     * Revalidación: crea una Cotización nueva (nuevo ID, fecha, vigencia,
     * número provisional) copiando el contenido de la versión vigente de la
     * vencida. La cotización anterior permanece intacta.
     */
    suspend fun revalidateQuote(id: String, validityDays: Int): String? {
        // Solo una Cotización efectivamente VENCIDA puede revalidarse
        // (estado efectivo con el vencimiento persistido y la fecha local).
        val source = db.quoteDao().get(id) ?: return null
        val today = LocalDate.now().toEpochDay()
        val effective = effectiveQuoteStatus(
            QuoteStatus.valueOf(source.status), source.expiryDateEpochDay, today,
        )
        if (effective != QuoteStatus.VENCIDA) return null
        if (effective.name != source.status) {
            val vencida = source.copy(status = effective.name, updatedAt = nowEpochMillis(),
                pendingSync = true)
            db.withTransaction {
                db.quoteDao().upsert(vencida)
                sync?.enqueue(EntityTypes.QUOTE, vencida.id,
                    SyncPayloads.quote(vencida), source.serverVersion)
            }
        }
        val latest = db.quoteVersionDao().getLatest(id) ?: return null
        val old = SnapshotJson.decodeQuote(latest.snapshotJson)
        val input = QuoteInput(
            clientId = old.client.id,
            profileId = old.profile.id,
            issueDateEpochDay = LocalDate.now().toEpochDay(),
            validityDays = validityDays,
            currency = old.currency,
            notes = old.notes,
            lines = old.lines.map {
                QuoteLineInput(
                    conceptType = ConceptType.valueOf(it.conceptType),
                    description = it.description,
                    quantity = it.quantity,
                    unit = it.unit,
                    unitPrice = it.unitPrice,
                    serviceRef = it.serviceRef,
                    materialOrigin = it.materialOrigin?.let(com.cotixgo.app.core.MaterialOrigin::valueOf),
                )
            },
            retentions = old.retentions.map {
                RetentionInput(
                    name = it.name,
                    ratePercent = it.ratePercent,
                    appliesTo = it.appliesTo.map(ConceptType::valueOf).toSet(),
                )
            },
        )
        return saveQuote(input)
    }

    private suspend fun requireUser(): UserEntity =
        db.userDao().observe().first() ?: error("No hay usuario local registrado.")
}

// ---------- Entradas de dominio para F2 ----------

data class ServiceInput(
    val profileId: String,
    val name: String,
    val description: String = "",
    val suggestedPrice: String = "",
)

data class RetentionInput(
    val name: String,
    val ratePercent: String,
    val appliesTo: Set<ConceptType> = setOf(ConceptType.SERVICIO),
)

data class QuoteLineInput(
    val conceptType: ConceptType,
    val description: String,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val serviceRef: String? = null,
    val materialOrigin: com.cotixgo.app.core.MaterialOrigin? = null,
)

data class QuoteInput(
    val clientId: String,
    val profileId: String,
    val issueDateEpochDay: Long,
    val validityDays: Int,
    val currency: String,
    val notes: String = "",
    val lines: List<QuoteLineInput>,
    val retentions: List<RetentionInput>,
)

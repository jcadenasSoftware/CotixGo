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

class AppRepository(private val db: AppDatabase) {

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
        db.withTransaction {
            db.userDao().insert(user)
            db.profileDao().insert(profile)
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
        )
        db.clientDao().upsert(client)
    }

    suspend fun setClientArchived(id: String, archived: Boolean) {
        val client = db.clientDao().get(id) ?: return
        db.clientDao().upsert(
            client.copy(archived = archived, updatedAt = nowEpochMillis()),
        )
    }

    // ---------- Usuario / Perfil (F2) ----------

    suspend fun setMainCurrency(code: String) {
        val user = db.userDao().observe().first() ?: return
        db.userDao().insert(user.copy(mainCurrency = code.uppercase(), updatedAt = nowEpochMillis()))
    }

    suspend fun updateProfile(id: String, businessName: String, description: String, commercialInfo: String, vigenciaDias: Int?) {
        val profile = db.profileDao().observeForUser(requireUser().id).first()
            .firstOrNull { it.id == id } ?: return
        db.profileDao().insert(
            profile.copy(
                businessName = businessName.trim(),
                description = description.trim(),
                commercialInfo = commercialInfo.trim(),
                vigenciaDias = vigenciaDias,
                updatedAt = nowEpochMillis(),
            ),
        )
    }

    // ---------- Catálogo de servicios (por Perfil) ----------

    fun observeServices(profileId: String): Flow<List<ServiceEntity>> =
        db.serviceDao().observeForProfile(profileId)

    suspend fun saveService(input: ServiceInput, existingId: String? = null) {
        val user = requireUser()
        val now = nowEpochMillis()
        val existing = existingId?.let { db.serviceDao().get(it) }
        db.serviceDao().upsert(
            ServiceEntity(
                id = existing?.id ?: Ids.new(),
                userId = existing?.userId ?: user.id,
                profileId = input.profileId,
                name = input.name.trim(),
                description = input.description.trim(),
                suggestedPrice = input.suggestedPrice.trim(),
                archived = existing?.archived ?: false,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun setServiceArchived(id: String, archived: Boolean) {
        val service = db.serviceDao().get(id) ?: return
        db.serviceDao().upsert(service.copy(archived = archived, updatedAt = nowEpochMillis()))
    }

    // ---------- Configuración de retenciones (global Usuario/Titular) ----------

    fun observeRetentions(userId: String): Flow<List<RetentionConfigEntity>> =
        db.retentionDao().observeForUser(userId)

    suspend fun saveRetention(input: RetentionInput, existingId: String? = null) {
        val user = requireUser()
        val now = nowEpochMillis()
        val existing = existingId?.let { db.retentionDao().get(it) }
        db.retentionDao().upsert(
            RetentionConfigEntity(
                id = existing?.id ?: Ids.new(),
                userId = existing?.userId ?: user.id,
                name = input.name.trim(),
                ratePercent = input.ratePercent.trim(),
                appliesTo = input.appliesTo.sorted().joinToString(",") { it.name },
                archived = existing?.archived ?: false,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            ),
        )
    }

    suspend fun setRetentionArchived(id: String, archived: Boolean) {
        val config = db.retentionDao().get(id) ?: return
        db.retentionDao().upsert(config.copy(archived = archived, updatedAt = nowEpochMillis()))
    }

    // ---------- Cotizaciones ----------

    fun observeQuotes(userId: String): Flow<List<QuoteEntity>> =
        db.quoteDao().observeForUser(userId)

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

        val status = existing?.let {
            effectiveQuoteStatus(QuoteStatus.valueOf(it.status), expiry, today)
        } ?: QuoteStatus.BORRADOR
        // Solo BORRADOR y EMITIDA se pueden editar antes del vencimiento.
        if (existing != null && !QuoteTransitions.isEditable(status)) return null

        val quoteId = existing?.id ?: Ids.new()
        db.withTransaction {
            db.quoteDao().upsert(
                QuoteEntity(
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
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                ),
            )
            db.quoteVersionDao().insert(
                QuoteVersionEntity(
                    id = Ids.new(),
                    quoteId = quoteId,
                    version = snapshot.version,
                    snapshotJson = SnapshotJson.encode(snapshot),
                    createdAt = now,
                ),
            )
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
            quote = quote.copy(status = effective.name, updatedAt = nowEpochMillis())
            db.quoteDao().upsert(quote)
        }
        if (!QuoteTransitions.canTransition(effective, to)) return false
        db.quoteDao().upsert(
            quote.copy(
                status = to.name,
                emittedVersion = if (to == QuoteStatus.EMITIDA) quote.currentVersion else quote.emittedVersion,
                updatedAt = nowEpochMillis(),
            ),
        )
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
        db.quoteDao().upsert(quote.copy(archived = archived, updatedAt = nowEpochMillis()))
    }

    /**
     * Revalidación: crea una Cotización nueva (nuevo ID, fecha, vigencia,
     * número provisional) copiando el contenido de la versión vigente de la
     * vencida. La cotización anterior permanece intacta.
     */
    suspend fun revalidateQuote(id: String, validityDays: Int): String? {
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

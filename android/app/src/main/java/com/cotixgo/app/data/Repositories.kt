package com.cotixgo.app.data

import androidx.room.withTransaction
import com.cotixgo.app.core.CalcLine
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.isValidCurrencyCode
import com.cotixgo.app.core.CollectionAccountTransitions
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.ConsolidatedTransitions
import com.cotixgo.app.core.MaterialCondition
import com.cotixgo.app.core.MovementType
import com.cotixgo.app.core.MovementOrigin
import com.cotixgo.app.core.PurchaseStatus
import com.cotixgo.app.core.PurchaseTransitions
import com.cotixgo.app.core.PaymentAdjustmentKind
import com.cotixgo.app.core.PaymentAssignmentAction
import com.cotixgo.app.core.PaymentAssignmentStatus
import com.cotixgo.app.core.PaymentTargetKind
import com.cotixgo.app.core.QuoteCalc
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.core.QuoteTransitions
import com.cotixgo.app.core.ReportStatus
import com.cotixgo.app.core.ReportTransitions
import com.cotixgo.app.core.RetentionSpec
import com.cotixgo.app.core.deriveAssignmentStatus
import com.cotixgo.app.core.deriveCollectionStatus
import com.cotixgo.app.core.effectiveQuoteStatus
import com.cotixgo.app.core.expiryEpochDay
import com.cotixgo.app.core.isPositiveQuantity
import com.cotixgo.app.core.newProvisionalNumber
import com.cotixgo.app.core.normDecimal
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import com.cotixgo.app.core.validateCollectionMethod
import com.cotixgo.app.core.validateMaterial
import com.cotixgo.app.net.str
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.math.BigDecimal
import java.time.LocalDate

class AppRepository(private val db: AppDatabase, private val sync: SyncRepository? = null) {

    fun observeUser(): Flow<UserEntity?> = db.userDao().observe()

    fun observeProfiles(userId: String): Flow<List<ProfileEntity>> =
        db.profileDao().observeForUser(userId)

    fun observeClients(userId: String): Flow<List<ClientEntity>> =
        db.clientDao().observeForUser(userId)

    fun observeClient(id: String): Flow<ClientEntity?> = db.clientDao().observeOne(id)

    suspend fun completeOnboarding(userName: String, profileName: String, logoRef: String? = null) {
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
            logoRef = logoRef,
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
            docType = input.docType.trim(),
            docNumber = input.docNumber.trim(),
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
        // ISO 4217 real (tabla del JDK), no solo 3 letras: ninguna ruta puede
        // persistir un código inexistente; los valores históricos no se tocan.
        require(isValidCurrencyCode(code)) { "Código de moneda no válido." }
        val user = db.userDao().observe().first() ?: return
        val updated = user.copy(mainCurrency = code.uppercase(), updatedAt = nowEpochMillis())
        db.withTransaction {
            db.userDao().insert(updated)
            sync?.enqueue(EntityTypes.USER, updated.id, SyncPayloads.user(updated), user.serverVersion)
        }
    }

    suspend fun updateProfile(id: String, businessName: String, description: String, commercialInfo: String, vigenciaDias: Int?) {
        // null = sin default válido; con valor debe ser >= 1 (un 0 rechazaría
        // después el editor de cotización). Ninguna ruta persiste < 1.
        require(vigenciaDias == null || vigenciaDias >= 1) {
            "La vigencia predeterminada debe ser de al menos 1 día."
        }
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

    /**
     * Cambia/quita el logo del perfil (logoRef = archivo inmutable en
     * filesDir/logos). Los snapshots emitidos conservan la referencia que
     * tenían al crearse — quitar el logo solo afecta documentos nuevos y la
     * imagen anterior nunca se borra aquí.
     */
    suspend fun setProfileLogo(id: String, logoRef: String?) {
        val profile = db.profileDao().observeForUser(requireUser().id).first()
            .firstOrNull { it.id == id } ?: return
        val updated = profile.copy(logoRef = logoRef, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.profileDao().insert(updated)
            sync?.enqueue(EntityTypes.PROFILE, updated.id, SyncPayloads.profile(updated), profile.serverVersion)
        }
    }

    /** Descarga bajo demanda de un logo referenciado por un snapshot. */
    suspend fun fetchLogo(ref: String): Boolean = sync?.fetchLogoBinary(ref) ?: false

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

    /** Origen del editor: si el snapshot vino de una versión persistida o se
     *  reconstruyó desde la entidad (sin líneas/retenciones recuperables). */
    data class QuoteEditSource(val snapshot: QuoteSnapshot, val fromVersion: Boolean)

    /**
     * Snapshot para el editor: la última versión si existe. Si la Cotización
     * no tiene versiones (p. ej. datos sembrados), reconstruye el formulario
     * desde la fila actual de la entidad — sin fabricar una versión
     * histórica. Devuelve null si faltan datos para editar (cotización,
     * cliente o perfil inexistentes).
     */
    suspend fun getQuoteSnapshotForEdit(quoteId: String): QuoteEditSource? {
        db.quoteVersionDao().getLatest(quoteId)
            ?.let { return QuoteEditSource(SnapshotJson.decodeQuote(it.snapshotJson), fromVersion = true) }
        val q = db.quoteDao().get(quoteId) ?: return null
        val client = db.clientDao().get(q.clientId) ?: return null
        val profile = db.profileDao().get(q.profileId) ?: return null
        return QuoteEditSource(
            QuoteSnapshot(
                version = q.currentVersion,
                provisionalNumber = q.provisionalNumber,
                title = q.title,
                client = ClientSnapshot(
                    client.id, client.name, client.type,
                    client.phone, client.email, client.address, client.notes,
                ),
                profile = ProfileSnapshot(
                    profile.id, profile.businessName, profile.description,
                    profile.commercialInfo, profile.logoRef,
                ),
                issueDate = LocalDate.ofEpochDay(q.issueDateEpochDay).toString(),
                validityDays = q.validityDays,
                expiryDate = LocalDate.ofEpochDay(q.expiryDateEpochDay).toString(),
                currency = q.currency,
                notes = q.notes,
                lines = emptyList(),
                retentions = emptyList(),
                gross = q.gross,
                totalRetained = q.totalRetained,
                net = q.net,
                savedAtEpochMs = q.updatedAt,
            ),
            fromVersion = false,
        )
    }

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
            title = input.title.trim(),
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
            title = snapshot.title,
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
            title = old.title,
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

    // ---------- Trabajos (F4) ----------

    fun observeJobs(userId: String): Flow<List<JobEntity>> = db.jobDao().observeForUser(userId)

    fun observeJobsForClient(clientId: String): Flow<List<JobEntity>> =
        db.jobDao().observeForClient(clientId)

    fun observeJob(id: String): Flow<JobEntity?> = db.jobDao().observeOne(id)

    fun observeJobQuotes(jobId: String): Flow<List<JobQuoteEntity>> =
        db.jobQuoteDao().observeForJob(jobId)

    fun observeJobActivities(jobId: String): Flow<List<JobActivityEntity>> =
        db.jobActivityDao().observeForJob(jobId)

    fun observeJobMaterials(jobId: String): Flow<List<JobMaterialEntity>> =
        db.jobMaterialDao().observeForJob(jobId)

    suspend fun getJobQuotes(jobId: String): List<JobQuoteEntity> = db.jobQuoteDao().forJob(jobId)

    /** Número provisional único entre los Trabajos del usuario. */
    private suspend fun uniqueJobProvisionalNumber(): String {
        var candidate = newProvisionalNumber("TR")
        while (db.jobDao().provisionalNumberExists(candidate)) {
            candidate = newProvisionalNumber("TR")
        }
        return candidate
    }

    /**
     * Crea o edita un Trabajo. Requiere Cliente y Perfil; la edición no
     * cambia el estado y queda bloqueada en ENTREGADO/CANCELADO (terminales).
     * `quoteIds` solo se aplica al crear: cada vínculo queda como ORIGEN;
     * las asociaciones posteriores usan [linkQuoteToJob] con ADICIONAL.
     */
    suspend fun saveJob(input: JobInput, existingId: String? = null): String? {
        val user = requireUser()
        val client = db.clientDao().get(input.clientId) ?: return null
        val profile = db.profileDao().get(input.profileId) ?: return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.jobDao().get(it) }
        if (existing != null && !JobTransitions.isEditable(JobStatus.valueOf(existing.status))) {
            return null // terminal: sin edición
        }
        // El Cliente ancla los vínculos job_quote (regla: cotizaciones
        // APROBADA del mismo Cliente). Recambiarlo con vínculos existentes
        // los dejaría inválidos — bloqueado en el repositorio, no solo en la UI.
        if (existing != null && input.clientId != existing.clientId
            && db.jobQuoteDao().forJob(existing.id).isNotEmpty()
        ) {
            return null
        }
        val jobId = existing?.id ?: Ids.new()
        val job = JobEntity(
            id = jobId,
            userId = existing?.userId ?: user.id,
            clientId = client.id,
            profileId = profile.id,
            provisionalNumber = existing?.provisionalNumber ?: uniqueJobProvisionalNumber(),
            status = existing?.status ?: JobStatus.BORRADOR.name,
            startDateEpochDay = input.startDateEpochDay,
            estimatedDateEpochDay = input.estimatedDateEpochDay,
            deliveryDateEpochDay = existing?.deliveryDateEpochDay,
            executionAddress = input.executionAddress.trim(),
            notes = input.notes.trim(),
            clientNameSnapshot = client.name,
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.jobDao().upsert(job)
            sync?.enqueue(EntityTypes.JOB, jobId, SyncPayloads.job(job), existing?.serverVersion ?: 0)
            if (existing == null) {
                for (quoteId in input.quoteIds) {
                    val quote = db.quoteDao().get(quoteId) ?: continue
                    if (quote.clientId != client.id || quote.status != QuoteStatus.APROBADA.name) continue
                    val link = JobQuoteEntity(
                        id = Ids.new(), jobId = jobId, quoteId = quoteId,
                        relationType = "ORIGEN", createdAt = now,
                    )
                    db.jobQuoteDao().upsert(link)
                    sync?.enqueue(EntityTypes.JOB_QUOTE, link.id, SyncPayloads.jobQuote(link), 0)
                }
            }
        }
        return jobId
    }

    /**
     * Cambio de estado validado por la matriz aprobada: dirección obligatoria
     * al PROGRAMADO; ENTREGADO registra `deliveryDateEpochDay` si falta.
     * Devuelve null si aplicó, o el motivo del rechazo.
     */
    suspend fun setJobStatus(id: String, to: JobStatus): String? {
        val job = db.jobDao().get(id) ?: return "Trabajo no encontrado."
        val from = JobStatus.valueOf(job.status)
        val error = JobTransitions.canTransitionWith(from, to, job.executionAddress)
        if (error != null) return error
        val now = nowEpochMillis()
        val updated = job.copy(
            status = to.name,
            deliveryDateEpochDay = if (to == JobStatus.ENTREGADO) {
                job.deliveryDateEpochDay ?: LocalDate.now().toEpochDay()
            } else job.deliveryDateEpochDay,
            updatedAt = now,
        )
        db.withTransaction {
            db.jobDao().upsert(updated)
            sync?.enqueue(EntityTypes.JOB, updated.id, SyncPayloads.job(updated), job.serverVersion)
        }
        return null
    }

    suspend fun setJobArchived(id: String, archived: Boolean) {
        val job = db.jobDao().get(id) ?: return
        val updated = job.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.jobDao().upsert(updated)
            sync?.enqueue(EntityTypes.JOB, updated.id, SyncPayloads.job(updated), job.serverVersion)
        }
    }

    /**
     * Asocia una Cotización al Trabajo como ADICIONAL: solo APROBADA del
     * mismo Cliente (regla aprobada; nunca automática).
     */
    suspend fun linkQuoteToJob(jobId: String, quoteId: String): Boolean {
        val job = db.jobDao().get(jobId) ?: return false
        if (!JobTransitions.isEditable(JobStatus.valueOf(job.status))) return false
        val quote = db.quoteDao().get(quoteId) ?: return false
        if (quote.clientId != job.clientId || quote.status != QuoteStatus.APROBADA.name) return false
        if (db.jobQuoteDao().forJob(jobId).any { it.quoteId == quoteId }) return false
        val link = JobQuoteEntity(
            id = Ids.new(), jobId = jobId, quoteId = quoteId,
            relationType = "ADICIONAL", createdAt = nowEpochMillis(),
        )
        db.withTransaction {
            db.jobQuoteDao().upsert(link)
            sync?.enqueue(EntityTypes.JOB_QUOTE, link.id, SyncPayloads.jobQuote(link), 0)
        }
        return true
    }

    /**
     * Crea un Trabajo desde una Cotización APROBADA (acción manual):
     * precarga Cliente y Perfil de la Cotización y vincula como ORIGEN.
     */
    suspend fun createJobFromQuote(quoteId: String, executionAddress: String = ""): String? {
        val quote = db.quoteDao().get(quoteId) ?: return null
        if (quote.status != QuoteStatus.APROBADA.name) return null
        return saveJob(
            JobInput(
                clientId = quote.clientId, profileId = quote.profileId,
                startDateEpochDay = LocalDate.now().toEpochDay(),
                executionAddress = executionAddress,
                quoteIds = listOf(quoteId),
            ),
        )
    }

    // --- Actividades (subordinadas; bloqueadas si el Trabajo es terminal) ---

    private suspend fun jobEditable(jobId: String): Boolean =
        db.jobDao().get(jobId)?.let { JobTransitions.isEditable(JobStatus.valueOf(it.status)) } ?: false

    // --- Fotos de evidencia (metadatos en Room; binario en filesDir) ---

    fun observeJobPhotos(jobId: String): Flow<List<JobPhotoEntity>> =
        db.jobPhotoDao().observeForJob(jobId)

    /**
     * Registra una foto capturada: importa el temporal de cámara al
     * almacenamiento privado (redimensionado a máx. 1600 px JPEG) y encola su
     * metadato para sync. Captura solo con el Trabajo EN PROCESO.
     */
    suspend fun saveJobPhoto(context: android.content.Context, jobId: String, src: java.io.File): String? {
        val job = db.jobDao().get(jobId) ?: return null
        if (JobStatus.valueOf(job.status) != JobStatus.EN_PROCESO || job.archived) return null
        val id = Ids.new()
        val fileName = PhotoStore.import(context, jobId, src, id) ?: return null
        val now = nowEpochMillis()
        val photo = JobPhotoEntity(
            id = id, jobId = jobId, fileName = fileName,
            fileReady = true, fileSynced = false, deleted = false,
            createdAt = now, updatedAt = now,
            pendingSync = true, serverVersion = 0,
        )
        db.withTransaction {
            db.jobPhotoDao().insert(photo)
            // Entidad NUEVA: la base es 0 (la versión del Trabajo padre solo
            // ordena dependencias; usarla como base rompe la creación con
            // STALE_BASE_VERSION cuando el Trabajo ya está sincronizado).
            sync?.enqueue(
                EntityTypes.JOB_PHOTO, photo.id,
                SyncPayloads.jobPhoto(photo), photo.serverVersion,
            )
        }
        src.delete() // limpiar el temporal de cámara tras importar
        return id
    }

    /**
     * Tombstone local + op de sync: la foto desaparece de la UI ya y su
     * borrado se propaga al servidor y a los demás dispositivos en el
     * próximo ciclo (sin conexión queda pendiente, no reaparece al pullear).
     */
    suspend fun deleteJobPhoto(context: android.content.Context, photoId: String) {
        val p = db.jobPhotoDao().get(photoId) ?: return
        val now = nowEpochMillis()
        db.withTransaction {
            db.jobPhotoDao().markDeleted(photoId, now)
            sync?.enqueue(
                EntityTypes.JOB_PHOTO, photoId,
                SyncPayloads.jobPhoto(p.copy(deleted = true)), p.serverVersion,
            )
        }
        PhotoStore.delete(context, p)
    }

    /**
     * Reintento explícito de una descarga marcada "no disponible" (404): el
     * metadato y el resto de datos se conservan; el binario vuelve a la cola
     * y se intenta en el próximo ciclo de sync.
     */
    suspend fun retryJobPhotoDownload(photoId: String) {
        db.jobPhotoDao().clearFileUnavailable(photoId, nowEpochMillis())
    }

    /**
     * Copia local dañada: la devuelve a la cola de descargas sin borrar el
     * archivo (la descarga válida lo sobrescribe) ni tocar metadatos ni
     * fileSynced. Si el servidor no tiene copia, el 404 la marca como no
     * disponible — recuperación siempre segura.
     */
    suspend fun repairJobPhotoDownload(photoId: String) {
        db.jobPhotoDao().resetFileForRedownload(photoId, nowEpochMillis())
    }

    suspend fun saveJobActivity(input: JobActivityInput, existingId: String? = null): String? {
        if (!jobEditable(input.jobId)) return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.jobActivityDao().get(it) }
        val siblings = db.jobActivityDao().forJob(input.jobId)
        val activity = JobActivityEntity(
            id = existing?.id ?: Ids.new(),
            jobId = input.jobId,
            description = input.description.trim(),
            status = input.status.name,
            position = existing?.position ?: (siblings.maxOfOrNull { it.position } ?: 0) + 1,
            startedAt = existing?.startedAt,
            finishedAt = existing?.finishedAt,
            notes = input.notes.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.jobActivityDao().upsert(activity)
            sync?.enqueue(EntityTypes.JOB_ACTIVITY, activity.id,
                SyncPayloads.jobActivity(activity), existing?.serverVersion ?: 0)
        }
        return activity.id
    }

    /** Marca la hora de inicio al entrar en EN_PROCESO y de fin al COMPLETADA. */
    suspend fun setJobActivityStatus(id: String, to: JobActivityStatus): Boolean {
        val activity = db.jobActivityDao().get(id) ?: return false
        if (!jobEditable(activity.jobId)) return false
        val now = nowEpochMillis()
        val updated = activity.copy(
            status = to.name,
            startedAt = if (to == JobActivityStatus.EN_PROCESO) activity.startedAt ?: now else activity.startedAt,
            finishedAt = if (to == JobActivityStatus.COMPLETADA) activity.finishedAt ?: now else activity.finishedAt,
            updatedAt = now,
        )
        db.withTransaction {
            db.jobActivityDao().upsert(updated)
            sync?.enqueue(EntityTypes.JOB_ACTIVITY, updated.id,
                SyncPayloads.jobActivity(updated), activity.serverVersion)
        }
        return true
    }

    // --- Materiales descriptivos (sin catálogo ni inventario) ---
    // F8: un registro NUEVO puede vincularse opcionalmente a un Material
    // inventariado del titular (origin PROFESIONAL, cantidad positiva y
    // condición de stock). El vínculo genera en la misma transacción el
    // movimiento de consumo (JOB_CONSUMPTION/TRABAJO, append-only) y sus
    // campos quedan inmutables — una corrección es un ajuste manual.

    suspend fun saveJobMaterial(input: JobMaterialInput, existingId: String? = null): String? {
        if (!jobEditable(input.jobId)) return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.jobMaterialDao().get(it) }
        val linkId = input.materialId?.trim()?.ifEmpty { null }
        if (existing?.materialId != null) {
            // El vínculo es append-only: los campos del consumo no cambian.
            if (linkId != existing.materialId
                || input.quantity.trim() != existing.quantity
                || input.unit.trim() != existing.unit
                || input.condition?.name != existing.condition
            ) {
                return null
            }
        } else if (existing != null && linkId != null) {
            return null // un registro histórico no puede vincularse al editarse
        }
        var material: MaterialEntity? = null
        if (linkId != null) {
            if (input.origin != com.cotixgo.app.core.MaterialOrigin.PROFESIONAL
                || input.condition == null || !isPositiveQuantity(input.quantity)
            ) {
                return null
            }
            material = db.materialDao().get(linkId)?.takeIf { !it.archived } ?: return null
        }
        val jobMaterial = JobMaterialEntity(
            id = existing?.id ?: Ids.new(),
            jobId = input.jobId,
            description = input.description.trim(),
            origin = input.origin.name,
            quantity = input.quantity.trim(),
            unit = input.unit.trim(),
            notes = input.notes.trim(),
            materialId = existing?.materialId ?: linkId,
            condition = existing?.condition ?: input.condition?.name,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.jobMaterialDao().upsert(jobMaterial)
            sync?.enqueue(EntityTypes.JOB_MATERIAL, jobMaterial.id,
                SyncPayloads.jobMaterial(jobMaterial), existing?.serverVersion ?: 0)
            if (linkId != null && existing == null) {
                // Consumo explícito: salida de inventario append-only.
                val user = db.userDao().get()
                val mov = InventoryMovementEntity(
                    id = Ids.new(), userId = user?.id ?: "",
                    materialId = linkId, condition = input.condition!!.name,
                    movementType = MovementType.JOB_CONSUMPTION.name,
                    origin = MovementOrigin.TRABAJO.name,
                    quantity = normDecimal(input.quantity), unit = input.unit.trim(),
                    jobId = input.jobId, jobMaterialId = jobMaterial.id,
                    note = "Consumo en trabajo",
                    occurredAt = now, createdAt = now, updatedAt = now,
                )
                db.inventoryMovementDao().upsert(mov)
                sync?.enqueue(EntityTypes.INVENTORY_MOVEMENT, mov.id,
                    SyncPayloads.inventoryMovement(mov), 0)
            }
        }
        return jobMaterial.id
    }

    // ---------- Informes de Trabajo (F5) ----------

    fun observeReports(userId: String): Flow<List<ReportEntity>> = db.reportDao().observeForUser(userId)

    fun observeVersionConflictReports(userId: String): Flow<List<ReportEntity>> =
        db.reportDao().observeVersionConflicts(userId)

    fun observeReport(id: String): Flow<ReportEntity?> = db.reportDao().observeOne(id)

    fun observeReportsForJob(jobId: String): Flow<List<ReportEntity>> = db.reportDao().observeForJob(jobId)

    fun observeReportJobs(reportId: String): Flow<List<ReportJobEntity>> =
        db.reportJobDao().observeForReport(reportId)

    fun observeReportVersions(reportId: String): Flow<List<ReportVersionEntity>> =
        db.reportVersionDao().observeForReport(reportId)

    suspend fun getReportSnapshot(reportId: String): ReportSnapshot? =
        db.reportVersionDao().getLatest(reportId)?.let { SnapshotJson.decodeReport(it.snapshotJson) }

    /** Número provisional único entre los Informes del usuario. */
    private suspend fun uniqueReportProvisionalNumber(): String {
        var candidate = newProvisionalNumber("INF")
        while (db.reportDao().provisionalNumberExists(candidate)) {
            candidate = newProvisionalNumber("INF")
        }
        return candidate
    }

    /**
     * Crea o edita un Informe en BORRADOR. Requiere Cliente, Perfil y título;
     * al crear exige al menos un Trabajo y todos deben compartir el Cliente
     * y el Perfil del Informe (el servidor revalida con datos persistidos).
     * El contenido inicial se construye desde los Trabajos (solo actividades
     * COMPLETADA como ejecución) y queda editable mientras sea BORRADOR.
     * Cambiar Cliente/Perfil con vínculos existentes queda bloqueado aquí,
     * no solo en la UI (misma lección que el Cliente del Trabajo en F4).
     */
    suspend fun saveReport(input: ReportInput, existingId: String? = null): String? {
        val user = requireUser()
        if (input.title.isBlank()) return null
        val client = db.clientDao().get(input.clientId) ?: return null
        val profile = db.profileDao().get(input.profileId) ?: return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.reportDao().get(it) }
        if (existing != null && !ReportTransitions.isEditable(ReportStatus.valueOf(existing.status))) {
            return null // EMITIDO: solo corrección explícita
        }
        val links = existing?.let { db.reportJobDao().forReport(it.id) } ?: emptyList()
        if (existing != null && (input.clientId != existing.clientId || input.profileId != existing.profileId)
            && links.isNotEmpty()
        ) {
            return null
        }
        if (existing == null) {
            if (input.jobIds.isEmpty()) return null
            for (jobId in input.jobIds) {
                val job = db.jobDao().get(jobId) ?: return null
                if (job.clientId != client.id || job.profileId != profile.id) return null
            }
        }
        val content = input.content.ifBlank {
            buildReportContent(input.jobIds.ifEmpty { links.map { it.jobId } })
        }
        val reportId = existing?.id ?: Ids.new()
        val report = ReportEntity(
            id = reportId,
            userId = existing?.userId ?: user.id,
            clientId = client.id,
            profileId = profile.id,
            provisionalNumber = existing?.provisionalNumber ?: uniqueReportProvisionalNumber(),
            status = existing?.status ?: ReportStatus.BORRADOR.name,
            title = input.title.trim(),
            destination = input.destination.name,
            content = content.trim(),
            currentVersion = existing?.currentVersion ?: 0,
            emittedVersion = existing?.emittedVersion ?: 0,
            clientNameSnapshot = client.name,
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            versionConflict = existing?.versionConflict ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.reportDao().upsert(report)
            sync?.enqueue(EntityTypes.REPORT, reportId, SyncPayloads.report(report), existing?.serverVersion ?: 0)
            if (existing == null) {
                input.jobIds.forEachIndexed { i, jobId ->
                    val link = ReportJobEntity(
                        id = Ids.new(), reportId = reportId, jobId = jobId,
                        position = i + 1, createdAt = now,
                    )
                    db.reportJobDao().upsert(link)
                    sync?.enqueue(EntityTypes.REPORT_JOB, link.id, SyncPayloads.reportJob(link), 0)
                }
            }
        }
        return reportId
    }

    /**
     * Asocia un Trabajo adicional al borrador: mismo Cliente y Perfil del
     * Informe (regla aprobada; el servidor lo revalida con persistidos) y
     * añade su bloque generado al contenido editable.
     */
    suspend fun linkJobToReport(reportId: String, jobId: String): Boolean {
        val report = db.reportDao().get(reportId) ?: return false
        if (!ReportTransitions.isEditable(ReportStatus.valueOf(report.status))) return false
        val job = db.jobDao().get(jobId) ?: return false
        if (job.clientId != report.clientId || job.profileId != report.profileId) return false
        val links = db.reportJobDao().forReport(reportId)
        if (links.any { it.jobId == jobId }) return false
        val link = ReportJobEntity(
            id = Ids.new(), reportId = reportId, jobId = jobId,
            position = (links.maxOfOrNull { it.position } ?: 0) + 1,
            createdAt = nowEpochMillis(),
        )
        val updated = report.copy(
            content = (report.content.trimEnd() + buildJobContentBlock(jobId)).trim(),
            updatedAt = nowEpochMillis(),
        )
        db.withTransaction {
            db.reportJobDao().upsert(link)
            sync?.enqueue(EntityTypes.REPORT_JOB, link.id, SyncPayloads.reportJob(link), 0)
            db.reportDao().upsert(updated)
            sync?.enqueue(EntityTypes.REPORT, reportId, SyncPayloads.report(updated), report.serverVersion)
        }
        return true
    }

    /**
     * Emitir congela el documento: crea la `report_version` con el snapshot
     * inmutable (cabecera + contenido + Trabajos con solo actividades
     * COMPLETADA) y pasa a EMITIDO. Exige al menos un Trabajo asociado.
     * El número oficial INF lo asigna el backend al aceptar la emisión.
     */
    suspend fun emitReport(id: String): Boolean {
        val report = db.reportDao().get(id) ?: return false
        if (ReportStatus.valueOf(report.status) != ReportStatus.BORRADOR) return false
        if (report.title.isBlank()) return false
        val links = db.reportJobDao().forReport(id)
        if (links.isEmpty()) return false
        val latest = db.reportVersionDao().getLatest(id)
        val version = (latest?.version ?: 0) + 1
        val now = nowEpochMillis()
        val client = db.clientDao().get(report.clientId) ?: return false
        val profile = db.profileDao().get(report.profileId) ?: return false
        val snapshot = ReportSnapshot(
            version = version,
            provisionalNumber = report.provisionalNumber,
            officialNumber = report.officialNumber,
            client = ClientSnapshot(client.id, client.name, client.type, client.phone, client.email, client.address, client.notes),
            profile = ProfileSnapshot(profile.id, profile.businessName, profile.description, profile.commercialInfo, profile.logoRef),
            title = report.title,
            destination = report.destination,
            content = report.content,
            jobs = links.mapNotNull { l -> reportJobSnapshot(l.jobId) },
            savedAtEpochMs = now,
        )
        val versionEntity = ReportVersionEntity(
            id = Ids.new(), reportId = id, version = version,
            snapshotJson = SnapshotJson.encodeReport(snapshot),
            previousVersionId = latest?.id,
            createdAt = now,
        )
        val emitted = report.copy(
            status = ReportStatus.EMITIDO.name,
            currentVersion = version,
            emittedVersion = version,
            updatedAt = now,
            pendingSync = true,
        )
        db.withTransaction {
            db.reportDao().upsert(emitted)
            db.reportVersionDao().insert(versionEntity)
            sync?.enqueue(EntityTypes.REPORT, id, SyncPayloads.report(emitted), report.serverVersion)
            sync?.enqueue(EntityTypes.REPORT_VERSION, versionEntity.id,
                SyncPayloads.reportVersion(versionEntity), 0)
        }
        return true
    }

    /**
     * Corregir un Informe emitido reabre el borrador (EMITIDO→BORRADOR):
     * la versión emitida y su snapshot quedan intactos; al re-emitirse se
     * crea una versión nueva enlazada por `previousVersionId` y el número
     * oficial se conserva.
     */
    suspend fun correctReport(id: String): Boolean {
        val report = db.reportDao().get(id) ?: return false
        if (ReportStatus.valueOf(report.status) != ReportStatus.EMITIDO) return false
        val updated = report.copy(
            status = ReportStatus.BORRADOR.name,
            updatedAt = nowEpochMillis(),
            pendingSync = true,
        )
        db.withTransaction {
            db.reportDao().upsert(updated)
            sync?.enqueue(EntityTypes.REPORT, id, SyncPayloads.report(updated), report.serverVersion)
        }
        return true
    }

    suspend fun setReportArchived(id: String, archived: Boolean) {
        val report = db.reportDao().get(id) ?: return
        val updated = report.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.reportDao().upsert(updated)
            sync?.enqueue(EntityTypes.REPORT, updated.id, SyncPayloads.report(updated), report.serverVersion)
        }
    }

    /** Snapshot del Trabajo para el documento: solo COMPLETADA como ejecutada. */
    private suspend fun reportJobSnapshot(jobId: String): ReportJobSnapshot? {
        val job = db.jobDao().get(jobId) ?: return null
        return ReportJobSnapshot(
            id = job.id,
            number = job.officialNumber ?: job.provisionalNumber,
            status = job.status,
            notes = job.notes,
            completedActivities = db.jobActivityDao().forJob(jobId)
                .filter { it.status == JobActivityStatus.COMPLETADA.name }
                .map { ReportJobActivitySnapshot(it.description, it.finishedAt, it.notes) },
            materials = db.jobMaterialDao().observeForJob(jobId).first()
                .map { ReportJobMaterialSnapshot(it.description, it.origin, it.quantity, it.unit, it.notes) },
        )
    }

    /**
     * Contenido inicial del borrador construido desde los Trabajos: por cada
     * uno, su número, las actividades COMPLETADA (las PENDIENTE/EN_PROCESO
     * nunca aparecen como ejecutadas), materiales descriptivos y
     * observaciones. El usuario puede revisarlo y editarlo.
     */
    suspend fun buildReportContent(jobIds: List<String>): String {
        val sb = StringBuilder()
        jobIds.forEach { sb.append(buildJobContentBlock(it)) }
        return sb.toString().trim()
    }

    private suspend fun buildJobContentBlock(jobId: String): String {
        val job = db.jobDao().get(jobId) ?: return ""
        val sb = StringBuilder()
        sb.append("Trabajo ${job.officialNumber ?: job.provisionalNumber}")
            .append(" (${job.status.replace('_', ' ')})").append('\n')
        val done = db.jobActivityDao().forJob(jobId).filter { it.status == "COMPLETADA" }
        if (done.isNotEmpty()) {
            sb.append("Actividades completadas:").append('\n')
            done.forEach { a ->
                sb.append("- ${a.description}")
                a.finishedAt?.let { sb.append(" (${java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()})") }
                if (a.notes.isNotBlank()) sb.append(" — ${a.notes}")
                sb.append('\n')
            }
        }
        val materials = db.jobMaterialDao().observeForJob(jobId).first()
        if (materials.isNotEmpty()) {
            sb.append("Materiales:").append('\n')
            materials.forEach { m ->
                sb.append("- ${m.description}")
                if (m.quantity.isNotBlank()) sb.append(" (${m.quantity} ${m.unit})".trimEnd().let { " $it" })
                if (m.origin == "CLIENTE") sb.append(" [del cliente]")
                if (m.notes.isNotBlank()) sb.append(" — ${m.notes}")
                sb.append('\n')
            }
        }
        if (job.notes.isNotBlank()) sb.append("Observaciones: ${job.notes}").append('\n')
        sb.append('\n')
        return sb.toString()
    }

    // ---------- Cuentas de Cobro, Métodos de Cobro y Pagos (F6) ----------

    fun observeCollectionAccounts(userId: String): Flow<List<CollectionAccountEntity>> =
        db.collectionAccountDao().observeForUser(userId)

    fun observeCollectionAccountsForClient(clientId: String): Flow<List<CollectionAccountEntity>> =
        db.collectionAccountDao().observeForClient(clientId)

    fun observeCollectionAccount(id: String): Flow<CollectionAccountEntity?> =
        db.collectionAccountDao().observeOne(id)

    fun observeCollectionAccountItems(accountId: String): Flow<List<CollectionAccountItemEntity>> =
        db.collectionAccountItemDao().observeForAccount(accountId)

    fun observeCollectionAccountJobs(accountId: String): Flow<List<CollectionAccountJobEntity>> =
        db.collectionAccountJobDao().observeForAccount(accountId)

    fun observeCollectionAccountQuotes(accountId: String): Flow<List<CollectionAccountQuoteEntity>> =
        db.collectionAccountQuoteDao().observeForAccount(accountId)

    fun observeAllCollectionAccountJobs(): Flow<List<CollectionAccountJobEntity>> =
        db.collectionAccountJobDao().observeAll()

    fun observeAllCollectionAccountQuotes(): Flow<List<CollectionAccountQuoteEntity>> =
        db.collectionAccountQuoteDao().observeAll()

    /**
     * Vínculos que bloquean orígenes (solo Cuentas no canceladas). Es
     * la forma reactiva de la misma regla canónica que aplica
     * `jobTakenByOtherAccount`/`quoteTakenByOtherAccount` al guardar:
     * "a lo sumo una Cuenta no cancelada" (docs/05 §179).
     */
    fun observeBlockingCollectionAccountJobs(): Flow<List<CollectionAccountJobEntity>> =
        db.collectionAccountJobDao().observeBlocking()

    fun observeBlockingCollectionAccountQuotes(): Flow<List<CollectionAccountQuoteEntity>> =
        db.collectionAccountQuoteDao().observeBlocking()

    fun observeCollectionMethods(userId: String): Flow<List<CollectionMethodEntity>> =
        db.collectionMethodDao().observeForUser(userId)

    fun observePayments(userId: String): Flow<List<PaymentEntity>> =
        db.paymentDao().observeForUser(userId)

    fun observePaymentsForAccount(accountId: String): Flow<List<PaymentEntity>> =
        db.paymentDao().observeForAccount(accountId)

    fun observePayment(id: String): Flow<PaymentEntity?> = db.paymentDao().observeOne(id)

    fun observePaymentAssignments(paymentId: String): Flow<List<PaymentAssignmentEntity>> =
        db.paymentAssignmentDao().observeForPayment(paymentId)

    fun observeAccountAssignments(accountId: String): Flow<List<PaymentAssignmentEntity>> =
        db.paymentAssignmentDao().observeForAccount(accountId)

    /** Estado de la última op por entidad (insignias pendiente/rechazado). */
    fun observeLatestOpStates(): Flow<Map<Pair<String, String>, LatestOpState>> =
        db.syncDao().observeLatestOpStates()
            .map { list -> list.associateBy { it.entityType to it.entityId } }

    /**
     * Efecto pendiente/rechazado por destino de cobro
     * ("ACCOUNT:<id>" / "CONSOLIDATED:<id>"), derivado de los eventos de
     * asignación y el estado de su última operación:
     * - pendiente  = última op PENDING/RETRY (aún no evaluada).
     * - rechazado  = última op PAUSED o cerrada como rechazo reconocido.
     * El signo respeta la acción (assign suma al destino, unassign resta).
     * Los saldos confirmados vienen de las columnas espejo `server*` de la
     * fila — esta función NO los altera.
     */
    fun observeDestinationEffects(userId: String): Flow<Map<String, DestEffect>> =
        kotlinx.coroutines.flow.combine(
            db.paymentAssignmentDao().observeForUser(userId),
            db.syncDao().observeLatestOpStates(),
        ) { assignments, opStates ->
            val latestOp = opStates.associateBy { it.entityType to it.entityId }
            val acc = mutableMapOf<String, Pair<BigDecimal, BigDecimal>>()
            for (a in assignments) {
                val op = latestOp[EntityTypes.PAYMENT_ASSIGNMENT to a.id]
                val kind = when {
                    op == null -> if (a.pendingSync) "pending" else "confirmed"
                    op.isRejectedEffect() -> "rejected"
                    op.state == "PENDING" || op.state == "RETRY" -> "pending"
                    else -> "confirmed"
                }
                if (kind == "confirmed") continue
                // Signos por rol de cada destino: en REASSIGN el origen
                // (from*) PIERDE el remanente liberado (releasedAmount) y
                // el destino nuevo GANA solo lo aplicado (amount, recotado
                // al saldo del destino); ASSIGN suma al destino y UNASSIGN
                // resta al origen declarado en accountId/consolidatedId.
                val effects = mutableListOf<Pair<String, BigDecimal>>()
                if (a.action == PaymentAssignmentAction.REASSIGN.name) {
                    val released = (a.releasedAmount ?: a.amount).toMoney().negate()
                    a.fromAccountId?.let { effects += "ACCOUNT:$it" to released }
                    a.fromConsolidatedId?.let { effects += "CONSOLIDATED:$it" to released }
                    val applied = a.amount.toMoney()
                    a.accountId?.let { effects += "ACCOUNT:$it" to applied }
                    a.consolidatedId?.let { effects += "CONSOLIDATED:$it" to applied }
                } else {
                    val sign = if (a.action == PaymentAssignmentAction.UNASSIGN.name) -1 else 1
                    val amount = a.amount.toMoney() * BigDecimal(sign)
                    for (dest in listOfNotNull(
                        a.accountId?.let { "ACCOUNT:$it" },
                        a.consolidatedId?.let { "CONSOLIDATED:$it" },
                    )) effects += dest to amount
                }
                for ((dest, amount) in effects) {
                    val cur = acc[dest] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
                    acc[dest] = if (kind == "pending") cur.first + amount to cur.second
                        else cur.first to cur.second + amount
                }
            }
            acc.mapValues { (k, v) -> DestEffect(v.first, v.second) }
        }

    suspend fun getCollectionAccountItems(accountId: String): List<CollectionAccountItemEntity> =
        db.collectionAccountItemDao().forAccount(accountId)

    suspend fun getCollectionAccountJobIds(accountId: String): List<String> =
        db.collectionAccountJobDao().forAccount(accountId).map { it.jobId }

    suspend fun getCollectionAccountQuoteIds(accountId: String): List<String> =
        db.collectionAccountQuoteDao().forAccount(accountId).map { it.quoteId }

    /** Número provisional único entre las Cuentas de Cobro del usuario. */
    private suspend fun uniqueCollectionProvisionalNumber(): String {
        var candidate = newProvisionalNumber("CC")
        while (db.collectionAccountDao().provisionalNumberExists(candidate)) {
            candidate = newProvisionalNumber("CC")
        }
        return candidate
    }

    /** True si el origen ya pertenece a otra Cuenta no cancelada. */
    private suspend fun jobTakenByOtherAccount(jobId: String, selfId: String?): Boolean =
        db.collectionAccountJobDao().forJob(jobId)
            .filter { it.accountId != selfId }
            .mapNotNull { db.collectionAccountDao().get(it.accountId) }
            .any { it.status != CollectionAccountStatus.CANCELADA.name }

    private suspend fun quoteTakenByOtherAccount(quoteId: String, selfId: String?): Boolean =
        db.collectionAccountQuoteDao().forQuote(quoteId)
            .filter { it.accountId != selfId }
            .mapNotNull { db.collectionAccountDao().get(it.accountId) }
            .any { it.status != CollectionAccountStatus.CANCELADA.name }

    /**
     * Crea o edita una Cuenta de Cobro (docs/03 §6). Nace en PENDIENTE con
     * `CC-PEND-XXXX`; editable solo en PENDIENTE y sin pagos aplicados.
     * Reutiliza el motor de cálculo F2: mismas líneas tipadas
     * SERVICIO/MATERIAL/OTRO, retenciones configuradas y redondeo; sin
     * impuestos ni descuentos. Los snapshots de Cliente, Perfil y
     * retenciones se capturan en cada guardado permitido y quedan
     * inmutables junto con líneas y totales tras el primer pago aplicado.
     * Nunca mezcla Trabajos y Cotizaciones; todos los orígenes comparten
     * Cliente y Perfil; solo Cotizaciones APROBADA son elegibles y cada
     * origen pertenece a lo sumo a una Cuenta no cancelada.
     */
    suspend fun saveCollectionAccount(input: CollectionAccountInput, existingId: String? = null): String? {
        val user = requireUser()
        if (input.items.isEmpty()) return null
        if (input.items.any { it.description.isBlank() }) return null
        if (input.jobIds.isNotEmpty() && input.quoteIds.isNotEmpty()) return null // nunca mezclar
        if (input.jobIds.toSet().size != input.jobIds.size
            || input.quoteIds.toSet().size != input.quoteIds.size
        ) return null
        val client = db.clientDao().get(input.clientId) ?: return null
        val profile = db.profileDao().get(input.profileId) ?: return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.collectionAccountDao().get(it) }
        if (existing != null) {
            val st = CollectionAccountStatus.valueOf(existing.status)
            if (!CollectionAccountTransitions.isEditable(st, existing.appliedAmount.toMoney())) {
                return null // con pagos aplicados (o cancelada): líneas/totales/snapshots inmutables
            }
            // F7: miembro de un Consolidado activo — la Cuenta queda
            // congelada (el Consolidado tomó su saldo); solo el archivado
            // sigue permitido por su vía propia.
            if (db.consolidatedCollectionAccountDao()
                    .activeConsolidatedForAccount(existing.id) != null
            ) return null
        }
        for (jobId in input.jobIds) {
            val job = db.jobDao().get(jobId) ?: return null
            if (job.clientId != client.id || job.profileId != profile.id) return null
            if (jobTakenByOtherAccount(jobId, existing?.id)) return null
        }
        for (quoteId in input.quoteIds) {
            val quote = db.quoteDao().get(quoteId) ?: return null
            if (quote.clientId != client.id || quote.profileId != profile.id) return null
            if (quote.status != QuoteStatus.APROBADA.name) return null // elegibilidad F6
            if (quoteTakenByOtherAccount(quoteId, existing?.id)) return null
        }

        val calc = QuoteCalc.compute(
            input.items.map { CalcLine(it.conceptType, it.quantity.toQty(), it.unitPrice.toMoney()) },
            input.retentions.map { RetentionSpec(it.name, it.ratePercent.toQty(), it.appliesTo) },
        )
        val applied = existing?.appliedAmount?.toMoney() ?: BigDecimal.ZERO.setScale(2)
        val accountId = existing?.id ?: Ids.new()
        val account = CollectionAccountEntity(
            id = accountId,
            userId = existing?.userId ?: user.id,
            clientId = client.id,
            profileId = profile.id,
            provisionalNumber = existing?.provisionalNumber ?: uniqueCollectionProvisionalNumber(),
            status = existing?.status ?: CollectionAccountStatus.PENDIENTE.name,
            currency = input.currency.uppercase(),
            gross = QuoteCalc.format(calc.gross),
            totalRetained = QuoteCalc.format(calc.totalRetained),
            net = QuoteCalc.format(calc.net),
            appliedAmount = QuoteCalc.format(applied),
            balance = QuoteCalc.format(calc.net.subtract(applied)),
            issuedAt = existing?.issuedAt ?: now,
            dueDateEpochDay = input.dueDateEpochDay,
            conditions = input.conditions.trim(),
            clientSnapshotJson = SnapshotJson.encodeClient(
                ClientSnapshot(client.id, client.name, client.type, client.phone, client.email, client.address, client.notes),
            ),
            profileSnapshotJson = SnapshotJson.encodeProfile(
                ProfileSnapshot(profile.id, profile.businessName, profile.description, profile.commercialInfo, profile.logoRef),
            ),
            retentionsSnapshotJson = SnapshotJson.encodeRetentions(
                calc.retentions.map {
                    AppliedRetentionSnapshot(
                        name = it.name,
                        ratePercent = it.ratePercent.stripTrailingZeros().toPlainString(),
                        appliesTo = it.appliesTo.sorted().map(ConceptType::name),
                        base = QuoteCalc.format(it.base),
                        amount = QuoteCalc.format(it.amount),
                    )
                },
            ),
            clientNameSnapshot = client.name,
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        val itemEntities = input.items.mapIndexed { i, l ->
            CollectionAccountItemEntity(
                id = Ids.new(), accountId = accountId, position = i + 1,
                conceptType = l.conceptType.name, description = l.description.trim(),
                quantity = l.quantity.trim(), unit = l.unit.trim(),
                unitPrice = QuoteCalc.format(l.unitPrice.toMoney()),
                lineTotal = QuoteCalc.format(calc.lineTotals[i]),
                createdAt = now,
            )
        }
        val jobLinks = input.jobIds.mapIndexed { i, jobId ->
            CollectionAccountJobEntity(
                id = Ids.new(), accountId = accountId, jobId = jobId,
                position = i + 1, createdAt = now,
            )
        }
        val quoteLinks = input.quoteIds.mapIndexed { i, quoteId ->
            CollectionAccountQuoteEntity(
                id = Ids.new(), accountId = accountId, quoteId = quoteId,
                position = i + 1, createdAt = now,
            )
        }
        // Una sola transacción: documento, líneas, vínculos y operación.
        db.withTransaction {
            db.collectionAccountDao().upsert(account)
            db.collectionAccountItemDao().deleteForAccount(accountId)
            db.collectionAccountItemDao().upsertAll(itemEntities)
            db.collectionAccountJobDao().deleteForAccount(accountId)
            db.collectionAccountJobDao().upsertAll(jobLinks)
            db.collectionAccountQuoteDao().deleteForAccount(accountId)
            db.collectionAccountQuoteDao().upsertAll(quoteLinks)
            sync?.enqueue(
                EntityTypes.COLLECTION_ACCOUNT, accountId,
                SyncPayloads.collectionAccount(
                    account, itemEntities,
                    jobLinks.map { it.jobId }, quoteLinks.map { it.quoteId },
                ),
                existing?.serverVersion ?: 0,
            )
        }
        return accountId
    }

    /**
     * Cancela la Cuenta: solo sin pagos aplicados (docs/03 §6). La
     * cancelación libera las asociaciones (dejan de bloquear).
     */
    suspend fun cancelCollectionAccount(id: String): Boolean {
        val account = db.collectionAccountDao().get(id) ?: return false
        val st = CollectionAccountStatus.valueOf(account.status)
        if (!CollectionAccountTransitions.canCancel(st, account.appliedAmount.toMoney())) return false
        // F7: una Cuenta en un Consolidado activo no se cancela — primero
        // se retira del Consolidado o se cancela el Consolidado.
        if (db.consolidatedCollectionAccountDao().activeConsolidatedForAccount(id) != null) {
            return false
        }
        val updated = account.copy(
            status = CollectionAccountStatus.CANCELADA.name,
            updatedAt = nowEpochMillis(),
            pendingSync = true,
        )
        db.withTransaction {
            db.collectionAccountDao().upsert(updated)
            sync?.enqueue(
                EntityTypes.COLLECTION_ACCOUNT, id, collectionAccountPayload(updated),
                account.serverVersion,
            )
        }
        return true
    }

    suspend fun setCollectionAccountArchived(id: String, archived: Boolean) {
        val account = db.collectionAccountDao().get(id) ?: return
        val updated = account.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.collectionAccountDao().upsert(updated)
            sync?.enqueue(
                EntityTypes.COLLECTION_ACCOUNT, id, collectionAccountPayload(updated),
                account.serverVersion,
            )
        }
    }

    /** Payload completo del documento: líneas y vínculos viajan dentro. */
    private suspend fun collectionAccountPayload(account: CollectionAccountEntity) =
        SyncPayloads.collectionAccount(
            account,
            db.collectionAccountItemDao().forAccount(account.id),
            db.collectionAccountJobDao().forAccount(account.id).map { it.jobId },
            db.collectionAccountQuoteDao().forAccount(account.id).map { it.quoteId },
        )

    // --- Métodos de Cobro (globales por Titular, sin predeterminados) ---

    suspend fun saveCollectionMethod(input: CollectionMethodInput, existingId: String? = null): String? {
        if (validateCollectionMethod(input.name) != null) return null
        val user = requireUser()
        val now = nowEpochMillis()
        val existing = existingId?.let { db.collectionMethodDao().get(it) }
        val method = CollectionMethodEntity(
            id = existing?.id ?: Ids.new(),
            userId = existing?.userId ?: user.id,
            name = input.name.trim(),
            detail = input.detail.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.collectionMethodDao().upsert(method)
            sync?.enqueue(EntityTypes.COLLECTION_METHOD, method.id,
                SyncPayloads.collectionMethod(method), existing?.serverVersion ?: 0)
        }
        return method.id
    }

    suspend fun setCollectionMethodArchived(id: String, archived: Boolean) {
        val method = db.collectionMethodDao().get(id) ?: return
        val updated = method.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.collectionMethodDao().upsert(updated)
            sync?.enqueue(EntityTypes.COLLECTION_METHOD, updated.id,
                SyncPayloads.collectionMethod(updated), method.serverVersion)
        }
    }

    // --- Pagos (append-only) ---

    /**
     * Registra un Pago recibido: contra una Cuenta individual, un
     * Consolidado o sin asignar vinculado al Cliente (docs/03 §6). Si se
     * indica destino, la aplicación inicial se registra como el primer
     * evento `payment_assignment` auditado en la misma transacción — una
     * sola vía de aplicación, igual que cualquier `assign` posterior.
     * El pago nunca se edita ni se elimina.
     */
    suspend fun savePayment(input: PaymentInput): String? {
        val user = requireUser()
        val client = db.clientDao().get(input.clientId) ?: return null
        if (input.collectionMethodId != null
            && db.collectionMethodDao().get(input.collectionMethodId) == null
        ) return null
        val amount = input.amount.toMoney()
        if (amount.signum() <= 0) return null
        if (input.accountId != null && input.consolidatedId != null) return null
        val account = input.accountId?.let { db.collectionAccountDao().get(it) }
        val consolidated = input.consolidatedId?.let { db.consolidatedCollectionDao().get(it) }
        if (input.accountId != null) {
            if (account == null || account.clientId != client.id) return null
            val st = CollectionAccountStatus.valueOf(account.status)
            if (!CollectionAccountTransitions.canReceivePayments(st)
                || account.balance.toMoney().signum() <= 0
            ) return null
            if (db.consolidatedCollectionAccountDao()
                    .activeConsolidatedForAccount(account.id) != null
            ) return null // miembro de Consolidado activo: sin asignación directa
        }
        if (input.consolidatedId != null) {
            if (consolidated == null || consolidated.clientId != client.id) return null
            val st = CollectionAccountStatus.valueOf(consolidated.status)
            if (!ConsolidatedTransitions.canReceivePayments(st)
                || consolidated.balance.toMoney().signum() <= 0
            ) return null
        }
        val now = nowEpochMillis()
        val payment = PaymentEntity(
            id = Ids.new(),
            userId = user.id,
            clientId = client.id,
            collectionMethodId = input.collectionMethodId,
            amount = QuoteCalc.format(amount),
            paidAtEpochDay = input.paidAtEpochDay,
            reference = input.reference.trim(),
            notes = input.notes.trim(),
            appliedAmount = "0.00",
            unassignedAmount = QuoteCalc.format(amount),
            effectiveAmount = QuoteCalc.format(amount),
            refundedAmount = "0.00",
            annulled = false,
            assignedAccountId = null,
            assignedConsolidatedId = null,
            assignedTargetType = null,
            assignmentStatus = PaymentAssignmentStatus.UNASSIGNED.name,
            createdAt = now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = 0,
        )
        db.withTransaction {
            db.paymentDao().upsert(payment)
            sync?.enqueue(EntityTypes.PAYMENT, payment.id, SyncPayloads.payment(payment), 0)
            val reason = input.initialAssignReason.ifBlank { "Aplicación inicial del pago" }
            when {
                account != null ->
                    applyAssignLocked(payment, PaymentTargetKind.ACCOUNT, account.id,
                        account.clientId, account.status, account.balance.toMoney(), amount, reason, now)
                consolidated != null ->
                    applyAssignLocked(payment, PaymentTargetKind.CONSOLIDATED, consolidated.id,
                        consolidated.clientId, consolidated.status, consolidated.balance.toMoney(), amount, reason, now)
            }
        }
        return payment.id
    }

    /**
     * Asigna dinero sin asignar de un Pago a una Cuenta o Consolidado:
     * aplica solo lo necesario (min de lo pedido, el remanente y el saldo);
     * el excedente queda UNASSIGNED en el mismo Pago. Devuelve null si
     * aplicó o el motivo del rechazo.
     */
    suspend fun assignPayment(paymentId: String, accountId: String, requested: String, reason: String): String? =
        assignPaymentToTarget(paymentId, PaymentTargetKind.ACCOUNT, accountId, requested, reason)

    /** Variante F7 genérica: el destino puede ser una Cuenta o un Consolidado. */
    suspend fun assignPaymentToTarget(
        paymentId: String,
        targetKind: PaymentTargetKind,
        targetId: String,
        requested: String,
        reason: String,
    ): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La asignación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago está anulado; no admite asignaciones."
            }
            when (targetKind) {
                PaymentTargetKind.ACCOUNT -> {
                    val account = db.collectionAccountDao().get(targetId)
                        ?: return@withTransaction "Cuenta no encontrada."
                    if (db.consolidatedCollectionAccountDao()
                            .activeConsolidatedForAccount(targetId) != null
                    ) {
                        return@withTransaction "La cuenta pertenece a un consolidado activo; asigna al consolidado."
                    }
                    val ok = applyAssignLocked(
                        payment, PaymentTargetKind.ACCOUNT, account.id,
                        account.clientId, account.status, account.balance.toMoney(),
                        requested.toMoney(), reason, nowEpochMillis(),
                    )
                    if (ok == null) {
                        return@withTransaction "La asignación no aplicó: sin remanente, cuenta cerrada o destino inválido."
                    }
                }
                PaymentTargetKind.CONSOLIDATED -> {
                    val consolidated = db.consolidatedCollectionDao().get(targetId)
                        ?: return@withTransaction "Consolidado no encontrado."
                    val ok = applyAssignLocked(
                        payment, PaymentTargetKind.CONSOLIDATED, consolidated.id,
                        consolidated.clientId, consolidated.status, consolidated.balance.toMoney(),
                        requested.toMoney(), reason, nowEpochMillis(),
                    )
                    if (ok == null) {
                        return@withTransaction "La asignación no aplicó: sin remanente o consolidado cerrado."
                    }
                }
            }
            null
        }

    /**
     * Retira la aplicación vigente del Pago (con motivo): el dinero vuelve
     * al remanente libre dentro del mismo Pago y el saldo del destino se
     * restaura. Evento auditado append-only; no edita ni elimina nada.
     */
    suspend fun unassignPayment(paymentId: String, reason: String): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La operación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago está anulado; no admite más movimientos."
            }
            val targetKind = payment.assignedTargetType?.let { PaymentTargetKind.valueOf(it) }
                ?: if (payment.assignedAccountId != null) PaymentTargetKind.ACCOUNT else null
                ?: return@withTransaction "El pago no tiene dinero aplicado."
            val targetId = when (targetKind) {
                PaymentTargetKind.ACCOUNT -> payment.assignedAccountId
                PaymentTargetKind.CONSOLIDATED -> payment.assignedConsolidatedId
            } ?: return@withTransaction "El pago no tiene dinero aplicado."
            val released = payment.appliedAmount.toMoney()
            if (released.signum() <= 0) return@withTransaction "El pago no tiene dinero aplicado."
            if (targetKind == PaymentTargetKind.ACCOUNT
                && db.consolidatedCollectionAccountDao()
                    .activeConsolidatedForAccount(targetId) != null
            ) {
                return@withTransaction "El dinero aplicado está en una cuenta dentro de un consolidado activo."
            }
            val now = nowEpochMillis()
            val event = PaymentAssignmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                action = PaymentAssignmentAction.UNASSIGN.name,
                targetType = targetKind.name,
                accountId = if (targetKind == PaymentTargetKind.ACCOUNT) targetId else null,
                consolidatedId = if (targetKind == PaymentTargetKind.CONSOLIDATED) targetId else null,
                amount = QuoteCalc.format(released),
                releasedAmount = QuoteCalc.format(released),
                reason = reason.trim(),
                createdAt = now, updatedAt = now,
            )
            // Remanente libre: efectivo − aplicado − reembolsado.
            val newUnassigned = payment.effectiveAmount.toMoney()
            db.paymentDao().upsert(
                payment.copy(
                    appliedAmount = "0.00",
                    unassignedAmount = QuoteCalc.format(newUnassigned.subtract(payment.refundedAmount.toMoney())),
                    assignedTargetType = null, assignedAccountId = null, assignedConsolidatedId = null,
                    assignmentStatus = deriveAssignmentStatus(
                        BigDecimal.ZERO,
                        newUnassigned.subtract(payment.refundedAmount.toMoney()),
                    ).name,
                    updatedAt = now,
                ),
            )
            releaseTargetLocked(targetKind, targetId, released, now)
            db.paymentAssignmentDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_ASSIGNMENT, event.id,
                SyncPayloads.paymentAssignment(event), 0)
            null
        }

    /**
     * Reasigna la aplicación vigente del Pago a otra Cuenta del mismo
     * Cliente (con motivo): libera de la origen y aplica al destino solo
     * hasta su saldo pendiente; el excedente queda UNASSIGNED en el mismo
     * Pago. Un solo evento auditado, atómico.
     */
    suspend fun reassignPayment(paymentId: String, toAccountId: String, reason: String): String? =
        reassignPaymentToTarget(paymentId, PaymentTargetKind.ACCOUNT, toAccountId, reason)

    /** Variante F7 genérica: origen y destino pueden ser Cuenta o Consolidado. */
    suspend fun reassignPaymentToTarget(
        paymentId: String,
        targetKind: PaymentTargetKind,
        targetId: String,
        reason: String,
    ): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La reasignación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago está anulado; no admite más movimientos."
            }
            val fromKind = payment.assignedTargetType?.let { PaymentTargetKind.valueOf(it) }
                ?: if (payment.assignedAccountId != null) PaymentTargetKind.ACCOUNT else null
                ?: return@withTransaction "El pago no tiene dinero aplicado; usa asignar."
            val fromId = when (fromKind) {
                PaymentTargetKind.ACCOUNT -> payment.assignedAccountId
                PaymentTargetKind.CONSOLIDATED -> payment.assignedConsolidatedId
            } ?: return@withTransaction "El pago no tiene dinero aplicado; usa asignar."
            if (fromKind == targetKind && fromId == targetId) {
                return@withTransaction "El pago ya está aplicado a ese destino."
            }
            if (fromKind == PaymentTargetKind.ACCOUNT
                && db.consolidatedCollectionAccountDao()
                    .activeConsolidatedForAccount(fromId) != null
            ) {
                return@withTransaction "El dinero aplicado está en una cuenta dentro de un consolidado activo."
            }
            val released = payment.appliedAmount.toMoney()
            val (toClientId, toStatus, toBalance) = when (targetKind) {
                PaymentTargetKind.ACCOUNT -> {
                    val to = db.collectionAccountDao().get(targetId)
                        ?: return@withTransaction "Cuenta destino no encontrada."
                    if (db.consolidatedCollectionAccountDao()
                            .activeConsolidatedForAccount(targetId) != null
                    ) {
                        return@withTransaction "La cuenta destino pertenece a un consolidado activo."
                    }
                    Triple(to.clientId, to.status, to.balance.toMoney())
                }
                PaymentTargetKind.CONSOLIDATED -> {
                    val to = db.consolidatedCollectionDao().get(targetId)
                        ?: return@withTransaction "Consolidado destino no encontrado."
                    Triple(to.clientId, to.status, to.balance.toMoney())
                }
            }
            if (toClientId != payment.clientId) {
                return@withTransaction "El destino pertenece a otro cliente."
            }
            if (!ConsolidatedTransitions.canReceivePayments(CollectionAccountStatus.valueOf(toStatus))
                || toBalance.signum() <= 0
            ) {
                return@withTransaction "El destino no puede recibir pagos."
            }
            val appliedTo = released.min(toBalance)
            val now = nowEpochMillis()
            val event = PaymentAssignmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                action = PaymentAssignmentAction.REASSIGN.name,
                targetType = targetKind.name,
                accountId = if (targetKind == PaymentTargetKind.ACCOUNT) targetId else null,
                consolidatedId = if (targetKind == PaymentTargetKind.CONSOLIDATED) targetId else null,
                fromAccountId = if (fromKind == PaymentTargetKind.ACCOUNT) fromId else null,
                fromConsolidatedId = if (fromKind == PaymentTargetKind.CONSOLIDATED) fromId else null,
                amount = QuoteCalc.format(appliedTo),
                releasedAmount = QuoteCalc.format(released), reason = reason.trim(),
                createdAt = now, updatedAt = now,
            )
            val newApplied = appliedTo
            val newUnassigned = payment.effectiveAmount.toMoney()
                .subtract(newApplied).subtract(payment.refundedAmount.toMoney())
            db.paymentDao().upsert(
                payment.copy(
                    appliedAmount = QuoteCalc.format(newApplied),
                    unassignedAmount = QuoteCalc.format(newUnassigned),
                    assignedTargetType = if (newApplied.signum() > 0) targetKind.name else null,
                    assignedAccountId = if (newApplied.signum() > 0 && targetKind == PaymentTargetKind.ACCOUNT) targetId else null,
                    assignedConsolidatedId = if (newApplied.signum() > 0 && targetKind == PaymentTargetKind.CONSOLIDATED) targetId else null,
                    assignmentStatus = deriveAssignmentStatus(newApplied, newUnassigned).name,
                    updatedAt = now,
                ),
            )
            releaseTargetLocked(fromKind, fromId, released, now)
            applyToTargetLocked(targetKind, targetId, appliedTo, now)
            db.paymentAssignmentDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_ASSIGNMENT, event.id,
                SyncPayloads.paymentAssignment(event), 0)
            null
        }

    // --- F7: Consolidados de Cuentas de Cobro ---

    fun observeConsolidatedCollections(userId: String): Flow<List<ConsolidatedCollectionEntity>> =
        db.consolidatedCollectionDao().observeForUser(userId)

    fun observeConsolidatedCollection(id: String): Flow<ConsolidatedCollectionEntity?> =
        db.consolidatedCollectionDao().observeOne(id)

    fun observeConsolidatedMembers(consolidatedId: String): Flow<List<ConsolidatedCollectionAccountEntity>> =
        db.consolidatedCollectionAccountDao().observeForConsolidated(consolidatedId)

    /** Membresías (cualquier estado) de la Cuenta en Consolidados. */
    fun observeConsolidatedMemberships(accountId: String): Flow<List<ConsolidatedCollectionAccountEntity>> =
        db.consolidatedCollectionAccountDao().observeForAccount(accountId)

    /** Membresías vigentes en Consolidados no cancelados (para la UI). */
    fun observeActiveMemberships(): Flow<List<ConsolidatedCollectionAccountEntity>> =
        db.consolidatedCollectionAccountDao().observeActiveMemberships()

    fun observePaymentsForConsolidated(consolidatedId: String): Flow<List<PaymentEntity>> =
        db.consolidatedCollectionAccountDao().observePaymentsForConsolidated(consolidatedId)

    /** ¿La Cuenta pertenece a un Consolidado no cancelado? */
    suspend fun activeConsolidatedForAccount(accountId: String): ConsolidatedCollectionEntity? =
        db.consolidatedCollectionAccountDao().activeConsolidatedForAccount(accountId)

    /**
     * Crea/edita un Consolidado (docs/03 §6): ≥2 Cuentas no canceladas
     * con saldo pendiente > 0, mismo Cliente y misma moneda (los Perfiles
     * pueden diferir; cada Cuenta conserva sus propios snapshots). Los
     * totales se derivan de los saldos vigentes de las Cuentas — la suma
     * de sus saldos pendientes en el momento de la escritura. Nace
     * PENDIENTE con numeración provisional `CONS-PEND-XXXX`; la oficial
     * `CONS-AAAAMM-NNN` llega en la primera aceptación del servidor.
     * Miembros editables solo en PENDIENTE sin pagos aplicados.
     */
    suspend fun saveConsolidatedCollection(
        input: ConsolidatedInput,
        existingId: String? = null,
    ): String? {
        val user = requireUser()
        if (input.accountIds.toSet().size != input.accountIds.size || input.accountIds.size < 2) {
            return null
        }
        val client = db.clientDao().get(input.clientId) ?: return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.consolidatedCollectionDao().get(it) }
        if (existing != null) {
            val st = CollectionAccountStatus.valueOf(existing.status)
            if (!ConsolidatedTransitions.isEditable(st, existing.appliedAmount.toMoney())) {
                return null // PARCIAL/PAGADA/CANCELADA: membresía cerrada
            }
        }
        var currency: String? = null
        var total = BigDecimal.ZERO
        for (accountId in input.accountIds) {
            val account = db.collectionAccountDao().get(accountId) ?: return null
            if (account.clientId != client.id) return null
            if (account.status == CollectionAccountStatus.CANCELADA.name) return null
            if (account.balance.toMoney().signum() <= 0) return null
            if (currency == null) currency = account.currency
            else if (account.currency != currency) return null
            val holder = db.consolidatedCollectionAccountDao().activeConsolidatedForAccount(accountId)
            if (holder != null && holder.id != existing?.id) return null
            total = total.add(account.balance.toMoney())
        }
        val consolidated = ConsolidatedCollectionEntity(
            id = existing?.id ?: Ids.new(),
            userId = existing?.userId ?: user.id,
            clientId = client.id,
            provisionalNumber = existing?.provisionalNumber ?: uniqueConsolidatedProvisionalNumber(),
            status = CollectionAccountStatus.PENDIENTE.name,
            currency = currency ?: input.currency.uppercase(),
            totalAmount = QuoteCalc.format(total),
            appliedAmount = "0.00",
            balance = QuoteCalc.format(total),
            clientNameSnapshot = client.name,
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        val links = input.accountIds.mapIndexed { i, accountId ->
            ConsolidatedCollectionAccountEntity(
                id = Ids.new(), consolidatedId = consolidated.id, accountId = accountId,
                position = i + 1, createdAt = now,
            )
        }
        db.withTransaction {
            db.consolidatedCollectionDao().upsert(consolidated)
            db.consolidatedCollectionAccountDao().deleteForConsolidated(consolidated.id)
            db.consolidatedCollectionAccountDao().upsertAll(links)
            sync?.enqueue(
                EntityTypes.CONSOLIDATED_COLLECTION, consolidated.id,
                SyncPayloads.consolidatedCollection(consolidated, links.map { it.accountId }),
                existing?.serverVersion ?: 0,
            )
        }
        return consolidated.id
    }

    /**
     * Cancela el Consolidado: solo en PENDIENTE sin pagos aplicados; la
     * membresía histórica se conserva y las Cuentas quedan libres.
     */
    suspend fun cancelConsolidatedCollection(id: String): Boolean {
        val consolidated = db.consolidatedCollectionDao().get(id) ?: return false
        val st = CollectionAccountStatus.valueOf(consolidated.status)
        if (!ConsolidatedTransitions.canCancel(st, consolidated.appliedAmount.toMoney())) return false
        val updated = consolidated.copy(
            status = CollectionAccountStatus.CANCELADA.name,
            updatedAt = nowEpochMillis(),
            pendingSync = true,
        )
        val members = db.consolidatedCollectionAccountDao().forConsolidated(id)
        db.withTransaction {
            db.consolidatedCollectionDao().upsert(updated)
            sync?.enqueue(
                EntityTypes.CONSOLIDATED_COLLECTION, id,
                SyncPayloads.consolidatedCollection(updated, members.map { it.accountId }),
                consolidated.serverVersion,
            )
        }
        return true
    }

    suspend fun setConsolidatedArchived(id: String, archived: Boolean) {
        val consolidated = db.consolidatedCollectionDao().get(id) ?: return
        val members = db.consolidatedCollectionAccountDao().forConsolidated(id)
        val updated = consolidated.copy(archived = archived, updatedAt = nowEpochMillis())
        db.withTransaction {
            db.consolidatedCollectionDao().upsert(updated)
            sync?.enqueue(
                EntityTypes.CONSOLIDATED_COLLECTION, id,
                SyncPayloads.consolidatedCollection(updated, members.map { it.accountId }),
                consolidated.serverVersion,
            )
        }
    }

    private suspend fun uniqueConsolidatedProvisionalNumber(): String {
        var n: String
        do {
            n = "CONS-PEND-" + (1000 + kotlin.random.Random.nextInt(9000)).toString()
        } while (db.consolidatedCollectionDao().provisionalNumberExists(n))
        return n
    }

    // --- F7: Ajustes de Pago (append-only) ---

    fun observePaymentAdjustments(paymentId: String): Flow<List<PaymentAdjustmentEntity>> =
        db.paymentAdjustmentDao().observeForPayment(paymentId)

    /**
     * Corrección del importe efectivo (evento `payment_adjustment`
     * append-only, docs/03 §6): al alza el incremento queda UNASSIGNED; a
     * la baja consume primero lo UNASSIGNED y luego libera la asignación
     * vigente (LIFO: la aplicación más reciente es la única vigente), todo
     * atómico. Rechaza si el nuevo efectivo queda por debajo del acumulado
     * reembolsado. Devuelve null o el motivo del rechazo.
     */
    suspend fun correctPayment(paymentId: String, newEffective: String, reason: String): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La corrección requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago está anulado; no admite correcciones."
            }
            val target = newEffective.toMoney()
            val effective = payment.effectiveAmount.toMoney()
            val applied = payment.appliedAmount.toMoney()
            val refunded = payment.refundedAmount.toMoney()
            if (target.signum() < 0 || target.compareTo(effective) == 0) {
                return@withTransaction "La corrección requiere un importe efectivo distinto y válido."
            }
            if (target < refunded) {
                return@withTransaction "El importe corregido no puede quedar por debajo de lo ya reembolsado."
            }
            val now = nowEpochMillis()
            var releasedNow = BigDecimal.ZERO
            var releasedFromType: String? = null
            var releasedFromId: String? = null
            var newApplied = applied
            if (target < effective) {
                val free = effective.subtract(applied).subtract(refunded)
                val need = target.let { effective.subtract(it) }.subtract(free)
                if (need.signum() > 0) {
                    val fromKind = payment.assignedTargetType?.let { PaymentTargetKind.valueOf(it) }
                        ?: if (payment.assignedAccountId != null) PaymentTargetKind.ACCOUNT else null
                    val fromId = when (fromKind) {
                        PaymentTargetKind.ACCOUNT -> payment.assignedAccountId
                        PaymentTargetKind.CONSOLIDATED -> payment.assignedConsolidatedId
                        null -> null
                    }
                    if (fromKind == PaymentTargetKind.ACCOUNT && fromId != null
                        && db.consolidatedCollectionAccountDao()
                            .activeConsolidatedForAccount(fromId) != null
                    ) {
                        return@withTransaction "La corrección liberaría fondos de una cuenta en un consolidado activo."
                    }
                    if (fromId != null) {
                        releaseTargetLocked(fromKind!!, fromId, need, now)
                        releasedFromType = fromKind.name
                        releasedFromId = fromId
                    }
                    releasedNow = need
                    newApplied = applied.subtract(need)
                }
            }
            val newUnassigned = target.subtract(newApplied).subtract(refunded)
            val event = PaymentAdjustmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                kind = PaymentAdjustmentKind.CORRECTION.name, reason = reason.trim(),
                newEffectiveAmount = QuoteCalc.format(target),
                effectiveBefore = QuoteCalc.format(effective),
                effectiveAfter = QuoteCalc.format(target),
                releasedAmount = QuoteCalc.format(releasedNow),
                releasedFromType = releasedFromType, releasedFromId = releasedFromId,
                createdAt = now, updatedAt = now,
            )
            db.paymentDao().upsert(
                payment.copy(
                    effectiveAmount = QuoteCalc.format(target),
                    appliedAmount = QuoteCalc.format(newApplied),
                    unassignedAmount = QuoteCalc.format(newUnassigned),
                    assignedTargetType = if (newApplied.signum() <= 0) null else payment.assignedTargetType,
                    assignedAccountId = if (newApplied.signum() <= 0) null else payment.assignedAccountId,
                    assignedConsolidatedId = if (newApplied.signum() <= 0) null else payment.assignedConsolidatedId,
                    assignmentStatus = deriveAssignmentStatus(newApplied, newUnassigned).name,
                    updatedAt = now,
                ),
            )
            db.paymentAdjustmentDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_ADJUSTMENT, event.id,
                SyncPayloads.paymentAdjustment(event), 0)
            null
        }

    /**
     * Anulación terminal (evento `payment_adjustment` ANNULMENT, una sola
     * vez por Pago): libera atómicamente la aplicación vigente y fija el
     * efectivo al acumulado reembolsado (cero si no hubo Reembolsos).
     * Después se rechazan asignaciones, correcciones y Reembolsos.
     */
    suspend fun annulPayment(paymentId: String, reason: String): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La anulación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago ya está anulado; la anulación es una sola vez y terminal."
            }
            val effective = payment.effectiveAmount.toMoney()
            val applied = payment.appliedAmount.toMoney()
            val refunded = payment.refundedAmount.toMoney()
            val now = nowEpochMillis()
            var releasedFromType: String? = null
            var releasedFromId: String? = null
            if (applied.signum() > 0) {
                val fromKind = payment.assignedTargetType?.let { PaymentTargetKind.valueOf(it) }
                    ?: if (payment.assignedAccountId != null) PaymentTargetKind.ACCOUNT else null
                val fromId = when (fromKind) {
                    PaymentTargetKind.ACCOUNT -> payment.assignedAccountId
                    PaymentTargetKind.CONSOLIDATED -> payment.assignedConsolidatedId
                    null -> null
                }
                if (fromKind == PaymentTargetKind.ACCOUNT && fromId != null
                    && db.consolidatedCollectionAccountDao()
                        .activeConsolidatedForAccount(fromId) != null
                ) {
                    return@withTransaction "La anulación liberaría fondos de una cuenta en un consolidado activo."
                }
                if (fromId != null) {
                    releaseTargetLocked(fromKind!!, fromId, applied, now)
                    releasedFromType = fromKind.name
                    releasedFromId = fromId
                }
            }
            val event = PaymentAdjustmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                kind = PaymentAdjustmentKind.ANNULMENT.name, reason = reason.trim(),
                newEffectiveAmount = null,
                effectiveBefore = QuoteCalc.format(effective),
                effectiveAfter = QuoteCalc.format(refunded),
                releasedAmount = QuoteCalc.format(applied),
                releasedFromType = releasedFromType, releasedFromId = releasedFromId,
                createdAt = now, updatedAt = now,
            )
            db.paymentDao().upsert(
                payment.copy(
                    effectiveAmount = QuoteCalc.format(refunded),
                    appliedAmount = "0.00",
                    unassignedAmount = "0.00",
                    assignedTargetType = null, assignedAccountId = null, assignedConsolidatedId = null,
                    assignmentStatus = PaymentAssignmentStatus.UNASSIGNED.name,
                    annulled = true,
                    updatedAt = now,
                ),
            )
            db.paymentAdjustmentDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_ADJUSTMENT, event.id,
                SyncPayloads.paymentAdjustment(event), 0)
            null
        }

    // --- F7: Reembolsos (append-only, irreversibles) ---

    fun observePaymentRefunds(paymentId: String): Flow<List<PaymentRefundEntity>> =
        db.paymentRefundDao().observeForPayment(paymentId)

    /**
     * Reembolso vinculado al Pago origen (evento `payment_refund`
     * append-only): solo hasta el remanente disponible — la misma reserva
     * que las asignaciones: efectivo − aplicado − reembolsado. El dinero
     * aplicado debe liberarse antes con una operación auditada. Nunca
     * edita ni elimina el Pago; irreversible (sin reversión aprobada).
     */
    suspend fun refundPayment(
        paymentId: String,
        amount: String,
        refundedAtEpochDay: Long,
        collectionMethodId: String?,
        reason: String,
    ): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "El reembolso requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            if (payment.annulled) {
                return@withTransaction "El pago está anulado; no admite reembolsos."
            }
            if (collectionMethodId != null
                && db.collectionMethodDao().get(collectionMethodId) == null
            ) return@withTransaction "Método de cobro no encontrado."
            val requested = amount.toMoney()
            if (requested.signum() <= 0) {
                return@withTransaction "El importe del reembolso debe ser positivo."
            }
            val effective = payment.effectiveAmount.toMoney()
            val applied = payment.appliedAmount.toMoney()
            val refunded = payment.refundedAmount.toMoney()
            val available = effective.subtract(applied).subtract(refunded)
            if (requested > available) {
                return@withTransaction "El reembolso excede el remanente disponible del pago."
            }
            val now = nowEpochMillis()
            val event = PaymentRefundEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                amount = QuoteCalc.format(requested), refundedAtEpochDay = refundedAtEpochDay,
                collectionMethodId = collectionMethodId, reason = reason.trim(),
                createdAt = now, updatedAt = now,
            )
            val newRefunded = refunded.add(requested)
            val newUnassigned = effective.subtract(applied).subtract(newRefunded)
            db.paymentDao().upsert(
                payment.copy(
                    refundedAmount = QuoteCalc.format(newRefunded),
                    unassignedAmount = QuoteCalc.format(newUnassigned),
                    assignmentStatus = deriveAssignmentStatus(applied, newUnassigned).name,
                    updatedAt = now,
                ),
            )
            db.paymentRefundDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_REFUND, event.id,
                SyncPayloads.paymentRefund(event), 0)
            null
        }

    /**
     * Núcleo de aplicación compartido (creación con destino y `assign`):
     * valida remanente (efectivo − aplicado − reembolsado), destino
     * receptor, mismo Cliente y unicidad de documento destino; registra
     * el evento auditado y actualiza los importes derivados de Pago y
     * destino en la misma transacción.
     * Los campos derivados NO se encolan como operación propia: el
     * servidor recalcula los mismos valores al aplicar el evento y los
     * entrega por pull (una sola fuente de verdad, sin doble escritura).
     */
    private suspend fun applyAssignLocked(
        payment: PaymentEntity,
        targetKind: PaymentTargetKind,
        targetId: String,
        targetClientId: String,
        targetStatus: String,
        targetBalance: BigDecimal,
        requested: BigDecimal,
        reason: String,
        now: Long,
    ): PaymentAssignmentEntity? {
        // Remanente reservable: efectivo − aplicado − reembolsado.
        val effective = payment.effectiveAmount.toMoney().let {
            if (it.signum() <= 0) payment.amount.toMoney() else it
        }
        val unassigned = effective.subtract(payment.appliedAmount.toMoney())
            .subtract(payment.refundedAmount.toMoney())
        if (unassigned.signum() <= 0) return null
        val currentKind = payment.assignedTargetType?.let { PaymentTargetKind.valueOf(it) }
            ?: if (payment.assignedAccountId != null) PaymentTargetKind.ACCOUNT else null
        val currentId = when (currentKind) {
            PaymentTargetKind.ACCOUNT -> payment.assignedAccountId
            PaymentTargetKind.CONSOLIDATED -> payment.assignedConsolidatedId
            null -> null
        }
        if (currentId != null && (currentKind != targetKind || currentId != targetId)) return null
        if (payment.clientId != targetClientId) return null
        if (!ConsolidatedTransitions.canReceivePayments(CollectionAccountStatus.valueOf(targetStatus))) {
            return null
        }
        val applied = requested.min(unassigned).min(targetBalance)
        if (applied.signum() <= 0) return null
        val event = PaymentAssignmentEntity(
            id = Ids.new(), userId = payment.userId, paymentId = payment.id,
            action = PaymentAssignmentAction.ASSIGN.name,
            targetType = targetKind.name,
            accountId = if (targetKind == PaymentTargetKind.ACCOUNT) targetId else null,
            consolidatedId = if (targetKind == PaymentTargetKind.CONSOLIDATED) targetId else null,
            amount = QuoteCalc.format(applied), reason = reason.trim(),
            createdAt = now, updatedAt = now,
        )
        val newApplied = payment.appliedAmount.toMoney().add(applied)
        val newUnassigned = effective.subtract(newApplied).subtract(payment.refundedAmount.toMoney())
        db.paymentDao().upsert(
            payment.copy(
                appliedAmount = QuoteCalc.format(newApplied),
                unassignedAmount = QuoteCalc.format(newUnassigned),
                assignedTargetType = targetKind.name,
                assignedAccountId = if (targetKind == PaymentTargetKind.ACCOUNT) targetId else null,
                assignedConsolidatedId = if (targetKind == PaymentTargetKind.CONSOLIDATED) targetId else null,
                assignmentStatus = deriveAssignmentStatus(newApplied, newUnassigned).name,
                updatedAt = now,
            ),
        )
        applyToTargetLocked(targetKind, targetId, applied, now)
        db.paymentAssignmentDao().upsert(event)
        sync?.enqueue(EntityTypes.PAYMENT_ASSIGNMENT, event.id,
            SyncPayloads.paymentAssignment(event), 0)
        return event
    }

    /** Suma dinero aplicado al destino con estado derivado (misma transacción). */
    private suspend fun applyToTargetLocked(
        targetKind: PaymentTargetKind,
        targetId: String,
        amount: BigDecimal,
        now: Long,
    ) {
        when (targetKind) {
            PaymentTargetKind.ACCOUNT -> {
                val t = db.collectionAccountDao().get(targetId) ?: return
                val applied = t.appliedAmount.toMoney().add(amount)
                db.collectionAccountDao().upsert(
                    t.copy(
                        appliedAmount = QuoteCalc.format(applied),
                        balance = QuoteCalc.format(t.net.toMoney().subtract(applied)),
                        status = deriveCollectionStatus(
                            t.status == CollectionAccountStatus.CANCELADA.name,
                            applied, t.net.toMoney(),
                        ).name,
                        updatedAt = now,
                    ),
                )
            }
            PaymentTargetKind.CONSOLIDATED -> {
                val t = db.consolidatedCollectionDao().get(targetId) ?: return
                val applied = t.appliedAmount.toMoney().add(amount)
                db.consolidatedCollectionDao().upsert(
                    t.copy(
                        appliedAmount = QuoteCalc.format(applied),
                        balance = QuoteCalc.format(t.totalAmount.toMoney().subtract(applied)),
                        status = deriveCollectionStatus(
                            t.status == CollectionAccountStatus.CANCELADA.name,
                            applied, t.totalAmount.toMoney(),
                        ).name,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    /** Retira dinero aplicado del destino con estado derivado (misma transacción). */
    private suspend fun releaseTargetLocked(
        targetKind: PaymentTargetKind,
        targetId: String,
        amount: BigDecimal,
        now: Long,
    ) {
        when (targetKind) {
            PaymentTargetKind.ACCOUNT -> {
                val t = db.collectionAccountDao().get(targetId) ?: return
                val applied = t.appliedAmount.toMoney().subtract(amount)
                db.collectionAccountDao().upsert(
                    t.copy(
                        appliedAmount = QuoteCalc.format(applied),
                        balance = QuoteCalc.format(t.net.toMoney().subtract(applied)),
                        status = deriveCollectionStatus(
                            t.status == CollectionAccountStatus.CANCELADA.name,
                            applied, t.net.toMoney(),
                        ).name,
                        updatedAt = now,
                    ),
                )
            }
            PaymentTargetKind.CONSOLIDATED -> {
                val t = db.consolidatedCollectionDao().get(targetId) ?: return
                val applied = t.appliedAmount.toMoney().subtract(amount)
                db.consolidatedCollectionDao().upsert(
                    t.copy(
                        appliedAmount = QuoteCalc.format(applied),
                        balance = QuoteCalc.format(t.totalAmount.toMoney().subtract(applied)),
                        status = deriveCollectionStatus(
                            t.status == CollectionAccountStatus.CANCELADA.name,
                            applied, t.totalAmount.toMoney(),
                        ).name,
                        updatedAt = now,
                    ),
                )
            }
        }
    }

    // ---------- Inventario y Compras (F8) ----------

    fun observeMaterials(userId: String): Flow<List<MaterialEntity>> =
        db.materialDao().observeForUser(userId)

    fun observeActiveMaterials(userId: String): Flow<List<MaterialEntity>> =
        db.materialDao().observeActiveForUser(userId)

    fun observeMaterial(id: String): Flow<MaterialEntity?> = db.materialDao().observeOne(id)

    fun observePurchases(userId: String): Flow<List<PurchaseEntity>> =
        db.purchaseDao().observeForUser(userId)

    fun observePurchase(id: String): Flow<PurchaseEntity?> = db.purchaseDao().observeOne(id)

    fun observePurchaseItems(id: String): Flow<List<PurchaseItemEntity>> =
        db.purchaseItemDao().observeForPurchase(id)

    fun observePurchaseMovements(id: String): Flow<List<InventoryMovementEntity>> =
        db.inventoryMovementDao().observeForPurchase(id)

    fun observeMovementsForMaterial(materialId: String): Flow<List<InventoryMovementEntity>> =
        db.inventoryMovementDao().observeForMaterial(materialId)

    fun observeMovementsForJob(jobId: String): Flow<List<InventoryMovementEntity>> =
        db.inventoryMovementDao().observeForJob(jobId)

    fun observeAllMovements(userId: String): Flow<List<InventoryMovementEntity>> =
        db.inventoryMovementDao().observeForUser(userId)

    /**
     * Saldo mostrado por Material: espejo `inventory_stock` (autoritativo,
     * derivado por el servidor) + movimientos locales aún pendientes +
     * efecto pendiente de las Compras no sincronizadas — calculado como la
     * diferencia entre sus líneas actuales y el último payload que el
     * servidor aplicó (reconstruido desde la cola durable). El stock
     * negativo es visible y nunca bloquea.
     *
     * Un movimiento cuya última operación fue rechazada (PAUSED o
     * reconocida como perdida) NO se suma al stock mostrado: su efecto se
     * expone aparte en `rejectedNew`/`rejectedUsed` para que el usuario lo
     * vea sin que se presente como aplicado (regla append-only aprobada).
     */
    fun observeInventory(userId: String): Flow<List<MaterialStockView>> =
        kotlinx.coroutines.flow.combine(
            db.materialDao().observeForUser(userId),
            db.materialStockDao().observeAll(),
            db.inventoryMovementDao().observeForUser(userId),
            db.purchaseDao().observePendingForUser(userId),
            db.syncDao().observeLatestOpStates(),
        ) { materials, mirrors, movements, pendingPurchases, opStates ->
            val purchaseDelta = pendingPurchaseDeltas(pendingPurchases)
            val latestOp = opStates.associateBy { it.entityType to it.entityId }
            materials.map { m ->
                val mirror = mirrors.firstOrNull { it.materialId == m.id }
                var sNew = (mirror?.stockNew ?: "0").toQty()
                var sUsed = (mirror?.stockUsed ?: "0").toQty()
                var rNew = BigDecimal.ZERO
                var rUsed = BigDecimal.ZERO
                for (mov in movements) {
                    if (mov.materialId != m.id || !mov.pendingSync) continue
                    val op = latestOp[EntityTypes.INVENTORY_MOVEMENT to mov.id]
                    if (op != null && op.isRejectedEffect()) {
                        if (mov.condition == MaterialCondition.NUEVO.name) {
                            rNew += mov.quantity.toQty()
                        } else {
                            rUsed += mov.quantity.toQty()
                        }
                        continue
                    }
                    val q = mov.quantity.toQty()
                    if (mov.movementType == MovementType.PURCHASE_IN.name
                        || mov.movementType == MovementType.ADJUSTMENT_IN.name
                    ) {
                        if (mov.condition == MaterialCondition.NUEVO.name) sNew += q else sUsed += q
                    } else {
                        if (mov.condition == MaterialCondition.NUEVO.name) sNew -= q else sUsed -= q
                    }
                }
                purchaseDelta[m.id]?.let { (dNew, dUsed) -> sNew += dNew; sUsed += dUsed }
                MaterialStockView(m, sNew, sUsed, rNew, rUsed)
            }
        }

    /** Deltas locales por materialId de las Compras aún no aplicadas. */
    private suspend fun pendingPurchaseDeltas(
        pending: List<PurchaseEntity>,
    ): Map<String, Pair<BigDecimal, BigDecimal>> {
        val acc = mutableMapOf<Pair<String, String>, BigDecimal>()
        fun add(materialId: String, condition: String, qty: BigDecimal) {
            val key = materialId to (if (condition == MaterialCondition.USADO.name) "USADO" else "NUEVO")
            acc[key] = (acc[key] ?: BigDecimal.ZERO).add(qty)
        }
        for (p in pending) {
            val baseJson = db.syncDao().lastAppliedPayload(EntityTypes.PURCHASE, p.id)
                ?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            if (baseJson?.str("status") == "CONFIRMADA") {
                baseJson["items"]?.jsonArray?.forEach { el ->
                    val it = el.jsonObject
                    add(it.str("materialId") ?: "", it.str("condition") ?: "NUEVO",
                        (it.str("quantity") ?: "0").toQty().negate())
                }
            }
            if (p.status == PurchaseStatus.CONFIRMADA.name) {
                for (it in db.purchaseItemDao().forPurchase(p.id)) {
                    add(it.materialId, it.condition, it.quantity.toQty())
                }
            }
        }
        val out = mutableMapOf<String, Pair<BigDecimal, BigDecimal>>()
        for ((key, q) in acc) {
            val (m, c) = key
            val cur = out[m] ?: (BigDecimal.ZERO to BigDecimal.ZERO)
            out[m] = if (c == "NUEVO") cur.first + q to cur.second else cur.first to cur.second + q
        }
        return out
    }

    /** Catálogo global: nombre y unidad requeridos, descripción opcional. */
    suspend fun saveMaterial(input: MaterialInput, existingId: String? = null): String? {
        validateMaterial(input.name, input.unit)?.let { return null }
        val user = db.userDao().get() ?: return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.materialDao().get(it) }
        val material = MaterialEntity(
            id = existing?.id ?: Ids.new(),
            userId = user.id,
            name = input.name.trim(),
            unit = input.unit.trim(),
            description = input.description.trim(),
            archived = existing?.archived ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.materialDao().upsert(material)
            sync?.enqueue(EntityTypes.MATERIAL, material.id,
                SyncPayloads.material(material), existing?.serverVersion ?: 0)
        }
        return material.id
    }

    /** Archivado de catálogo: solo cambia visibilidad, nunca toca el stock. */
    suspend fun setMaterialArchived(id: String, archived: Boolean) {
        val existing = db.materialDao().get(id) ?: return
        val updated = existing.copy(archived = archived, updatedAt = nowEpochMillis(), pendingSync = true)
        db.withTransaction {
            db.materialDao().upsert(updated)
            sync?.enqueue(EntityTypes.MATERIAL, id, SyncPayloads.material(updated), existing.serverVersion)
        }
    }

    /** Número provisional único `COM-PEND-XXXX` entre las Compras del usuario. */
    private suspend fun uniquePurchaseProvisionalNumber(): String {
        var candidate = newProvisionalNumber("COM")
        while (db.purchaseDao().provisionalNumberExists(candidate)) {
            candidate = newProvisionalNumber("COM")
        }
        return candidate
    }

    /**
     * Crea o edita una Compra (docs/03 §6). Nace en BORRADOR con
     * `COM-PEND-XXXX` (o ya CONFIRMADA si el usuario confirmó antes de
     * sincronizar — el ciclo de ops lo refleja). En CONFIRMADA la edición
     * es diferencial: el servidor recalcula totales, fija la moneda global
     * del titular y deriva los movimientos con trazabilidad. Devuelve null
     * o el motivo del rechazo.
     */
    suspend fun savePurchase(input: PurchaseInput, existingId: String? = null,
                             confirm: Boolean = false): String? {
        validatePurchase(input, confirm)?.let { return it }
        val user = db.userDao().get() ?: return "Sin usuario local."
        val now = nowEpochMillis()
        val existing = existingId?.let { db.purchaseDao().get(it) }
        if (existing != null) {
            val cur = PurchaseStatus.valueOf(existing.status)
            if (cur == PurchaseStatus.ANULADA) return "La compra está anulada (terminal)."
            if (confirm && cur == PurchaseStatus.CONFIRMADA) {
                return "La compra ya está confirmada."
            }
            // Una Compra CONFIRMADA no puede quedar sin líneas (mínimo
            // aprobado del estado); el servidor lo rechaza igual que la
            // confirmación. El borrador sí admite guardarse sin líneas.
            if (cur == PurchaseStatus.CONFIRMADA && input.items.isEmpty()) {
                return "Una compra confirmada requiere al menos una línea."
            }
        }
        val materialNames = mutableMapOf<String, String>()
        for (it in input.items) {
            val m = db.materialDao().get(it.materialId) ?: return "Material no encontrado."
            materialNames[it.materialId] = m.name
        }
        val items = input.items.mapIndexed { i, it ->
            val lineTotal = QuoteCalc.format(it.quantity.toQty().multiply(it.unitPrice.toMoney()))
            PurchaseItemEntity(
                id = Ids.new(), purchaseId = existing?.id ?: "",
                position = i + 1, materialId = it.materialId,
                materialNameSnapshot = materialNames[it.materialId] ?: "",
                quantity = normDecimal(it.quantity), unit = it.unit.trim(),
                unitPrice = QuoteCalc.format(it.unitPrice.toMoney()),
                lineTotal = lineTotal, condition = it.condition.name,
                createdAt = now,
            )
        }
        val total = items.fold(BigDecimal.ZERO) { a, it -> a.add(it.lineTotal.toMoney()) }
        val status = when {
            existing == null -> PurchaseStatus.BORRADOR
            else -> if (confirm) PurchaseStatus.CONFIRMADA else PurchaseStatus.valueOf(existing.status)
        }
        val purchase = PurchaseEntity(
            id = existing?.id ?: Ids.new(),
            userId = user.id,
            provisionalNumber = existing?.provisionalNumber ?: uniquePurchaseProvisionalNumber(),
            status = status.name,
            purchaseDateEpochDay = input.purchaseDateEpochDay,
            supplierPlace = input.supplierPlace.trim(),
            reference = input.reference.trim(),
            notes = input.notes.trim(),
            currency = user.mainCurrency ?: "COP",
            totalAmount = QuoteCalc.format(total),
            archived = existing?.archived ?: false,
            officialNumber = existing?.officialNumber,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        val itemsWithId = items.map { it.copy(purchaseId = purchase.id) }
        db.withTransaction {
            db.purchaseDao().upsert(purchase)
            db.purchaseItemDao().deleteForPurchase(purchase.id)
            db.purchaseItemDao().upsertAll(itemsWithId)
            sync?.enqueue(EntityTypes.PURCHASE, purchase.id,
                SyncPayloads.purchase(purchase, itemsWithId), existing?.serverVersion ?: 0)
        }
        if (existing == null && confirm) {
            // "Guardar y confirmar" en el alta: el documento nace en
            // BORRADOR y la confirmación se encola como segunda operación
            // de la misma entidad; el push la envía tras aplicar el alta
            // con la base rebasada. La intención nunca se descarta.
            confirmPurchase(purchase.id)?.let { return it }
        }
        return null
    }

    /** Confirma una compra en BORRADOR (requiere al menos una línea). */
    suspend fun confirmPurchase(id: String): String? {
        val purchase = db.purchaseDao().get(id) ?: return "Compra no encontrada."
        val items = db.purchaseItemDao().forPurchase(id)
        if (!PurchaseTransitions.canTransition(
                PurchaseStatus.valueOf(purchase.status), PurchaseStatus.CONFIRMADA)
            || !PurchaseTransitions.canConfirm(PurchaseStatus.valueOf(purchase.status), items.size)
        ) {
            return "Solo una compra en borrador con al menos una línea puede confirmarse."
        }
        val updated = purchase.copy(
            status = PurchaseStatus.CONFIRMADA.name,
            updatedAt = nowEpochMillis(), pendingSync = true,
        )
        db.withTransaction {
            db.purchaseDao().upsert(updated)
            sync?.enqueue(EntityTypes.PURCHASE, id,
                SyncPayloads.purchase(updated, items), purchase.serverVersion)
        }
        return null
    }

    /**
     * Anula la Compra: terminal e irreversible. Sobre una CONFIRMADA el
     * servidor registra los movimientos inversos en la misma transacción;
     * sobre BORRADOR no hay efectos de stock que revertir.
     */
    suspend fun annulPurchase(id: String): String? {
        val purchase = db.purchaseDao().get(id) ?: return "Compra no encontrada."
        // Terminalidad explícita: canTransition admite mismo-estado para
        // ediciones, pero la anulación no se puede repetir.
        if (purchase.status == PurchaseStatus.ANULADA.name) {
            return "La compra ya está anulada (terminal)."
        }
        val items = db.purchaseItemDao().forPurchase(id)
        val updated = purchase.copy(
            status = PurchaseStatus.ANULADA.name,
            updatedAt = nowEpochMillis(), pendingSync = true,
        )
        db.withTransaction {
            db.purchaseDao().upsert(updated)
            sync?.enqueue(EntityTypes.PURCHASE, id,
                SyncPayloads.purchase(updated, items), purchase.serverVersion)
        }
        return null
    }

    /** Archivado separado de la anulación: jamás revierte movimientos. */
    suspend fun setPurchaseArchived(id: String, archived: Boolean) {
        val existing = db.purchaseDao().get(id) ?: return
        val items = db.purchaseItemDao().forPurchase(id)
        val updated = existing.copy(archived = archived, updatedAt = nowEpochMillis(), pendingSync = true)
        db.withTransaction {
            db.purchaseDao().upsert(updated)
            sync?.enqueue(EntityTypes.PURCHASE, id,
                SyncPayloads.purchase(updated, items), existing.serverVersion)
        }
    }

    /**
     * Movimiento manual explicable (append-only): ajuste de entrada/salida
     * o entrada por SOBRANTE con referencia opcional a un Trabajo. Requiere
     * nota; el stock negativo resultante está permitido. Devuelve null o
     * el motivo del rechazo.
     */
    suspend fun saveInventoryMovement(input: InventoryMovementInput): String? {
        val material = db.materialDao().get(input.materialId) ?: return "Material no encontrado."
        if (material.archived) return "El material está archivado."
        if (!isPositiveQuantity(input.quantity)) return "El movimiento requiere una cantidad positiva."
        if (input.note.isBlank()) return "El movimiento manual requiere una nota explicativa."
        val (type, origin) = input.movementType to input.origin
        val validCombo = when (origin) {
            MovementOrigin.SOBRANTE -> type == MovementType.ADJUSTMENT_IN
            MovementOrigin.AJUSTE -> type == MovementType.ADJUSTMENT_IN || type == MovementType.ADJUSTMENT_OUT
            else -> false
        }
        if (!validCombo) return "Combinación tipo/origen no permitida para un movimiento manual."
        if (input.jobId != null && origin != MovementOrigin.SOBRANTE) {
            return "Solo el sobrante puede referenciar un Trabajo."
        }
        if (input.jobId != null && db.jobDao().get(input.jobId) == null) {
            return "El trabajo referenciado no existe."
        }
        val user = db.userDao().get() ?: return "Sin usuario local."
        val now = nowEpochMillis()
        val mov = InventoryMovementEntity(
            id = Ids.new(), userId = user.id,
            materialId = input.materialId, condition = input.condition.name,
            movementType = type.name, origin = origin.name,
            quantity = normDecimal(input.quantity),
            unit = input.unit.ifBlank { material.unit },
            unitCost = input.unitCost?.ifBlank { null }?.let { QuoteCalc.format(it.toMoney()) },
            jobId = input.jobId, note = input.note.trim(),
            occurredAt = now, createdAt = now, updatedAt = now,
        )
        db.withTransaction {
            db.inventoryMovementDao().upsert(mov)
            sync?.enqueue(EntityTypes.INVENTORY_MOVEMENT, mov.id,
                SyncPayloads.inventoryMovement(mov), 0)
        }
        return null
    }
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
    val title: String = "",
    val issueDateEpochDay: Long,
    val validityDays: Int,
    val currency: String,
    val notes: String = "",
    val lines: List<QuoteLineInput>,
    val retentions: List<RetentionInput>,
)

// ---------- Entradas de dominio para F4 ----------

data class JobInput(
    val clientId: String,
    val profileId: String,
    val startDateEpochDay: Long? = null,
    val estimatedDateEpochDay: Long? = null,
    val executionAddress: String = "",
    val notes: String = "",
    val quoteIds: List<String> = emptyList(),
)

data class JobActivityInput(
    val jobId: String,
    val description: String,
    val status: com.cotixgo.app.core.JobActivityStatus =
        com.cotixgo.app.core.JobActivityStatus.PENDIENTE,
    val notes: String = "",
)

data class JobMaterialInput(
    val jobId: String,
    val description: String,
    val origin: com.cotixgo.app.core.MaterialOrigin,
    val quantity: String = "",
    val unit: String = "",
    val notes: String = "",
    // F8: vínculo opcional al catálogo de Materiales — solo al crear el
    // registro (nunca al editar un histórico descriptivo).
    val materialId: String? = null,
    val condition: com.cotixgo.app.core.MaterialCondition? = null,
)

// ---------- Entradas de dominio para F5 ----------

data class ReportInput(
    val clientId: String,
    val profileId: String,
    val title: String,
    val destination: com.cotixgo.app.core.ReportDestination =
        com.cotixgo.app.core.ReportDestination.CLIENTE,
    val content: String = "",
    val jobIds: List<String> = emptyList(),
)

// ---------- Entradas de dominio para F6 ----------

data class CollectionItemInput(
    val conceptType: ConceptType,
    val description: String,
    val quantity: String,
    val unit: String = "",
    val unitPrice: String,
)

/**
 * Entrada de Cuenta de Cobro: Trabajos O Cotizaciones (nunca ambos),
 * retenciones configuradas en F2 como [RetentionInput] y campos opcionales
 * (`dueDateEpochDay`, `conditions`).
 */
data class CollectionAccountInput(
    val clientId: String,
    val profileId: String,
    val currency: String,
    val items: List<CollectionItemInput>,
    val retentions: List<RetentionInput> = emptyList(),
    val jobIds: List<String> = emptyList(),
    val quoteIds: List<String> = emptyList(),
    val dueDateEpochDay: Long? = null,
    val conditions: String = "",
)

data class CollectionMethodInput(
    val name: String,
    val detail: String = "",
)

/**
 * Entrada de Pago: `accountId` o `consolidatedId` opcionales (mutuamente
 * excluyentes) — si se indica destino, la aplicación inicial se registra
 * como evento `assign` auditado en la misma transacción; el excedente
 * queda UNASSIGNED en el mismo Pago.
 */
data class PaymentInput(
    val clientId: String,
    val amount: String,
    val paidAtEpochDay: Long,
    val collectionMethodId: String? = null,
    val reference: String = "",
    val notes: String = "",
    val accountId: String? = null,
    val consolidatedId: String? = null,
    val initialAssignReason: String = "",
)

/**
 * Entrada de Consolidado F7: al menos dos Cuentas del mismo Cliente y
 * moneda, no canceladas y con saldo pendiente > 0. Los Perfiles pueden
 * diferir; cada Cuenta conserva sus snapshots.
 */
data class ConsolidatedInput(
    val clientId: String,
    val accountIds: List<String>,
    val currency: String,
)

// ---------- Entradas de dominio para F8 ----------

/** Material del catálogo global: nombre y unidad requeridos. */
data class MaterialInput(
    val name: String,
    val unit: String,
    val description: String = "",
)

/** Línea de Compra: Material, cantidad, unidad, precio unitario y condición de stock. */
data class PurchaseLineInput(
    val materialId: String,
    val quantity: String,
    val unit: String,
    val unitPrice: String,
    val condition: com.cotixgo.app.core.MaterialCondition =
        com.cotixgo.app.core.MaterialCondition.NUEVO,
)

/**
 * Entrada de Compra (docs/03 §6): fecha requerida; proveedor/lugar y
 * referencia opcionales; al menos una línea para confirmar. El total y la
 * moneda los deriva el servidor.
 */
data class PurchaseInput(
    val purchaseDateEpochDay: Long,
    val items: List<PurchaseLineInput>,
    val supplierPlace: String = "",
    val reference: String = "",
    val notes: String = "",
)

/** Mínimos de la Compra validados localmente (el servidor los revalida). */
fun validatePurchase(input: PurchaseInput, confirm: Boolean): String? = when {
    input.purchaseDateEpochDay <= 0 -> "La fecha de la compra es obligatoria."
    confirm && input.items.isEmpty() -> "Confirmar la compra requiere al menos una línea."
    input.items.any { it.materialId.isBlank() } -> "Cada línea de la compra requiere un material."
    input.items.any { !isPositiveQuantity(it.quantity) } ->
        "Cada línea de la compra requiere una cantidad positiva."
    input.items.any { it.unit.isBlank() } -> "Cada línea de la compra requiere una unidad."
    input.items.any { it.unitPrice.isBlank()
        || runCatching { it.unitPrice.toMoney() }.getOrNull()?.signum()?.let { s -> s < 0 } != false } ->
        "Cada línea requiere un precio unitario válido (≥ 0)."
    else -> null
}

/**
 * Movimiento manual de inventario (append-only): AJUSTE con entrada o
 * salida, o SOBRANTE como entrada con referencia opcional a un Trabajo.
 */
data class InventoryMovementInput(
    val materialId: String,
    val condition: com.cotixgo.app.core.MaterialCondition,
    val movementType: com.cotixgo.app.core.MovementType,
    val origin: com.cotixgo.app.core.MovementOrigin,
    val quantity: String,
    val unit: String = "",
    val unitCost: String? = null,
    val jobId: String? = null,
    val note: String = "",
)

/** Saldo mostrado por Material: espejo del servidor + efectos locales pendientes. */
data class MaterialStockView(
    val material: MaterialEntity,
    val stockNew: java.math.BigDecimal,
    val stockUsed: java.math.BigDecimal,
    /** Efecto de movimientos rechazados por el servidor (nunca sumado). */
    val rejectedNew: java.math.BigDecimal = java.math.BigDecimal.ZERO,
    val rejectedUsed: java.math.BigDecimal = java.math.BigDecimal.ZERO,
)

/** Efecto agregado sobre un destino de cobro (importes con signo). */
data class DestEffect(
    val pending: java.math.BigDecimal,
    val rejected: java.math.BigDecimal,
)

/**
 * Estado de sincronización de la última operación de una entidad: una
 * operación pausada o reconocida como rechazada cuenta su efecto como
 * «rechazado», nunca como pendiente ni como confirmado.
 */
internal fun LatestOpState?.isRejectedEffect(): Boolean =
    this != null && (
        state == "PAUSED" ||
            (state == "DONE" && resultCode in setOf(
                Rejections.RES_ACKNOWLEDGED,
                Rejections.RES_PREREQ_DISCARDED,
                Rejections.RES_DISCARDED,
                Rejections.RES_CASCADE_DISCARDED,
            ))
        )

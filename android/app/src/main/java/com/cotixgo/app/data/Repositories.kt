package com.cotixgo.app.data

import androidx.room.withTransaction
import com.cotixgo.app.core.CalcLine
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.CollectionAccountStatus
import com.cotixgo.app.core.CollectionAccountTransitions
import com.cotixgo.app.core.ConceptType
import com.cotixgo.app.core.Ids
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.JobTransitions
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.PaymentAssignmentAction
import com.cotixgo.app.core.PaymentAssignmentStatus
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
import com.cotixgo.app.core.newProvisionalNumber
import com.cotixgo.app.core.nowEpochMillis
import com.cotixgo.app.core.toMoney
import com.cotixgo.app.core.toQty
import com.cotixgo.app.core.validateCollectionMethod
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
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

    suspend fun saveJobMaterial(input: JobMaterialInput, existingId: String? = null): String? {
        if (!jobEditable(input.jobId)) return null
        val now = nowEpochMillis()
        val existing = existingId?.let { db.jobMaterialDao().get(it) }
        val material = JobMaterialEntity(
            id = existing?.id ?: Ids.new(),
            jobId = input.jobId,
            description = input.description.trim(),
            origin = input.origin.name,
            quantity = input.quantity.trim(),
            unit = input.unit.trim(),
            notes = input.notes.trim(),
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = existing?.serverVersion ?: 0,
        )
        db.withTransaction {
            db.jobMaterialDao().upsert(material)
            sync?.enqueue(EntityTypes.JOB_MATERIAL, material.id,
                SyncPayloads.jobMaterial(material), existing?.serverVersion ?: 0)
        }
        return material.id
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
     * Registra un Pago recibido: contra una Cuenta individual o sin
     * asignar vinculado al Cliente (docs/03 §6). Si se indica `accountId`,
     * la aplicación inicial se registra como el primer evento
     * `payment_assignment` auditado en la misma transacción — una sola
     * vía de aplicación, igual que cualquier `assign` posterior.
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
        val target = input.accountId?.let { db.collectionAccountDao().get(it) }
        if (input.accountId != null) {
            if (target == null || target.clientId != client.id) return null
            val st = CollectionAccountStatus.valueOf(target.status)
            if (!CollectionAccountTransitions.canReceivePayments(st)
                || target.balance.toMoney().signum() <= 0
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
            assignedAccountId = null,
            assignmentStatus = PaymentAssignmentStatus.UNASSIGNED.name,
            createdAt = now,
            updatedAt = now,
            pendingSync = true,
            serverVersion = 0,
        )
        db.withTransaction {
            db.paymentDao().upsert(payment)
            sync?.enqueue(EntityTypes.PAYMENT, payment.id, SyncPayloads.payment(payment), 0)
            if (target != null) {
                applyAssignLocked(
                    payment, target, amount,
                    input.initialAssignReason.ifBlank { "Aplicación inicial del pago" },
                    now,
                )
            }
        }
        return payment.id
    }

    /**
     * Asigna dinero sin asignar de un Pago a una Cuenta: aplica solo lo
     * necesario (min de lo pedido, el remanente y el saldo); el excedente
     * queda UNASSIGNED en el mismo Pago. Devuelve null si aplicó o el
     * motivo del rechazo.
     */
    suspend fun assignPayment(paymentId: String, accountId: String, requested: String, reason: String): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La asignación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            val account = db.collectionAccountDao().get(accountId) ?: return@withTransaction "Cuenta no encontrada."
            if (applyAssignLocked(payment, account, requested.toMoney(), reason, nowEpochMillis()) == null) {
                return@withTransaction "La asignación no aplicó: sin remanente, cuenta cerrada o destino inválido."
            }
            null
        }

    /**
     * Retira la aplicación vigente del Pago (con motivo): el dinero vuelve
     * a UNASSIGNED dentro del mismo Pago y el saldo de la Cuenta se
     * restaura. Evento auditado append-only; no edita ni elimina nada.
     */
    suspend fun unassignPayment(paymentId: String, reason: String): String? =
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La operación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            val accountId = payment.assignedAccountId
                ?: return@withTransaction "El pago no tiene dinero aplicado."
            val released = payment.appliedAmount.toMoney()
            if (released.signum() <= 0) return@withTransaction "El pago no tiene dinero aplicado."
            val account = db.collectionAccountDao().get(accountId)
                ?: return@withTransaction "Cuenta destino no encontrada."
            val now = nowEpochMillis()
            val event = PaymentAssignmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                action = PaymentAssignmentAction.UNASSIGN.name, accountId = accountId,
                amount = QuoteCalc.format(released), reason = reason.trim(),
                createdAt = now, updatedAt = now,
            )
            val payUnassigned = payment.amount.toMoney()
            db.paymentDao().upsert(
                payment.copy(
                    appliedAmount = "0.00", unassignedAmount = QuoteCalc.format(payUnassigned),
                    assignedAccountId = null,
                    assignmentStatus = PaymentAssignmentStatus.UNASSIGNED.name,
                    updatedAt = now,
                ),
            )
            val accApplied = account.appliedAmount.toMoney().subtract(released)
            db.collectionAccountDao().upsert(
                account.copy(
                    appliedAmount = QuoteCalc.format(accApplied),
                    balance = QuoteCalc.format(account.net.toMoney().subtract(accApplied)),
                    status = deriveCollectionStatus(false, accApplied, account.net.toMoney()).name,
                    updatedAt = now,
                ),
            )
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
        db.withTransaction {
            if (reason.isBlank()) return@withTransaction "La reasignación requiere un motivo."
            val payment = db.paymentDao().get(paymentId) ?: return@withTransaction "Pago no encontrado."
            val fromId = payment.assignedAccountId
                ?: return@withTransaction "El pago no tiene dinero aplicado; usa asignar."
            if (fromId == toAccountId) return@withTransaction "El pago ya está aplicado a esa cuenta."
            val released = payment.appliedAmount.toMoney()
            val from = db.collectionAccountDao().get(fromId)
                ?: return@withTransaction "Cuenta origen no encontrada."
            val to = db.collectionAccountDao().get(toAccountId)
                ?: return@withTransaction "Cuenta destino no encontrada."
            if (to.clientId != payment.clientId) {
                return@withTransaction "La cuenta destino pertenece a otro cliente."
            }
            if (!CollectionAccountTransitions.canReceivePayments(
                    CollectionAccountStatus.valueOf(to.status),
                )
            ) {
                return@withTransaction "La cuenta destino no puede recibir pagos."
            }
            val toBalance = to.balance.toMoney()
            val appliedTo = released.min(toBalance)
            if (appliedTo.signum() <= 0) {
                return@withTransaction "La cuenta destino no tiene saldo pendiente."
            }
            val now = nowEpochMillis()
            val event = PaymentAssignmentEntity(
                id = Ids.new(), userId = payment.userId, paymentId = payment.id,
                action = PaymentAssignmentAction.REASSIGN.name, accountId = toAccountId,
                fromAccountId = fromId, amount = QuoteCalc.format(appliedTo),
                releasedAmount = QuoteCalc.format(released), reason = reason.trim(),
                createdAt = now, updatedAt = now,
            )
            val newApplied = appliedTo
            val newUnassigned = payment.amount.toMoney().subtract(newApplied)
            db.paymentDao().upsert(
                payment.copy(
                    appliedAmount = QuoteCalc.format(newApplied),
                    unassignedAmount = QuoteCalc.format(newUnassigned),
                    assignedAccountId = if (newApplied.signum() > 0) toAccountId else null,
                    assignmentStatus = deriveAssignmentStatus(newApplied, newUnassigned).name,
                    updatedAt = now,
                ),
            )
            val fromApplied = from.appliedAmount.toMoney().subtract(released)
            db.collectionAccountDao().upsert(
                from.copy(
                    appliedAmount = QuoteCalc.format(fromApplied),
                    balance = QuoteCalc.format(from.net.toMoney().subtract(fromApplied)),
                    status = deriveCollectionStatus(false, fromApplied, from.net.toMoney()).name,
                    updatedAt = now,
                ),
            )
            val toApplied = to.appliedAmount.toMoney().add(appliedTo)
            db.collectionAccountDao().upsert(
                to.copy(
                    appliedAmount = QuoteCalc.format(toApplied),
                    balance = QuoteCalc.format(to.net.toMoney().subtract(toApplied)),
                    status = deriveCollectionStatus(false, toApplied, to.net.toMoney()).name,
                    updatedAt = now,
                ),
            )
            db.paymentAssignmentDao().upsert(event)
            sync?.enqueue(EntityTypes.PAYMENT_ASSIGNMENT, event.id,
                SyncPayloads.paymentAssignment(event), 0)
            null
        }

    /**
     * Núcleo de aplicación compartido (creación con destino y `assign`):
     * valida remanente, cuenta receptora, mismo Cliente y unicidad de
     * documento destino; registra el evento auditado y actualiza los
     * importes derivados de Pago y Cuenta en la misma transacción.
     * Los campos derivados NO se encolan como operación propia: el
     * servidor recalcula los mismos valores al aplicar el evento y los
     * entrega por pull (una sola fuente de verdad, sin doble escritura).
     */
    private suspend fun applyAssignLocked(
        payment: PaymentEntity,
        account: CollectionAccountEntity,
        requested: BigDecimal,
        reason: String,
        now: Long,
    ): PaymentAssignmentEntity? {
        val unassigned = payment.unassignedAmount.toMoney()
        if (unassigned.signum() <= 0) return null
        if (payment.assignedAccountId != null && payment.assignedAccountId != account.id) return null
        if (payment.clientId != account.clientId) return null
        if (!CollectionAccountTransitions.canReceivePayments(
                CollectionAccountStatus.valueOf(account.status),
            )
        ) return null
        val balance = account.balance.toMoney()
        val applied = requested.min(unassigned).min(balance)
        if (applied.signum() <= 0) return null
        val event = PaymentAssignmentEntity(
            id = Ids.new(), userId = payment.userId, paymentId = payment.id,
            action = PaymentAssignmentAction.ASSIGN.name, accountId = account.id,
            amount = QuoteCalc.format(applied), reason = reason.trim(),
            createdAt = now, updatedAt = now,
        )
        val newApplied = payment.appliedAmount.toMoney().add(applied)
        val newUnassigned = payment.amount.toMoney().subtract(newApplied)
        db.paymentDao().upsert(
            payment.copy(
                appliedAmount = QuoteCalc.format(newApplied),
                unassignedAmount = QuoteCalc.format(newUnassigned),
                assignedAccountId = account.id,
                assignmentStatus = deriveAssignmentStatus(newApplied, newUnassigned).name,
                updatedAt = now,
            ),
        )
        val accApplied = account.appliedAmount.toMoney().add(applied)
        db.collectionAccountDao().upsert(
            account.copy(
                appliedAmount = QuoteCalc.format(accApplied),
                balance = QuoteCalc.format(account.net.toMoney().subtract(accApplied)),
                status = deriveCollectionStatus(
                    account.status == CollectionAccountStatus.CANCELADA.name,
                    accApplied, account.net.toMoney(),
                ).name,
                updatedAt = now,
            ),
        )
        db.paymentAssignmentDao().upsert(event)
        sync?.enqueue(EntityTypes.PAYMENT_ASSIGNMENT, event.id,
            SyncPayloads.paymentAssignment(event), 0)
        return event
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
 * Entrada de Pago: `accountId` opcional — si se indica, la aplicación
 * inicial se registra como evento `assign` auditado en la misma
 * transacción; el excedente queda UNASSIGNED en el mismo Pago.
 */
data class PaymentInput(
    val clientId: String,
    val amount: String,
    val paidAtEpochDay: Long,
    val collectionMethodId: String? = null,
    val reference: String = "",
    val notes: String = "",
    val accountId: String? = null,
    val initialAssignReason: String = "",
)

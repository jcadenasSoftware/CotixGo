package com.cotixgo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cotixgo.app.BuildConfig
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.JobActivityStatus
import com.cotixgo.app.core.JobStatus
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.CollectionAccountEntity
import com.cotixgo.app.data.CollectionAccountInput
import com.cotixgo.app.data.CollectionMethodEntity
import com.cotixgo.app.data.CollectionMethodInput
import com.cotixgo.app.data.ConsolidatedCollectionEntity
import com.cotixgo.app.data.ConsolidatedInput
import com.cotixgo.app.data.PaymentEntity
import com.cotixgo.app.data.PaymentInput
import com.cotixgo.app.data.JobActivityInput
import com.cotixgo.app.data.JobEntity
import com.cotixgo.app.data.JobInput
import com.cotixgo.app.data.JobMaterialInput
import com.cotixgo.app.data.MaterialEntity
import com.cotixgo.app.data.MaterialInput
import com.cotixgo.app.data.MaterialStockView
import com.cotixgo.app.data.InventoryMovementInput
import com.cotixgo.app.data.PurchaseEntity
import com.cotixgo.app.data.PurchaseInput
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.ReportEntity
import com.cotixgo.app.data.ReportInput
import com.cotixgo.app.data.RetentionConfigEntity
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.ServiceEntity
import com.cotixgo.app.data.ServiceInput
import com.cotixgo.app.data.SyncConflictEntity
import com.cotixgo.app.data.SyncOperationEntity
import com.cotixgo.app.data.SyncRepository
import com.cotixgo.app.data.UserEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(
    private val repo: AppRepository,
    private val sync: SyncRepository,
    private val auth: AuthRepository,
) : ViewModel() {

    val user: StateFlow<UserEntity?> = repo.observeUser()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val profiles: StateFlow<List<ProfileEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeProfiles(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val clients: StateFlow<List<ClientEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeClients(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // true cuando Room ya emitió el primer valor de `user` (incluso null):
    // distingue "sin usuario" de "todavía cargando" en el arranque.
    val bootstrapped: StateFlow<Boolean> = user.map { true }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val quotes: StateFlow<List<QuoteEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeQuotes(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val services: StateFlow<List<ServiceEntity>> = profiles.flatMapLatest { ps ->
        ps.firstOrNull()?.let { repo.observeServices(it.id) } ?: flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val retentions: StateFlow<List<RetentionConfigEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeRetentions(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- Estado de sincronización (F3) ----------

    val accountEmail = MutableStateFlow(sync.session.accountEmail)
    val apiBaseUrl = MutableStateFlow(sync.session.apiBaseUrl)
    val syncing = MutableStateFlow(false)
    val lastSyncMessage = MutableStateFlow<String?>(null)

    val pendingOps: StateFlow<Int> = sync.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val pausedOps: StateFlow<Int> = sync.observePausedCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val conflicts: StateFlow<List<SyncConflictEntity>> = sync.observeConflicts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val paused: StateFlow<List<SyncOperationEntity>> = sync.observePaused()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val versionConflictQuotes: StateFlow<List<QuoteEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeVersionConflictQuotes(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val versionConflictReports: StateFlow<List<ReportEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeVersionConflictReports(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ---------- Trabajos (F4) ----------

    val jobs: StateFlow<List<JobEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeJobs(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeJob(id: String) = repo.observeJob(id)
    fun observeJobActivities(id: String) = repo.observeJobActivities(id)
    fun observeJobMaterials(id: String) = repo.observeJobMaterials(id)
    fun observeJobQuotes(id: String) = repo.observeJobQuotes(id)

    fun saveJob(input: JobInput, existingId: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveJob(input, existingId)) }
    }

    fun setJobStatus(id: String, to: JobStatus, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.setJobStatus(id, to)) }
    }

    fun setJobArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setJobArchived(id, archived) }
    }

    fun saveJobActivity(input: JobActivityInput) {
        viewModelScope.launch { repo.saveJobActivity(input) }
    }

    fun setJobActivityStatus(id: String, to: JobActivityStatus) {
        viewModelScope.launch { repo.setJobActivityStatus(id, to) }
    }

    fun saveJobMaterial(input: JobMaterialInput) {
        viewModelScope.launch { repo.saveJobMaterial(input) }
    }

    fun linkQuoteToJob(jobId: String, quoteId: String) {
        viewModelScope.launch { repo.linkQuoteToJob(jobId, quoteId) }
    }

    // ---------- Informes de Trabajo (F5) ----------

    val reports: StateFlow<List<ReportEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeReports(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeReport(id: String) = repo.observeReport(id)
    fun observeReportJobs(reportId: String) = repo.observeReportJobs(reportId)
    fun observeReportVersions(reportId: String) = repo.observeReportVersions(reportId)
    fun observeReportsForJob(jobId: String) = repo.observeReportsForJob(jobId)

    fun buildReportContent(jobIds: List<String>, onDone: (String) -> Unit) {
        viewModelScope.launch { onDone(repo.buildReportContent(jobIds)) }
    }

    suspend fun getReportSnapshot(id: String) = repo.getReportSnapshot(id)

    fun saveReport(input: ReportInput, existingId: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveReport(input, existingId)) }
    }

    fun linkJobToReport(reportId: String, jobId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.linkJobToReport(reportId, jobId)) }
    }

    fun emitReport(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.emitReport(id)) }
    }

    fun correctReport(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.correctReport(id)) }
    }

    fun setReportArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setReportArchived(id, archived) }
    }

    fun resolveReportVersions(reportId: String, keepVersionRowId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(sync.resolveReportVersions(reportId, keepVersionRowId))
            syncNow()
        }
    }

    // ---------- Cuentas de Cobro, Métodos y Pagos (F6) ----------

    val collectionAccounts: StateFlow<List<CollectionAccountEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeCollectionAccounts(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val collectionMethods: StateFlow<List<CollectionMethodEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeCollectionMethods(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val payments: StateFlow<List<PaymentEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observePayments(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeCollectionAccount(id: String) = repo.observeCollectionAccount(id)
    fun observeCollectionAccountItems(id: String) = repo.observeCollectionAccountItems(id)
    fun observeCollectionAccountJobs(id: String) = repo.observeCollectionAccountJobs(id)
    fun observeCollectionAccountQuotes(id: String) = repo.observeCollectionAccountQuotes(id)
    fun observeAllCollectionAccountJobs() = repo.observeAllCollectionAccountJobs()
    fun observeAllCollectionAccountQuotes() = repo.observeAllCollectionAccountQuotes()
    fun observePaymentsForAccount(id: String) = repo.observePaymentsForAccount(id)
    fun observeAccountAssignments(id: String) = repo.observeAccountAssignments(id)
    fun observePayment(id: String) = repo.observePayment(id)
    fun observePaymentAssignments(paymentId: String) = repo.observePaymentAssignments(paymentId)

    fun saveCollectionAccount(input: CollectionAccountInput, existingId: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveCollectionAccount(input, existingId)) }
    }

    fun cancelCollectionAccount(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.cancelCollectionAccount(id)) }
    }

    fun setCollectionAccountArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setCollectionAccountArchived(id, archived) }
    }

    fun saveCollectionMethod(input: CollectionMethodInput, existingId: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.saveCollectionMethod(input, existingId) != null) }
    }

    fun setCollectionMethodArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setCollectionMethodArchived(id, archived) }
    }

    fun savePayment(input: PaymentInput, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.savePayment(input) != null) }
    }

    fun assignPayment(paymentId: String, accountId: String, requested: String, reason: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.assignPayment(paymentId, accountId, requested, reason)) }
    }

    fun unassignPayment(paymentId: String, reason: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.unassignPayment(paymentId, reason)) }
    }

    fun reassignPayment(paymentId: String, toAccountId: String, reason: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.reassignPayment(paymentId, toAccountId, reason)) }
    }

    // ---------- Consolidados, Ajustes y Reembolsos (F7) ----------

    val consolidateds: StateFlow<List<ConsolidatedCollectionEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeConsolidatedCollections(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Membresías en Consolidados no cancelados: accountId → consolidatedId. */
    val activeMemberships: StateFlow<Map<String, String>> = run {
        repo.observeActiveMemberships()
            .map { links -> links.associate { it.accountId to it.consolidatedId } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    fun observeConsolidated(id: String) = repo.observeConsolidatedCollection(id)
    fun observeConsolidatedMembers(id: String) = repo.observeConsolidatedMembers(id)
    fun observeConsolidatedMemberships(accountId: String) = repo.observeConsolidatedMemberships(accountId)
    fun observePaymentsForConsolidated(id: String) = repo.observePaymentsForConsolidated(id)
    fun observePaymentAdjustments(paymentId: String) = repo.observePaymentAdjustments(paymentId)
    fun observePaymentRefunds(paymentId: String) = repo.observePaymentRefunds(paymentId)

    fun saveConsolidated(input: ConsolidatedInput, existingId: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveConsolidatedCollection(input, existingId)) }
    }

    fun cancelConsolidated(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.cancelConsolidatedCollection(id)) }
    }

    fun setConsolidatedArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setConsolidatedArchived(id, archived) }
    }

    fun assignPaymentToTarget(
        paymentId: String,
        targetKind: com.cotixgo.app.core.PaymentTargetKind,
        targetId: String,
        requested: String,
        reason: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(repo.assignPaymentToTarget(paymentId, targetKind, targetId, requested, reason))
        }
    }

    fun reassignPaymentToTarget(
        paymentId: String,
        targetKind: com.cotixgo.app.core.PaymentTargetKind,
        targetId: String,
        reason: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(repo.reassignPaymentToTarget(paymentId, targetKind, targetId, reason))
        }
    }

    fun correctPayment(paymentId: String, newEffective: String, reason: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.correctPayment(paymentId, newEffective, reason)) }
    }

    fun annulPayment(paymentId: String, reason: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.annulPayment(paymentId, reason)) }
    }

    fun refundPayment(
        paymentId: String,
        amount: String,
        refundedAtEpochDay: Long,
        collectionMethodId: String?,
        reason: String,
        onDone: (String?) -> Unit,
    ) {
        viewModelScope.launch {
            onDone(repo.refundPayment(paymentId, amount, refundedAtEpochDay, collectionMethodId, reason))
        }
    }

    // ---------- Inventario y Compras (F8) ----------

    val materials: StateFlow<List<MaterialEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeMaterials(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Catálogo + saldo mostrado (espejo del servidor + efectos pendientes). */
    val inventory: StateFlow<List<MaterialStockView>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observeInventory(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val purchases: StateFlow<List<PurchaseEntity>> = user.flatMapLatest { u ->
        if (u == null) flowOf(emptyList()) else repo.observePurchases(u.id)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun observeMaterial(id: String) = repo.observeMaterial(id)
    fun observeMovementsForMaterial(materialId: String) = repo.observeMovementsForMaterial(materialId)
    fun observeAllMovements(userId: String) = repo.observeAllMovements(userId)
    fun observePurchase(id: String) = repo.observePurchase(id)
    fun observePurchaseItems(id: String) = repo.observePurchaseItems(id)
    fun observePurchaseMovements(id: String) = repo.observePurchaseMovements(id)
    fun observeMovementsForJob(jobId: String) = repo.observeMovementsForJob(jobId)

    fun saveMaterial(input: MaterialInput, existingId: String?, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.saveMaterial(input, existingId) != null) }
    }

    fun setMaterialArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setMaterialArchived(id, archived) }
    }

    fun savePurchase(input: PurchaseInput, existingId: String?, confirm: Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.savePurchase(input, existingId, confirm)) }
    }

    fun confirmPurchase(id: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.confirmPurchase(id)) }
    }

    fun annulPurchase(id: String, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.annulPurchase(id)) }
    }

    fun setPurchaseArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setPurchaseArchived(id, archived) }
    }

    fun saveInventoryMovement(input: InventoryMovementInput, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveInventoryMovement(input)) }
    }

    fun syncNow() {
        if (!sync.session.isBound) {
            lastSyncMessage.value = "Vincula una cuenta para sincronizar."
            return
        }
        viewModelScope.launch {
            syncing.value = true
            try {
                val s = sync.syncNow()
                lastSyncMessage.value =
                    "Sincronizado: ${s.pushed} enviadas, ${s.pulled} recibidas" +
                        if (s.paused > 0) ", ${s.paused} requieren atención" else "."
            } catch (e: Exception) {
                lastSyncMessage.value = "Sin conexión con el servidor local; se reintentará."
            } finally {
                syncing.value = false
            }
        }
    }

    fun register(name: String, email: String, pass: String) {
        viewModelScope.launch {
            val err = auth.register(name, email, pass)
            accountEmail.value = sync.session.accountEmail
            lastSyncMessage.value = err ?: "Cuenta creada y vinculada."
            if (err == null) syncNow()
        }
    }

    fun login(email: String, pass: String) {
        viewModelScope.launch {
            val err = auth.login(email, pass)
            accountEmail.value = sync.session.accountEmail
            lastSyncMessage.value = err ?: "Sesión iniciada y cuenta vinculada."
            if (err == null) syncNow()
        }
    }

    fun recover(email: String) {
        viewModelScope.launch { lastSyncMessage.value = auth.recover(email) }
    }

    fun switchAccount(currentPass: String, newEmail: String, newPass: String, confirmed: Boolean) {
        viewModelScope.launch {
            val err = auth.switchAccount(currentPass, newEmail, newPass, confirmed)
            accountEmail.value = sync.session.accountEmail
            lastSyncMessage.value = err ?: "Cuenta cambiada; descargando datos."
            if (err == null) syncNow()
        }
    }

    fun setBaseUrl(url: String) {
        val u = url.trim()
        if (!BuildConfig.DEBUG && u.startsWith("http://", ignoreCase = true)) {
            // Release no admite cleartext: HTTPS obligatorio (decisión F3).
            lastSyncMessage.value = "HTTPS obligatorio: HTTP en claro solo está permitido en desarrollo."
            return
        }
        sync.session.apiBaseUrl = u
        apiBaseUrl.value = sync.session.apiBaseUrl
    }

    fun resolveKeepLocal(conflictId: Long) {
        viewModelScope.launch { sync.resolveKeepLocal(conflictId); syncNow() }
    }

    fun resolveKeepRemote(conflictId: Long) {
        viewModelScope.launch { sync.resolveKeepRemote(conflictId) }
    }

    fun retryPaused(opId: String) {
        viewModelScope.launch { sync.retryPaused(opId); syncNow() }
    }

    fun resolveQuoteVersions(quoteId: String, keepVersionRowId: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(sync.resolveQuoteVersions(quoteId, keepVersionRowId))
            syncNow()
        }
    }

    fun observeQuote(id: String) = repo.observeQuote(id)

    fun observeQuoteVersions(id: String) = repo.observeQuoteVersions(id)

    suspend fun getQuoteSnapshot(id: String) = repo.getQuoteSnapshot(id)

    fun completeOnboarding(userName: String, profileName: String) {
        viewModelScope.launch { repo.completeOnboarding(userName, profileName) }
    }

    fun saveClient(input: ClientInput, existingId: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.saveClient(input, existingId)
            onDone()
        }
    }

    fun setClientArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setClientArchived(id, archived) }
    }

    fun setMainCurrency(code: String) {
        viewModelScope.launch { repo.setMainCurrency(code) }
    }

    fun updateProfile(id: String, businessName: String, description: String, commercialInfo: String, vigenciaDias: Int?, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.updateProfile(id, businessName, description, commercialInfo, vigenciaDias)
            onDone()
        }
    }

    fun saveService(input: ServiceInput, existingId: String?, onDone: () -> Unit) {
        viewModelScope.launch {
            val fixed = if (input.profileId.isBlank()) {
                input.copy(profileId = profiles.value.firstOrNull()?.id ?: "")
            } else input
            repo.saveService(fixed, existingId); onDone()
        }
    }

    fun setServiceArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setServiceArchived(id, archived) }
    }

    fun saveRetention(input: RetentionInput, existingId: String?, onDone: () -> Unit) {
        viewModelScope.launch { repo.saveRetention(input, existingId); onDone() }
    }

    fun setRetentionArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setRetentionArchived(id, archived) }
    }

    fun saveQuote(input: QuoteInput, existingId: String?, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.saveQuote(input, existingId)) }
    }

    fun setQuoteStatus(id: String, to: QuoteStatus, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.setQuoteStatus(id, to)) }
    }

    fun emitQuote(id: String, onDone: (Boolean) -> Unit) {
        viewModelScope.launch { onDone(repo.emitQuote(id)) }
    }

    fun setQuoteArchived(id: String, archived: Boolean) {
        viewModelScope.launch { repo.setQuoteArchived(id, archived) }
    }

    fun revalidateQuote(id: String, validityDays: Int, onDone: (String?) -> Unit) {
        viewModelScope.launch { onDone(repo.revalidateQuote(id, validityDays)) }
    }

    companion object {
        fun factory(repo: AppRepository, sync: SyncRepository, auth: AuthRepository) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(repo, sync, auth) as T
            }
    }
}

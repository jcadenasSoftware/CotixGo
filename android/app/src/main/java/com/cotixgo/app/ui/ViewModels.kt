package com.cotixgo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.AuthRepository
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteInput
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
        sync.session.apiBaseUrl = url.trim()
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

    fun resolveQuoteVersions(quoteId: String, keepVersion: Int, onDone: (Boolean) -> Unit) {
        viewModelScope.launch {
            onDone(sync.resolveQuoteVersions(quoteId, keepVersion))
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

package com.cotixgo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.core.QuoteStatus
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.QuoteEntity
import com.cotixgo.app.data.QuoteInput
import com.cotixgo.app.data.RetentionConfigEntity
import com.cotixgo.app.data.RetentionInput
import com.cotixgo.app.data.ServiceEntity
import com.cotixgo.app.data.ServiceInput
import com.cotixgo.app.data.UserEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(private val repo: AppRepository) : ViewModel() {

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
        fun factory(repo: AppRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(repo) as T
        }
    }
}

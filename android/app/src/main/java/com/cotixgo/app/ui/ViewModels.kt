package com.cotixgo.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cotixgo.app.core.ClientInput
import com.cotixgo.app.data.AppRepository
import com.cotixgo.app.data.ClientEntity
import com.cotixgo.app.data.ProfileEntity
import com.cotixgo.app.data.UserEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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

    fun completeOnboarding(userName: String, profileName: String, onDone: () -> Unit) {
        viewModelScope.launch {
            repo.completeOnboarding(userName, profileName)
            onDone()
        }
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

    companion object {
        fun factory(repo: AppRepository) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                AppViewModel(repo) as T
        }
    }
}

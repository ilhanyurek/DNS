package com.ilhanyurek.privatednstiles.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class UiState(
    val configs: List<DnsConfig> = emptyList(),
    val activeId: String? = null,
    val hasPermission: Boolean = false
)

class DnsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DnsRepository(app)

    private val _permission = MutableStateFlow(false)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<UiState> =
        combine(repo.configs, repo.activeId, _permission) { configs, activeId, perm ->
            UiState(configs = configs, activeId = activeId, hasPermission = perm)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState())

    init {
        viewModelScope.launch { repo.seedDefaultsIfEmpty() }
        refreshPermission()
    }

    fun refreshPermission() {
        _permission.value = DnsManager.hasPermission(getApplication())
        // Keep the stored active id in sync with the real system state.
        viewModelScope.launch {
            val configs = repo.getConfigs()
            DnsManager.matchActive(getApplication(), configs)?.let { repo.setActiveId(it.id) }
        }
    }

    fun apply(config: DnsConfig) {
        viewModelScope.launch {
            val ok = DnsManager.apply(getApplication(), config)
            if (ok) {
                repo.setActiveId(config.id)
                _message.value = "Switched to ${config.name}"
            } else {
                _permission.value = false
                _message.value = "Couldn't change DNS – grant permission via ADB"
            }
        }
    }

    fun save(config: DnsConfig) {
        viewModelScope.launch { repo.upsert(config) }
    }

    fun delete(config: DnsConfig) {
        viewModelScope.launch { repo.delete(config.id) }
    }

    fun move(from: Int, to: Int) {
        viewModelScope.launch {
            val list = repo.getConfigs().toMutableList()
            if (from in list.indices && to in list.indices) {
                val item = list.removeAt(from)
                list.add(to, item)
                repo.replaceAll(list)
            }
        }
    }

    fun consumeMessage() { _message.value = null }
}

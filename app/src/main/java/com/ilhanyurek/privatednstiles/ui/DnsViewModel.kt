package com.ilhanyurek.privatednstiles.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import com.ilhanyurek.privatednstiles.dns.DnsSwitcher
import com.ilhanyurek.privatednstiles.dns.Reachability
import com.ilhanyurek.privatednstiles.dns.ReachabilityChecker
import com.ilhanyurek.privatednstiles.dns.SwitchResult
import com.ilhanyurek.privatednstiles.dns.message
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val configs: List<DnsConfig> = emptyList(),
    /** Id of the saved entry matching the live system setting, if any. */
    val activeId: String? = null,
    val hasPermission: Boolean = false,
    /** Reachability per DoT hostname (see [reachabilityKey]). Missing = not tested yet. */
    val reachability: Map<String, Reachability> = emptyMap(),
    /** Id of the entry currently being checked / applied, if any. */
    val switchingId: String? = null
)

/** Key used for [UiState.reachability]. */
fun reachabilityKey(hostname: String): String = hostname.trim().lowercase()

class DnsViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = DnsRepository(app)

    private val _permission = MutableStateFlow(false)
    private val _reachability = MutableStateFlow<Map<String, Reachability>>(emptyMap())
    private val _switchingId = MutableStateFlow<String?>(null)
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<UiState> =
        combine(
            repo.configs, DnsManager.settingChanges(app), _permission, _reachability, _switchingId
        ) { configs, setting, perm, reachability, switchingId ->
            // The active entry always reflects the real system setting, even when
            // it was changed elsewhere (system Settings, a tile, a fallback).
            val activeId = DnsManager.findMatch(configs, setting.mode, setting.hostname)?.id
            UiState(configs, activeId, perm, reachability, switchingId)
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

    /**
     * Re-test every DoT entry whenever the default network or the set of saved
     * hostnames changes. Call while the screen is visible; cached results (per
     * network) are reused, so reopening the screen doesn't re-probe right away.
     */
    suspend fun watchReachability() {
        combine(ReachabilityChecker.networkChanges(getApplication()), repo.configs) { _, configs ->
            configs.filter { it.mode == DnsMode.HOSTNAME }.map { reachabilityKey(it.hostname) }.toSet()
        }.collectLatest { hosts -> refreshReachability(hosts) }
    }

    private suspend fun refreshReachability(hosts: Set<String>) = coroutineScope {
        val app = getApplication<Application>()
        // Show what's already known for this network, then probe in parallel.
        _reachability.value = hosts.mapNotNull { h -> ReachabilityChecker.cached(app, h)?.let { h to it } }.toMap()
        hosts.forEach { host ->
            launch {
                val result = ReachabilityChecker.check(app, host)
                _reachability.update { it + (host to result) }
            }
        }
    }

    fun apply(config: DnsConfig) {
        if (_switchingId.value != null) return
        _switchingId.value = config.id
        DnsSwitcher.launch(getApplication(), config) { result ->
            _switchingId.value = null
            if (result == SwitchResult.NoPermission) _permission.value = false
            if (config.mode == DnsMode.HOSTNAME) {
                ReachabilityChecker.cached(getApplication(), config.hostname)?.let { r ->
                    _reachability.update { it + (reachabilityKey(config.hostname) to r) }
                }
            }
            _message.value = result.message
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

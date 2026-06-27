package com.ilhanyurek.privatednstiles.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "private_dns_tiles")

/**
 * Persists the user's list of [DnsConfig] entries plus the id of the one most
 * recently applied. Backed by Preferences DataStore + kotlinx.serialization.
 *
 * The same store is read by the UI and by the Quick Settings tile services, so
 * changes from either side stay in sync.
 */
class DnsRepository(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }

    private val keyConfigs = stringPreferencesKey("configs")
    private val keyActiveId = stringPreferencesKey("active_id")

    val configs: Flow<List<DnsConfig>> = context.dataStore.data.map { prefs ->
        decode(prefs[keyConfigs])
    }

    val activeId: Flow<String?> = context.dataStore.data.map { prefs ->
        prefs[keyActiveId]
    }

    suspend fun getConfigs(): List<DnsConfig> = configs.first()

    suspend fun getActiveId(): String? = activeId.first()

    suspend fun upsert(config: DnsConfig) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[keyConfigs]).toMutableList()
            val idx = current.indexOfFirst { it.id == config.id }
            if (idx >= 0) current[idx] = config else current.add(config)
            prefs[keyConfigs] = encode(current)
        }
    }

    suspend fun delete(id: String) {
        context.dataStore.edit { prefs ->
            val current = decode(prefs[keyConfigs]).filterNot { it.id == id }
            prefs[keyConfigs] = encode(current)
            if (prefs[keyActiveId] == id) prefs.remove(keyActiveId)
        }
    }

    /** Persist an explicitly ordered list (used by move up/down). */
    suspend fun replaceAll(configs: List<DnsConfig>) {
        context.dataStore.edit { prefs ->
            prefs[keyConfigs] = encode(configs)
        }
    }

    suspend fun setActiveId(id: String?) {
        context.dataStore.edit { prefs ->
            if (id == null) prefs.remove(keyActiveId) else prefs[keyActiveId] = id
        }
    }

    /** Seed the default list on first launch (only when nothing is stored yet). */
    suspend fun seedDefaultsIfEmpty() {
        context.dataStore.edit { prefs ->
            if (prefs[keyConfigs] == null) {
                prefs[keyConfigs] = encode(DnsConfig.defaults())
            }
        }
    }

    private fun encode(list: List<DnsConfig>): String = json.encodeToString(list)

    private fun decode(raw: String?): List<DnsConfig> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching { json.decodeFromString<List<DnsConfig>>(raw) }.getOrDefault(emptyList())
}

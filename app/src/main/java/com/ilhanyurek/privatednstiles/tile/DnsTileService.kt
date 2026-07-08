package com.ilhanyurek.privatednstiles.tile

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ilhanyurek.privatednstiles.R
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quick Settings tile that cycles through every saved DNS entry on each tap and
 * applies it immediately. The tile label shows the active entry.
 */
class DnsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { DnsRepository(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        scope.launch {
            val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }
            if (configs.isEmpty()) return@launch

            // Where are we now? Start from the live system state, fall back to the
            // last stored active id, otherwise begin at the start of the list.
            val active = DnsManager.matchActive(applicationContext, configs)
            val currentIndex = when {
                active != null -> configs.indexOf(active)
                else -> configs.indexOfFirst { it.id == repo.getActiveId() }
            }
            val next = configs[(currentIndex + 1).mod(configs.size)]

            val ok = DnsManager.apply(applicationContext, next)
            if (ok) repo.setActiveId(next.id)
            withContext(Dispatchers.Main) { renderTile(next, ok) }
        }
    }

    private fun refresh() {
        scope.launch {
            val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }
            val active = DnsManager.matchActive(applicationContext, configs)
                ?: configs.firstOrNull { it.id == repo.getActiveId() }
            val granted = DnsManager.hasPermission(applicationContext)
            withContext(Dispatchers.Main) { renderTile(active, granted) }
        }
    }

    private fun renderTile(config: DnsConfig?, ok: Boolean) {
        val tile = qsTile ?: return
        if (!ok) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.label = getString(R.string.tile_cycle_label)
            tile.subtitle = getString(R.string.tile_needs_permission)
            tile.icon = Icon.createWithResource(this, R.drawable.ic_dns)
        } else {
            tile.label = config?.name ?: getString(R.string.tile_cycle_label)
            tile.subtitle = config?.subtitle ?: getString(R.string.tile_tap_to_cycle)
            tile.state = if (config == null || config.mode == com.ilhanyurek.privatednstiles.data.DnsMode.OFF)
                Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            // Show the active DNS's short label as the icon so it's recognisable.
            tile.icon = if (config != null) TileIcons.forConfig(config)
            else Icon.createWithResource(this, R.drawable.ic_dns)
        }
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

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

            // Cycle position is based on the live system state; fall back to the
            // last stored id, otherwise start at the beginning of the list.
            val active = DnsManager.matchActive(applicationContext, configs)
            val currentIndex = when {
                active != null -> configs.indexOf(active)
                else -> configs.indexOfFirst { it.id == repo.getActiveId() }
            }
            val next = configs[(currentIndex + 1).mod(configs.size)]

            val ok = DnsManager.apply(applicationContext, next)
            if (ok) repo.setActiveId(next.id)
            // Re-read the real system state so the tile reflects what actually applied.
            withContext(Dispatchers.Main) { refresh() }
        }
    }

    private fun refresh() {
        scope.launch {
            val granted = DnsManager.hasPermission(applicationContext)
            if (!granted) {
                withContext(Dispatchers.Main) { renderUnavailable() }
                return@launch
            }
            val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }
            // Describe the live system state rather than the last stored entry.
            val mode = DnsManager.currentMode(applicationContext)
            val host = DnsManager.currentHostname(applicationContext)
            val match = DnsManager.findMatch(configs, mode, host)
            match?.let { repo.setActiveId(it.id) }
            val display = TileIcons.describe(mode, host, match)
            withContext(Dispatchers.Main) { renderTile(display) }
        }
    }

    private fun renderUnavailable() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = getString(R.string.tile_cycle_label)
        tile.subtitle = getString(R.string.tile_needs_permission)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_dns)
        tile.updateTile()
    }

    private fun renderTile(display: TileIcons.Display) {
        val tile = qsTile ?: return
        tile.label = display.name
        tile.subtitle = display.subtitle
        tile.state = if (display.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = TileIcons.forLabel(display.label)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

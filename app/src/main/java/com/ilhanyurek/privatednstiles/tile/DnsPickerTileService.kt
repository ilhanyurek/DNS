package com.ilhanyurek.privatednstiles.tile

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ilhanyurek.privatednstiles.PickerActivity
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
 * Quick Settings tile that, on tap, opens a compact picker dialog listing every
 * saved DNS entry so the user can jump straight to a specific one.
 */
class DnsPickerTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { DnsRepository(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope.launch {
            val granted = DnsManager.hasPermission(applicationContext)
            if (!granted) {
                withContext(Dispatchers.Main) { renderUnavailable() }
                return@launch
            }
            val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }
            val mode = DnsManager.currentMode(applicationContext)
            val host = DnsManager.currentHostname(applicationContext)
            val display = TileIcons.describe(mode, host, DnsManager.findMatch(configs, mode, host))
            withContext(Dispatchers.Main) { renderTile(display) }
        }
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(applicationContext, PickerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Requires unlocked device; collapses the QS panel as the dialog opens.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                applicationContext, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun renderUnavailable() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_UNAVAILABLE
        tile.label = getString(R.string.tile_picker_label)
        tile.subtitle = getString(R.string.tile_needs_permission)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_dns_pick)
        tile.updateTile()
    }

    private fun renderTile(display: TileIcons.Display) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_picker_label)
        tile.subtitle = display.subtitle
        tile.state = if (display.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        // Show the active DNS's short label so the current choice is visible.
        tile.icon = TileIcons.forLabel(display.label)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

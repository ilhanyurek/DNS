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
            val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }
            val active = DnsManager.matchActive(applicationContext, configs)
            val granted = DnsManager.hasPermission(applicationContext)
            withContext(Dispatchers.Main) { renderTile(active, granted) }
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

    private fun renderTile(config: DnsConfig?, granted: Boolean) {
        val tile = qsTile ?: return
        if (!granted) {
            tile.state = Tile.STATE_UNAVAILABLE
            tile.subtitle = getString(R.string.tile_needs_permission)
        } else {
            tile.state = if (config == null || config.mode == com.ilhanyurek.privatednstiles.data.DnsMode.OFF)
                Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            tile.subtitle = config?.subtitle ?: getString(R.string.tile_tap_to_pick)
        }
        tile.label = getString(R.string.tile_picker_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_dns_pick)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

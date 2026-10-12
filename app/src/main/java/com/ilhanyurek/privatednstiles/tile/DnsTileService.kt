package com.ilhanyurek.privatednstiles.tile

import android.content.Context
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.ilhanyurek.privatednstiles.R
import com.ilhanyurek.privatednstiles.background.Notifier
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsManager
import com.ilhanyurek.privatednstiles.dns.DnsSwitcher
import com.ilhanyurek.privatednstiles.dns.Reachability
import com.ilhanyurek.privatednstiles.dns.ReachabilityChecker
import com.ilhanyurek.privatednstiles.dns.SwitchResult
import com.ilhanyurek.privatednstiles.dns.message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quick Settings tile that cycles through every saved DNS entry on each tap and
 * applies it immediately. Entries whose DoT server is blocked on the current
 * network are skipped. The tile label shows the active entry.
 */
class DnsTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repo by lazy { DnsRepository(applicationContext) }

    /** What happened on a tap: the switch outcome (null = nothing reachable) and skipped entries. */
    private class CycleOutcome(val result: SwitchResult?, val skipped: List<DnsConfig>)

    override fun onStartListening() {
        super.onStartListening()
        refresh()
        repairOnOpen()
    }

    /** If automatic fallback is on and the current setting doesn't work, fix it. */
    private fun repairOnOpen() {
        val app = applicationContext
        DnsSwitcher.runDetached({ DnsSwitcher.autoFailoverOnOpen(app) }) { result ->
            result.message?.let {
                Notifier.show(app, it)
                refresh()
            }
        }
    }

    override fun onClick() {
        super.onClick()
        // Process-wide: the service can be recreated between taps while a switch runs.
        if (!cycling.compareAndSet(false, true)) return
        qsTile?.let { it.setSubtitleIfSupported(getString(R.string.tile_checking)); it.updateTile() }
        val app = applicationContext
        // Runs in the app-wide scope so it finishes even if the panel is closed.
        DnsSwitcher.runDetached({ cycle(app) }, onFailure = {
            cycling.set(false)
            refresh()
        }) { outcome ->
            cycling.set(false)
            cycleMessage(outcome)?.let { Toast.makeText(app, it, Toast.LENGTH_LONG).show() }
            refresh()
        }
    }

    private suspend fun cycle(app: Context): CycleOutcome {
        val configs = repo.getConfigs().ifEmpty { DnsConfig.defaults() }

        // Cycle position is based on the live system state; fall back to the
        // last stored id, otherwise start at the beginning of the list.
        val active = DnsManager.matchActive(app, configs)
        val currentIndex = active?.let { configs.indexOf(it) }
            ?: configs.indexOfFirst { it.id == repo.getActiveId() }
        // Every other entry, in cycle order (the whole list if nothing matches).
        val candidates = if (currentIndex < 0) configs
        else (1 until configs.size).map { configs[(currentIndex + it).mod(configs.size)] }

        // Test every DoT candidate in parallel (cached results are reused), then
        // take the first one in cycle order that isn't blocked here.
        val reach = ReachabilityChecker.checkAll(
            app, candidates.filter { it.mode == DnsMode.HOSTNAME }.map { it.hostname }
        )
        fun blocked(c: DnsConfig) =
            c.mode == DnsMode.HOSTNAME && reach[c.hostname.trim()] == Reachability.BLOCKED
        val next = candidates.firstOrNull { !blocked(it) } ?: return CycleOutcome(null, candidates)
        val skipped = candidates.takeWhile { it != next }
        return CycleOutcome(DnsSwitcher.switch(app, next), skipped)
    }

    private fun cycleMessage(outcome: CycleOutcome): String? {
        val result = outcome.result ?: return if (outcome.skipped.isEmpty())
            "No other DNS entry to switch to" else "No other DNS is reachable on this network"
        if (result is SwitchResult.Applied) {
            if (outcome.skipped.isEmpty()) return null // the tile itself shows the change
            val names = outcome.skipped.joinToString { it.name }
            return "${result.message} (skipped $names: blocked on this network)"
        }
        return result.message
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
        tile.setSubtitleIfSupported(getString(R.string.tile_needs_permission))
        tile.icon = Icon.createWithResource(this, R.drawable.ic_dns)
        tile.updateTile()
    }

    private fun renderTile(display: TileIcons.Display) {
        val tile = qsTile ?: return
        tile.label = display.name
        tile.setSubtitleIfSupported(display.subtitle)
        tile.state = if (display.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = TileIcons.forLabel(display.label)
        tile.updateTile()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        val cycling = AtomicBoolean(false)
    }
}

package com.ilhanyurek.privatednstiles.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode
import com.ilhanyurek.privatednstiles.data.DnsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executor
import kotlin.coroutines.resume

/** Outcome of [DnsSwitcher.switch]. */
sealed interface SwitchResult {
    /** The entry was applied and DNS resolves through it. */
    data class Applied(val config: DnsConfig) : SwitchResult

    /** The server looked blocked on this network, so nothing was changed. */
    data class Blocked(val config: DnsConfig) : SwitchResult

    /** The entry was applied but DNS stopped resolving, so another server was used. */
    data class RolledBack(val config: DnsConfig, val restoredName: String) : SwitchResult

    /** The entry didn't answer and no other saved server did either: Private DNS is off. */
    data class TurnedOff(val config: DnsConfig) : SwitchResult

    /** WRITE_SECURE_SETTINGS hasn't been granted. */
    data object NoPermission : SwitchResult

    /** Something unexpected went wrong (e.g. storage); see the log. */
    data class Failed(val config: DnsConfig) : SwitchResult
}

/** Short user-facing description of a [SwitchResult]. */
val SwitchResult.message: String
    get() = when (this) {
        is SwitchResult.Applied -> "Switched to ${config.name}"
        is SwitchResult.Blocked ->
            "${config.name} seems blocked on this network (port 853), so it wasn't applied"
        is SwitchResult.RolledBack ->
            "${config.name} didn't answer, switched to $restoredName"
        is SwitchResult.TurnedOff ->
            "${config.name} didn't answer and none of your DNS servers is reachable here, " +
                "so Private DNS is off"
        SwitchResult.NoPermission -> "Couldn't change DNS – grant permission via ADB"
        is SwitchResult.Failed -> "Couldn't finish switching to ${config.name}, please try again"
    }

/** Outcome of [DnsSwitcher.failover]. */
sealed interface FailoverResult {
    /** The setting was already the best working one (or nothing could be judged). */
    data object Unchanged : FailoverResult

    /** Switched to [config], the first saved server that works on this network. */
    data class Switched(val config: DnsConfig) : FailoverResult

    /** None of the saved servers answered, so Private DNS was turned off. */
    data object TurnedOff : FailoverResult

    /** WRITE_SECURE_SETTINGS hasn't been granted. */
    data object NoPermission : FailoverResult
}

/** User-facing description of an automatic change, or null if nothing changed. */
val FailoverResult.message: String?
    get() = when (this) {
        is FailoverResult.Switched -> "Switched to ${config.name}, the first of your DNS servers that works here"
        FailoverResult.TurnedOff -> "None of your DNS servers is reachable here, so Private DNS is off"
        FailoverResult.Unchanged, FailoverResult.NoPermission -> null
    }

/**
 * Switches Private DNS safely: checks that a DoT server is reachable before
 * applying it, then confirms that DNS still resolves. When a server doesn't
 * work, the saved servers are tried in list order (the list is the priority
 * order) and, if none works, Private DNS is turned off.
 *
 * Android's strict Private DNS mode never falls back to plain DNS, so a server
 * whose port 853 is filtered would otherwise leave the device offline.
 */
object DnsSwitcher {

    private const val TAG = "DnsSwitcher"

    private const val VERIFY_TIMEOUT_MS = 5_000L
    private const val SETTLE_DELAY_MS = 300L
    private const val RETRY_DELAY_MS = 250L
    private const val PROBE_HOST = "connectivitycheck.gstatic.com"
    private const val CURRENT_CHECK_TIMEOUT_MS = 2_000L
    private const val OPEN_CHECK_INTERVAL_MS = 20_000L

    // Outlives tiles and short-lived activities so the verification can finish.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val off = DnsConfig(id = "fallback-off", name = "Off", mode = DnsMode.OFF)

    @Volatile private var lastOpenCheckAt = 0L
    @Volatile private var lastOpenCheckNetwork = Long.MIN_VALUE

    /** Run [switch] in an app-wide scope and deliver the result on the main thread. */
    fun launch(context: Context, config: DnsConfig, onResult: (SwitchResult) -> Unit = {}): Job {
        val app = context.applicationContext
        return runDetached(
            { switch(app, config) },
            onFailure = { onResult(SwitchResult.Failed(config)) },
            onResult = onResult
        )
    }

    /**
     * Run [block] in the app-wide scope (so it isn't cancelled with a tile or a
     * short-lived activity) and deliver its result on the main thread. If it
     * throws, the error is logged and [onFailure] is called instead, so callers
     * can always release what they hold (a job, a receiver, a busy flag).
     */
    fun <T> runDetached(
        block: suspend () -> T,
        onFailure: () -> Unit = {},
        onResult: (T) -> Unit
    ): Job = scope.launch {
        val result = try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Background DNS task failed", e)
            Result.failure(e)
        }
        withContext(Dispatchers.Main) {
            result.fold(onSuccess = onResult, onFailure = { onFailure() })
        }
    }

    /** Check, apply and verify [config]. Switches are serialised. */
    suspend fun switch(context: Context, config: DnsConfig): SwitchResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val app = context.applicationContext
            val repo = DnsRepository(app)
            if (!DnsManager.hasPermission(app)) return@withContext SwitchResult.NoPermission

            // 1. Don't touch the setting if the server is clearly unreachable.
            //    UNKNOWN (the name can't be resolved right now) is let through;
            //    the verification below catches it if it really doesn't work.
            if (config.mode == DnsMode.HOSTNAME &&
                ReachabilityChecker.check(app, config.hostname) == Reachability.BLOCKED
            ) {
                return@withContext SwitchResult.Blocked(config)
            }

            val previous = currentConfig(app, repo.getConfigs())
            if (!DnsManager.apply(app, config)) return@withContext SwitchResult.NoPermission

            // 2. Off and Automatic always fall back to plain DNS, nothing to verify.
            if (config.mode != DnsMode.HOSTNAME || verify(app)) {
                repo.setActiveId(config.id)
                repo.setFallbackOff(false) // an explicit choice, even if it is Off
                return@withContext SwitchResult.Applied(config)
            }

            ReachabilityChecker.markBlocked(app, config.hostname)

            // 3a. Automatic fallback is off: only undo this switch, never pick
            //     another server on the user's behalf. Off if even that fails.
            if (!repo.isAutoFailoverEnabled()) {
                DnsManager.apply(app, previous)
                if (previous.mode != DnsMode.HOSTNAME || verify(app)) {
                    val savedIds = repo.getConfigs().map { it.id }
                    repo.setActiveId(previous.id.takeIf { it in savedIds })
                    return@withContext SwitchResult.RolledBack(config, previous.name)
                }
                ReachabilityChecker.markBlocked(app, previous.hostname)
                DnsManager.apply(app, off)
                repo.setFallbackOff(true)
                // Only the previous setting was tried, so don't claim none works.
                return@withContext SwitchResult.RolledBack(config, off.name)
            }

            // 3b. Fall back along the priority list, or to Off if nothing answers.
            when (val result = failoverLocked(app, restoreIfOffline = previous, upgrade = true)) {
                is FailoverResult.Switched -> SwitchResult.RolledBack(config, result.config.name)
                FailoverResult.TurnedOff -> SwitchResult.TurnedOff(config)
                FailoverResult.NoPermission -> SwitchResult.NoPermission
                FailoverResult.Unchanged -> SwitchResult.RolledBack(config, previous.name)
            }
        }
    }

    /**
     * Keep the highest-priority working server active: the first saved DoT entry
     * (in list order) that is reachable and answers DNS queries. If none does,
     * Private DNS is turned off so the device stays online.
     */
    suspend fun failover(context: Context): FailoverResult = mutex.withLock {
        failoverLocked(context.applicationContext, restoreIfOffline = null, upgrade = true)
    }

    /**
     * For a network change: if automatic fallback is on, move to the first saved
     * server that works on the new network (possibly a higher-priority one).
     */
    suspend fun autoFailover(context: Context): FailoverResult {
        val app = context.applicationContext
        if (!DnsRepository(app).isAutoFailoverEnabled()) return FailoverResult.Unchanged
        return failover(app)
    }

    /**
     * For when the app or the Quick Settings panel is opened: if automatic
     * fallback is on, only repair a setting that doesn't work (or retry after the
     * fallback turned Private DNS off). A working setting, including one the user
     * picked by hand, is left alone. Runs at most every [OPEN_CHECK_INTERVAL_MS]
     * on the same network.
     */
    suspend fun autoFailoverOnOpen(context: Context): FailoverResult {
        val app = context.applicationContext
        val network = app.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle ?: 0L
        synchronized(this) {
            val now = SystemClock.elapsedRealtime()
            if (network == lastOpenCheckNetwork && now - lastOpenCheckAt < OPEN_CHECK_INTERVAL_MS) {
                return FailoverResult.Unchanged
            }
            lastOpenCheckNetwork = network
            lastOpenCheckAt = now
        }
        if (!DnsRepository(app).isAutoFailoverEnabled()) return FailoverResult.Unchanged
        return mutex.withLock { failoverLocked(app, restoreIfOffline = null, upgrade = false) }
    }

    /**
     * @param restoreIfOffline what to put back if the network turns out to have no
     *   connectivity at all (nothing can be judged then); defaults to the current setting.
     * @param upgrade also move to a higher-priority server when the current one
     *   works; when false, a working setting is kept unless it is the fallback Off.
     */
    private suspend fun failoverLocked(
        app: Context,
        restoreIfOffline: DnsConfig?,
        upgrade: Boolean
    ): FailoverResult =
        withContext(Dispatchers.IO) {
            if (!DnsManager.hasPermission(app)) return@withContext FailoverResult.NoPermission
            // Without any network nothing can be judged; leave the setting alone.
            if (app.getSystemService(ConnectivityManager::class.java)?.activeNetwork == null) {
                return@withContext FailoverResult.Unchanged
            }
            val repo = DnsRepository(app)
            val configs = repo.getConfigs()
            val servers = configs.filter { it.mode == DnsMode.HOSTNAME && it.hostname.isNotBlank() }
            if (servers.isEmpty()) return@withContext FailoverResult.Unchanged

            val original = currentConfig(app, configs)
            val current = DnsManager.findMatch(servers, original.mode, original.hostname)
            var touched = false

            val currentWorks = resolveOnce(app, CURRENT_CHECK_TIMEOUT_MS)
            if (currentWorks && !upgrade &&
                !(original.mode == DnsMode.OFF && repo.isFallbackOff())
            ) {
                return@withContext FailoverResult.Unchanged
            }
            if (!currentWorks) {
                // A broken strict server also breaks the lookups the tests rely on,
                // so test from plain DNS. If even that fails, the network is down.
                if (original.mode != DnsMode.OFF) {
                    DnsManager.apply(app, off)
                    touched = true
                }
                if (!verify(app)) {
                    if (touched) DnsManager.apply(app, restoreIfOffline ?: original)
                    return@withContext FailoverResult.Unchanged
                }
            }

            // With a working current server only the ones above it can be better, so
            // test those first and the rest only if it turns out to be needed.
            val split = if (currentWorks && current != null) servers.indexOf(current) + 1 else servers.size
            for (group in listOf(servers.subList(0, split), servers.subList(split, servers.size))) {
                if (group.isEmpty()) continue
                val reach = ReachabilityChecker.checkAll(
                    app, group.filter { !(it == current && currentWorks) }.map { it.hostname }
                )
                for (server in group) {
                    if (reach[server.hostname.trim()] == Reachability.BLOCKED) continue
                    if (server == current && currentWorks && !touched) {
                        return@withContext FailoverResult.Unchanged
                    }
                    if (!DnsManager.apply(app, server)) return@withContext FailoverResult.NoPermission
                    touched = true
                    if (verify(app)) {
                        repo.setActiveId(server.id)
                        repo.setFallbackOff(false)
                        return@withContext if (server == current) FailoverResult.Unchanged
                        else FailoverResult.Switched(server)
                    }
                    ReachabilityChecker.markBlocked(app, server.hostname)
                }
            }

            // Nothing answered: plain DNS keeps the device online. Remember that
            // this Off was our doing, so it is retried later.
            DnsManager.apply(app, off)
            repo.setActiveId(configs.firstOrNull { it.mode == DnsMode.OFF }?.id)
            repo.setFallbackOff(true)
            if (original.mode == DnsMode.OFF) FailoverResult.Unchanged else FailoverResult.TurnedOff
        }

    /** The live system setting, as a saved entry when one matches. */
    private fun currentConfig(context: Context, configs: List<DnsConfig>): DnsConfig {
        val mode = DnsManager.currentMode(context)
        val host = DnsManager.currentHostname(context)
        return DnsManager.findMatch(configs, mode, host) ?: when (mode) {
            DnsMode.OFF -> DnsConfig(id = "previous", name = "Off", mode = mode)
            DnsMode.AUTOMATIC -> DnsConfig(id = "previous", name = "Automatic", mode = mode)
            DnsMode.HOSTNAME -> DnsConfig(id = "previous", name = host, mode = mode, hostname = host)
        }
    }

    /** True if a DNS query is answered within [VERIFY_TIMEOUT_MS] of switching. */
    private suspend fun verify(context: Context): Boolean {
        val deadline = SystemClock.elapsedRealtime() + VERIFY_TIMEOUT_MS
        delay(SETTLE_DELAY_MS) // give the resolver a moment to pick up the new setting
        while (true) {
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) return false
            if (resolveOnce(context, left)) return true
            delay(RETRY_DELAY_MS)
        }
    }

    private suspend fun resolveOnce(context: Context, timeoutMs: Long): Boolean {
        val network = context.getSystemService(ConnectivityManager::class.java)?.activeNetwork
            ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            rawQuery(network, timeoutMs)
        } else {
            val lookup = scope.async { runCatching { network.getByName(PROBE_HOST) }.isSuccess }
            withTimeoutOrNull(timeoutMs) { lookup.await() } ?: false
        }
    }

    /** A cache-bypassing query; any NOERROR/NXDOMAIN answer means the server responded. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private suspend fun rawQuery(network: Network, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                DnsResolver.getInstance().rawQuery(
                    network, PROBE_HOST, DnsResolver.CLASS_IN, DnsResolver.TYPE_A,
                    DnsResolver.FLAG_NO_CACHE_LOOKUP, Executor { it.run() }, signal,
                    object : DnsResolver.Callback<ByteArray> {
                        override fun onAnswer(answer: ByteArray, rcode: Int) {
                            if (cont.isActive) cont.resume(rcode == RCODE_NOERROR || rcode == RCODE_NXDOMAIN)
                        }

                        override fun onError(error: DnsResolver.DnsException) {
                            if (cont.isActive) cont.resume(false)
                        }
                    }
                )
            }
        } ?: false

    private const val RCODE_NOERROR = 0
    private const val RCODE_NXDOMAIN = 3
}

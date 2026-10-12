package com.ilhanyurek.privatednstiles.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.Network
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.ilhanyurek.privatednstiles.data.DnsConfig
import com.ilhanyurek.privatednstiles.data.DnsMode
import com.ilhanyurek.privatednstiles.data.DnsRepository
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
        return runDetached({ switch(app, config) }, onResult)
    }

    /**
     * Run [block] in the app-wide scope (so it isn't cancelled with a tile or a
     * short-lived activity) and deliver its result on the main thread.
     */
    fun <T> runDetached(block: suspend () -> T, onResult: (T) -> Unit): Job =
        scope.launch {
            val result = block()
            withContext(Dispatchers.Main) { onResult(result) }
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
                return@withContext SwitchResult.Applied(config)
            }

            // 3. Fall back along the priority list, or to Off if nothing answers.
            ReachabilityChecker.markBlocked(app, config.hostname)
            when (val result = failoverLocked(app, restoreIfOffline = previous)) {
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
    suspend fun failover(context: Context): FailoverResult =
        mutex.withLock { failoverLocked(context.applicationContext, restoreIfOffline = null) }

    /** [failover], but only when the user has automatic fallback turned on. */
    suspend fun autoFailover(context: Context): FailoverResult {
        val app = context.applicationContext
        if (!DnsRepository(app).isAutoFailoverEnabled()) return FailoverResult.Unchanged
        return failover(app)
    }

    /**
     * [autoFailover] for when the app or the Quick Settings panel is opened; runs
     * at most every [OPEN_CHECK_INTERVAL_MS] on the same network.
     */
    suspend fun autoFailoverOnOpen(context: Context): FailoverResult {
        val app = context.applicationContext
        val network = app.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle ?: 0L
        val now = SystemClock.elapsedRealtime()
        if (network == lastOpenCheckNetwork && now - lastOpenCheckAt < OPEN_CHECK_INTERVAL_MS) {
            return FailoverResult.Unchanged
        }
        lastOpenCheckNetwork = network
        lastOpenCheckAt = now
        return autoFailover(app)
    }

    /**
     * @param restoreIfOffline what to put back if the network turns out to have no
     *   connectivity at all (nothing can be judged then); defaults to the current setting.
     */
    private suspend fun failoverLocked(app: Context, restoreIfOffline: DnsConfig?): FailoverResult =
        withContext(Dispatchers.IO) {
            if (!DnsManager.hasPermission(app)) return@withContext FailoverResult.NoPermission
            val repo = DnsRepository(app)
            val configs = repo.getConfigs()
            val servers = configs.filter { it.mode == DnsMode.HOSTNAME && it.hostname.isNotBlank() }
            if (servers.isEmpty()) return@withContext FailoverResult.Unchanged

            val original = currentConfig(app, configs)
            val current = DnsManager.findMatch(servers, original.mode, original.hostname)
            var touched = false

            val currentWorks = resolveOnce(app, CURRENT_CHECK_TIMEOUT_MS)
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

            val reach = ReachabilityChecker.checkAll(app, servers.map { it.hostname })
            for (server in servers) {
                if (reach[server.hostname.trim()] == Reachability.BLOCKED) continue
                if (server == current && currentWorks && !touched) return@withContext FailoverResult.Unchanged
                if (!DnsManager.apply(app, server)) return@withContext FailoverResult.NoPermission
                touched = true
                if (verify(app)) {
                    repo.setActiveId(server.id)
                    return@withContext if (server == current) FailoverResult.Unchanged
                    else FailoverResult.Switched(server)
                }
                ReachabilityChecker.markBlocked(app, server.hostname)
            }

            // Nothing answered: plain DNS keeps the device online.
            DnsManager.apply(app, off)
            repo.setActiveId(configs.firstOrNull { it.mode == DnsMode.OFF }?.id)
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

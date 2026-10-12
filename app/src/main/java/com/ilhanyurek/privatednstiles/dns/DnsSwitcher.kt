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

    /** The entry was applied but DNS stopped resolving, so it was rolled back. */
    data class RolledBack(val config: DnsConfig, val restoredName: String) : SwitchResult

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
            "${config.name} didn't answer, switched back to $restoredName"
        SwitchResult.NoPermission -> "Couldn't change DNS – grant permission via ADB"
    }

/**
 * Switches Private DNS safely: checks that a DoT server is reachable before
 * applying it, then confirms that DNS still resolves and rolls back if not.
 *
 * Android's strict Private DNS mode never falls back to plain DNS, so a server
 * whose port 853 is filtered would otherwise leave the device offline.
 */
object DnsSwitcher {

    private const val VERIFY_TIMEOUT_MS = 5_000L
    private const val SETTLE_DELAY_MS = 300L
    private const val RETRY_DELAY_MS = 250L
    private const val PROBE_HOST = "connectivitycheck.gstatic.com"

    // Outlives tiles and short-lived activities so the verification can finish.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private val automatic = DnsConfig(id = "rollback-auto", name = "Automatic", mode = DnsMode.AUTOMATIC)

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

            // 3. Roll back: the previous setting if it still works, else Automatic.
            ReachabilityChecker.markBlocked(app, config.hostname)
            val restored = rollback(app, previous, config)
            val savedIds = repo.getConfigs().map { it.id }
            repo.setActiveId(restored.id.takeIf { it in savedIds })
            SwitchResult.RolledBack(config, restored.name)
        }
    }

    private suspend fun rollback(context: Context, previous: DnsConfig, failed: DnsConfig): DnsConfig {
        val samePlace = previous.mode == failed.mode &&
            previous.hostname.equals(failed.hostname, ignoreCase = true)
        if (!samePlace) {
            DnsManager.apply(context, previous)
            if (previous.mode != DnsMode.HOSTNAME || verify(context)) return previous
        }
        DnsManager.apply(context, automatic)
        return automatic
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

package com.ilhanyurek.privatednstiles.dns

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Result of probing a DNS-over-TLS server from the current network. */
enum class Reachability {
    /** TCP 853 connected and the TLS handshake for the hostname succeeded. */
    REACHABLE,

    /** The hostname resolved but port 853 / the TLS handshake failed or timed out. */
    BLOCKED,

    /** Not tested yet, or the hostname couldn't be resolved (so we can't tell). */
    UNKNOWN
}

/**
 * Checks whether a DoT server is usable from the current network before Android
 * is pointed at it. Android's strict Private DNS never falls back to plain DNS,
 * so switching to a server whose port 853 is filtered leaves the device offline.
 *
 * Results are cached briefly per network, so a network change invalidates them.
 */
object ReachabilityChecker {

    private const val DOT_PORT = 853
    private const val TIMEOUT_MS = 2_000L
    private const val LOOKUP_TIMEOUT_MS = 3_000L
    private const val CACHE_TTL_MS = 60_000L

    private data class Entry(val result: Reachability, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    // Abandoned lookups keep running here after their caller has timed out,
    // since InetAddress lookups can't be interrupted.
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The cached result for [hostname] on the current network, if still fresh. */
    fun cached(context: Context, hostname: String): Reachability? {
        val entry = cache[key(activeNetwork(context), hostname)] ?: return null
        return entry.result.takeIf { SystemClock.elapsedRealtime() - entry.at < CACHE_TTL_MS }
    }

    /** Probe [hostname] (or reuse a fresh cached result unless [force]). */
    suspend fun check(context: Context, hostname: String, force: Boolean = false): Reachability {
        if (!force) cached(context, hostname)?.let { return it }
        // Take the key before probing: if the network changes meanwhile, the
        // result still belongs to the network it was measured on.
        val network = activeNetwork(context)
        val key = key(network, hostname)
        val result = probe(network, hostname.trim())
        if (result != Reachability.UNKNOWN) store(key, result)
        return result
    }

    /** Record that [hostname] failed in practice (e.g. DNS stopped resolving after a switch). */
    fun markBlocked(context: Context, hostname: String) {
        store(key(activeNetwork(context), hostname), Reachability.BLOCKED)
    }

    private fun store(key: String, result: Reachability) {
        val now = SystemClock.elapsedRealtime()
        // Drop expired entries (e.g. for networks we've left) so the cache stays small.
        cache.entries.removeIf { now - it.value.at >= CACHE_TTL_MS }
        cache[key] = Entry(result, now)
    }

    /** Probe all [hostnames] in parallel. */
    suspend fun checkAll(
        context: Context,
        hostnames: Collection<String>,
        force: Boolean = false
    ): Map<String, Reachability> = coroutineScope {
        val distinct = hostnames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val results = distinct.map { host -> async { check(context, host, force) } }.awaitAll()
        distinct.zip(results).toMap()
    }

    /**
     * Emits once when collection starts and again whenever the default network
     * changes (e.g. Wi-Fi <-> mobile data), so callers can re-test.
     */
    fun networkChanges(context: Context): Flow<Unit> = callbackFlow {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(Unit)
            }

            override fun onLost(network: Network) {
                trySend(Unit)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitClose { cm.unregisterNetworkCallback(callback) }
    }.conflate()

    private suspend fun probe(network: Network?, hostname: String): Reachability {
        // Resolve with the current DNS. If that fails we can't judge the server.
        val lookup = lookupScope.async {
            runCatching { network?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname) }
                .getOrNull()?.toList().orEmpty()
        }
        val addresses = withTimeoutOrNull(LOOKUP_TIMEOUT_MS) { lookup.await() }.orEmpty()
        if (addresses.isEmpty()) return Reachability.UNKNOWN

        return withContext(Dispatchers.IO) {
            // The time budget covers only the DoT connection, not the lookup above,
            // so a slow lookup can't make a working server look blocked.
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            // Like Android itself, try every address. IPv4 goes first: a network
            // with a broken IPv6 route could otherwise use up the whole budget.
            val ok = addresses.sortedBy { it !is Inet4Address }.any { address ->
                SystemClock.elapsedRealtime() < deadline && handshake(network, address, hostname, deadline)
            }
            if (ok) Reachability.REACHABLE else Reachability.BLOCKED
        }
    }

    /** Connect to [address]:853 and complete a TLS handshake for [hostname] before [deadline]. */
    private fun handshake(network: Network?, address: InetAddress, hostname: String, deadline: Long): Boolean =
        try {
            val plain = network?.socketFactory?.createSocket() ?: Socket()
            plain.use { socket ->
                socket.connect(InetSocketAddress(address, DOT_PORT), remaining(deadline))
                socket.soTimeout = remaining(deadline)
                // A bare TCP connect isn't enough: some networks accept the
                // connection and then drop it based on the TLS SNI.
                val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
                (factory.createSocket(socket, hostname, DOT_PORT, true) as SSLSocket).use { tls ->
                    tls.sslParameters = tls.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    tls.startHandshake()
                }
            }
            true
        } catch (e: IOException) {
            false
        }

    private fun remaining(deadline: Long): Int =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()

    private fun activeNetwork(context: Context): Network? =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork

    private fun key(network: Network?, hostname: String): String =
        "${network?.networkHandle ?: 0L}|${hostname.trim().lowercase()}"
}

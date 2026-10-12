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
    private const val CACHE_TTL_MS = 60_000L

    private data class Entry(val result: Reachability, val at: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    // Abandoned lookups keep running here after their caller has timed out,
    // since InetAddress lookups can't be interrupted.
    private val lookupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The cached result for [hostname] on the current network, if still fresh. */
    fun cached(context: Context, hostname: String): Reachability? {
        val entry = cache[key(context, hostname)] ?: return null
        return entry.result.takeIf { SystemClock.elapsedRealtime() - entry.at < CACHE_TTL_MS }
    }

    /** Probe [hostname] (or reuse a fresh cached result unless [force]). */
    suspend fun check(context: Context, hostname: String, force: Boolean = false): Reachability {
        if (!force) cached(context, hostname)?.let { return it }
        val network = activeNetwork(context)
        val result = probe(network, hostname.trim())
        if (result != Reachability.UNKNOWN) {
            cache[key(context, hostname)] = Entry(result, SystemClock.elapsedRealtime())
        }
        return result
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
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS

        // Resolve with the current DNS. If that fails we can't judge the server.
        val lookup = lookupScope.async {
            runCatching { network?.getAllByName(hostname) ?: InetAddress.getAllByName(hostname) }
                .getOrNull()?.firstOrNull()
        }
        val address = withTimeoutOrNull(TIMEOUT_MS) { lookup.await() }
            ?: return Reachability.UNKNOWN

        return withContext(Dispatchers.IO) {
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
                Reachability.REACHABLE
            } catch (e: IOException) {
                Reachability.BLOCKED
            }
        }
    }

    private fun remaining(deadline: Long): Int =
        (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()

    private fun activeNetwork(context: Context): Network? =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork

    private fun key(context: Context, hostname: String): String {
        val net = activeNetwork(context)?.networkHandle ?: 0L
        return "$net|${hostname.trim().lowercase()}"
    }
}

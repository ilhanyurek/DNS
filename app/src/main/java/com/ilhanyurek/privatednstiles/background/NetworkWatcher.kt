package com.ilhanyurek.privatednstiles.background

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import com.ilhanyurek.privatednstiles.data.DnsRepository

/**
 * Asks Android to wake the app whenever a network that can reach the internet
 * becomes available, so the automatic fallback can run without a background
 * service. The system keeps the registration until it is removed, the device
 * reboots or the app is updated; [BootReceiver] restores it after the latter two.
 */
object NetworkWatcher {

    private const val REQUEST_CODE = 1

    /** Register or unregister to match the automatic fallback setting. */
    suspend fun sync(context: Context) {
        val app = context.applicationContext
        if (DnsRepository(app).isAutoFailoverEnabled()) register(app) else unregister(app)
    }

    /** Handle of the current default network, or 0 when there is none. */
    fun currentNetworkHandle(context: Context): Long =
        context.getSystemService(ConnectivityManager::class.java)?.activeNetwork?.networkHandle ?: 0L

    private suspend fun register(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        // Right after registering, the system also reports the networks that are
        // already up. Remember the current one so that isn't taken for a change.
        DnsRepository(context).setLastNetwork(currentNetworkHandle(context))
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        // Registering again with the same PendingIntent replaces the old request.
        runCatching { cm.registerNetworkCallback(request, pendingIntent(context)) }
    }

    private fun unregister(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(pendingIntent(context)) }
    }

    private fun pendingIntent(context: Context): PendingIntent {
        // Must be mutable: the system adds the network to the intent's extras.
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, NetworkChangeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or mutable
        )
    }
}

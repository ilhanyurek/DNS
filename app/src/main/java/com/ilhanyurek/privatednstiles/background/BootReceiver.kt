package com.ilhanyurek.privatednstiles.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ilhanyurek.privatednstiles.dns.DnsSwitcher

/** Restores the [NetworkWatcher] registration after a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val app = context.applicationContext
        val pending = goAsync()
        DnsSwitcher.runDetached(
            { NetworkWatcher.sync(app) },
            onFailure = { pending.finish() }
        ) { pending.finish() }
    }
}

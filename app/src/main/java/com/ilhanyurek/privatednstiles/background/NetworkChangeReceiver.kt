package com.ilhanyurek.privatednstiles.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Woken by [NetworkWatcher] when a network comes up; defers the work to a job. */
class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        FailoverJobService.schedule(context)
    }
}

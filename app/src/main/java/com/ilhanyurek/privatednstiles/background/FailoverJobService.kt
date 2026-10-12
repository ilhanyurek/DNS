package com.ilhanyurek.privatednstiles.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.ilhanyurek.privatednstiles.data.DnsRepository
import com.ilhanyurek.privatednstiles.dns.DnsSwitcher
import com.ilhanyurek.privatednstiles.dns.FailoverResult
import com.ilhanyurek.privatednstiles.dns.message

/**
 * Runs the automatic fallback after the default network has changed. Scheduled
 * by [NetworkChangeReceiver]; a burst of network events collapses into one run.
 */
class FailoverJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        DnsSwitcher.runDetached({ runIfNetworkChanged(app) }) { result ->
            result?.message?.let { Notifier.show(app, it) }
            jobFinished(params, false)
        }
        return true
    }

    // The fallback isn't cancelled here: stopping it halfway could leave Private
    // DNS switched off for testing. It finishes on its own within seconds.
    override fun onStopJob(params: JobParameters): Boolean = false

    companion object {
        private const val JOB_ID = 1001

        // Lets the new network become the default one before it is checked.
        private const val SETTLE_DELAY_MS = 2_000L
        private const val MAX_DELAY_MS = 5_000L

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, FailoverJobService::class.java))
                .setMinimumLatency(SETTLE_DELAY_MS)
                .setOverrideDeadline(MAX_DELAY_MS)
                .build()
            // Re-scheduling a pending job replaces it, which debounces bursts.
            scheduler.schedule(job)
        }

        /** Run the fallback only if the default network differs from the last handled one. */
        suspend fun runIfNetworkChanged(context: Context): FailoverResult? {
            val repo = DnsRepository(context)
            val network = NetworkWatcher.currentNetworkHandle(context)
            if (network == 0L || network == repo.getLastNetwork()) return null
            // Record it first so an overlapping run for the same network is skipped.
            repo.setLastNetwork(network)
            return DnsSwitcher.autoFailover(context)
        }
    }
}

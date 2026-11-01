package com.family.tvguard.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import com.family.tvguard.TvGuardApp

/**
 * Persisted JobScheduler entry. The system restores this after reboot / IPO wake
 * even when the OEM drops BOOT_COMPLETED. Work is just "start the foreground service".
 */
class GuardRestartJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TvGuardApp.TAG, "GuardRestartJob start id=${params.jobId}")
        // Presence only. The service tick raises the lock; forcing it here would
        // re-launch LockActivity every time the one-shot job cycles.
        // start() re-arms the persisted one-shot once this execution drops out of getPendingJob.
        GuardService.start(applicationContext, showLock = false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.w(TvGuardApp.TAG, "GuardRestartJob stopped id=${params.jobId}, reschedule")
        KeepAliveScheduler.scheduleFastRetry(applicationContext)
        return true
    }
}

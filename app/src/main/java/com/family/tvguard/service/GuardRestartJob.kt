package com.family.tvguard.service

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.ui.LockActivity

/**
 * Persisted JobScheduler entry. The system restores this after reboot / IPO wake
 * even when the OEM drops BOOT_COMPLETED. Work is just "start the foreground service".
 */
class GuardRestartJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Log.i(TvGuardApp.TAG, "GuardRestartJob start id=${params.jobId}")
        val appCtx = applicationContext
        val interactive = (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (interactive) {
            GuardService.start(appCtx, showLock = true)
            KeepAliveScheduler.bringLockToFront(appCtx)
        } else {
            // Do not start LockActivity while the panel is off — turnScreenOn would power the TV.
            LockActivity.clearResumed()
            GuardService.start(appCtx, showLock = false)
        }
        // JOB_SOON is still pending until this method returns, so schedule() will not
        // replace it. Arm the next one after it drops out, or a standby wake has nothing left.
        Handler(Looper.getMainLooper()).post {
            val on = (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
            if (on) {
                KeepAliveScheduler.armSoonJob(appCtx)
            } else {
                KeepAliveScheduler.armForStandby(appCtx, fromAlarmWhileOff = true)
            }
        }
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        Log.w(TvGuardApp.TAG, "GuardRestartJob stopped id=${params.jobId}, reschedule")
        KeepAliveScheduler.scheduleFastRetry(applicationContext)
        return true
    }
}

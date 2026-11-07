package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler
import com.family.tvguard.ui.LockActivity

/**
 * Alarm target. Not directBootAware, so a direct-boot schedule is queued
 * until credential storage unlocks, then this starts [GuardService].
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TvGuardApp.TAG, "KeepAliveReceiver action=$action")
        if (action == ACTION_PING || action == Intent.ACTION_BOOT_COMPLETED) {
            val pending = goAsync()
            try {
                val appCtx = context.applicationContext
                val interactive = (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
                if (!interactive) {
                    // Panel is still off. Launching LockActivity here can turn it back on.
                    LockActivity.clearResumed()
                    KeepAliveScheduler.armForStandby(appCtx, fromAlarmWhileOff = true)
                    GuardService.start(appCtx, showLock = false)
                } else {
                    GuardService.start(appCtx, showLock = true)
                    KeepAliveScheduler.bringLockToFront(appCtx)
                }
            } catch (e: Exception) {
                Log.e(TvGuardApp.TAG, "KeepAliveReceiver failed: ${e.message}", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_PING = "com.family.tvguard.action.KEEPALIVE_PING"
    }
}

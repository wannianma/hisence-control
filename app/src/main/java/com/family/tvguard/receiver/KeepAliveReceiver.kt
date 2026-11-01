package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService

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
                GuardService.start(context.applicationContext, showLock = true)
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

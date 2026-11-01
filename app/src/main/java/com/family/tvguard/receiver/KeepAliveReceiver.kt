package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler

/**
 * Periodic / one-shot alarm to re-start GuardService after OEM kills.
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TvGuardApp.TAG, "KeepAliveReceiver action=$action")
        if (action == ACTION_PING || action == Intent.ACTION_BOOT_COMPLETED) {
            GuardService.start(context.applicationContext)
            KeepAliveScheduler.schedule(context.applicationContext)
        }
    }

    companion object {
        const val ACTION_PING = "com.family.tvguard.action.KEEPALIVE_PING"
    }
}

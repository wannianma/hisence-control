package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler

/**
 * Best-effort boot / package-replace start.
 * Hisense/VIDAA often kills non-whitelisted apps — add 家庭电视锁 to 启动管理 whitelist.
 * Also schedules KeepAlive alarms as a soft sidekick (not a second APK).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TvGuardApp.TAG, "BootReceiver action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT" -> {
                val appCtx = context.applicationContext
                GuardService.start(appCtx)
                KeepAliveScheduler.schedule(appCtx)
            }
        }
    }
}

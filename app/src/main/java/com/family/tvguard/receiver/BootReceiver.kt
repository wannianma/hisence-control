package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService

/**
 * Best-effort boot start. Hisense/VIDAA often kills non-whitelisted apps —
 * document: add 家庭电视锁 to 启动管理 whitelist.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TvGuardApp.TAG, "BootReceiver action=$action")
        when (action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            "android.intent.action.QUICKBOOT_POWERON" -> {
                GuardService.start(context.applicationContext)
            }
        }
    }
}

package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService
import com.family.tvguard.util.UserUnlock

/**
 * Cold boot, package replace, and MediaTek IPO (Hisense power-on from suspend).
 * directBootAware so LOCKED_BOOT_COMPLETED is not dropped. Prefs stay untouched
 * until [UserUnlock] — see [TvGuardApp].
 *
 * Hisense/VIDAA may still kill a non-whitelisted app. 启动管理 remains the fallback.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in START_ACTIONS) {
            Log.d(TvGuardApp.TAG, "BootReceiver ignore action=$action")
            return
        }
        val pending = goAsync()
        try {
            Log.i(
                TvGuardApp.TAG,
                "BootReceiver action=$action userUnlocked=${UserUnlock.isUnlocked(context)}"
            )
            // showLock=false: device-uptime grace in GuardService avoids fighting the boot animation.
            // The service still enforces as soon as that grace ends.
            GuardService.start(context.applicationContext, showLock = false)
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "BootReceiver failed: ${e.message}", e)
        } finally {
            pending.finish()
        }
    }

    companion object {
        val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            "android.intent.action.REBOOT",
            "android.intent.action.ACTION_BOOT_IPO",
            "android.intent.action.ACTION_PREBOOT_IPO",
            "com.mediatek.intent.action.ACTION_BOOT_IPO",
        )
    }
}

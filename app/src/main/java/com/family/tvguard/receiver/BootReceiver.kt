package com.family.tvguard.receiver

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler
import com.family.tvguard.ui.LockActivity
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
            val appCtx = context.applicationContext
            if (isStandbyAction(action)) {
                // Every IPO shutdown, not only the first. Plant a wall-clock alarm
                // before the process is frozen, and forget any stale "lock is showing".
                LockActivity.clearResumed()
                KeepAliveScheduler.armForStandby(appCtx)
                GuardService.start(appCtx, showLock = false)
            } else {
                // IPO / user-present must lock even during the cold-boot grace window.
                // The 2nd standby resume is not a new boot, and grace must not swallow it.
                val wake = isWakeAction(action)
                GuardService.start(appCtx, showLock = wake)
                // Start the activity from this receiver. Hisense drops the same
                // startActivity once it is only coming from GuardService.
                if (wake) KeepAliveScheduler.bringLockToFront(appCtx)
            }
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "BootReceiver failed: ${e.message}", e)
        } finally {
            pending.finish()
        }
    }

    companion object {
        /** Hisense can disable this component in 启动管理. Re-enable while we are running. */
        fun ensureEnabled(context: Context) {
            try {
                val pm = context.packageManager
                val cn = ComponentName(context, BootReceiver::class.java)
                val state = pm.getComponentEnabledSetting(cn)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED
                ) {
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    Log.i(TvGuardApp.TAG, "Re-enabled BootReceiver (was $state)")
                }
            } catch (e: Exception) {
                Log.w(TvGuardApp.TAG, "ensure BootReceiver enabled: ${e.message}")
            }
        }

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
            "android.intent.action.ACTION_SHUTDOWN_IPO",
            Intent.ACTION_SHUTDOWN,
        )

        private fun isStandbyAction(action: String): Boolean =
            action == Intent.ACTION_SHUTDOWN ||
                action == "android.intent.action.ACTION_SHUTDOWN_IPO"

        /** Wakes that should raise the lock immediately, including the 2nd IPO resume. */
        private fun isWakeAction(action: String): Boolean =
            action == Intent.ACTION_BOOT_COMPLETED ||
                action == Intent.ACTION_USER_PRESENT ||
                action == "android.intent.action.ACTION_BOOT_IPO" ||
                action == "com.mediatek.intent.action.ACTION_BOOT_IPO" ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}

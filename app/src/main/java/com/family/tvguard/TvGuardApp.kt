package com.family.tvguard

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler
import com.family.tvguard.util.UsageAccessHelper
import com.family.tvguard.util.UserUnlock

class TvGuardApp : Application() {
    lateinit var prefs: GuardPrefs
        private set

    private var runtimeReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        registerRuntimeReceivers()
        if (!UserUnlock.isUnlocked(this)) {
            // LOCKED_BOOT_COMPLETED starts this process before CE storage exists.
            // Touching SharedPreferences here crashes the process and Hisense may not retry.
            Log.i(TAG, "Direct boot: defer GuardService until user unlock")
            KeepAliveScheduler.scheduleFastRetry(this)
            return
        }
        ensurePrefs()
        GuardService.start(this)
    }

    /**
     * @return true when credential-encrypted prefs are safe to use.
     */
    fun ensurePrefs(): Boolean {
        if (::prefs.isInitialized) return true
        if (!UserUnlock.isUnlocked(this)) return false
        return try {
            prefs = GuardPrefs(this)
            val token = prefs.ensureParentToken()
            Log.i(
                TAG,
                "TvGuard ${GuardService.VERSION_NAME} (${GuardService.VERSION_CODE}) started; " +
                    "parentToken=$token port=${GuardPrefs.HTTP_PORT} " +
                    "usageAccess=${UsageAccessHelper.hasUsageAccess(this)}"
            )
            if (!UsageAccessHelper.hasUsageAccess(this)) {
                Log.w(TAG, "使用情况访问未开启：守护仍会启动，前台检测回退启发式。建议在特殊应用权限中打开。")
            }
            writeTokenFile(token)
            true
        } catch (e: Exception) {
            Log.e(TAG, "ensurePrefs failed: ${e.message}", e)
            false
        }
    }

    private fun writeTokenFile(token: String) {
        try {
            val f = getExternalFilesDir(null)
            if (f != null) {
                val out = java.io.File(f, "parent_token.txt")
                out.writeText(
                    "parentToken=$token\n" +
                        "admin=http://<tv-ip>:${GuardPrefs.HTTP_PORT}/admin/\n" +
                        "Authorization: Bearer $token\n" +
                        "version=${GuardService.VERSION_NAME}\n"
                )
                Log.i(TAG, "Wrote token file: ${out.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "token file write failed: ${e.message}")
        }
    }

    /**
     * SCREEN_ON / dreaming cannot be received from the manifest. Once any start
     * path has created the process, these re-arm the guard when the panel wakes.
     */
    private fun registerRuntimeReceivers() {
        if (runtimeReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_USER_UNLOCKED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(ACTION_BOOT_IPO)
            addAction(ACTION_SHUTDOWN_IPO)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent?) {
                val action = intent?.action ?: return
                Log.i(TAG, "runtime action=$action")
                if (action == ACTION_SHUTDOWN_IPO) {
                    // Suspend imminent. Leave a non-wakeup alarm so wake flushes the guard.
                    KeepAliveScheduler.scheduleFastRetry(context.applicationContext)
                    return
                }
                val showLock = action == Intent.ACTION_SCREEN_ON ||
                    action == Intent.ACTION_USER_PRESENT ||
                    action == Intent.ACTION_USER_UNLOCKED ||
                    action == Intent.ACTION_DREAMING_STOPPED ||
                    action == ACTION_BOOT_IPO
                GuardService.start(context.applicationContext, showLock = showLock)
            }
        }
        runtimeReceiver = receiver
        ContextCompat.registerReceiver(
            this,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    companion object {
        const val TAG = "TvGuard"
        private const val ACTION_BOOT_IPO = "android.intent.action.ACTION_BOOT_IPO"
        private const val ACTION_SHUTDOWN_IPO = "android.intent.action.ACTION_SHUTDOWN_IPO"

        lateinit var instance: TvGuardApp
            private set
    }
}

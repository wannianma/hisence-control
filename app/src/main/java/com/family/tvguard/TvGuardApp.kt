package com.family.tvguard

import android.app.Application
import android.util.Log
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler
import com.family.tvguard.util.UsageAccessHelper

class TvGuardApp : Application() {
    lateinit var prefs: GuardPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = GuardPrefs(this)
        val token = prefs.ensureParentToken()
        Log.i(
            TAG,
            "TvGuard ${GuardService.VERSION_NAME} started; parentToken=$token port=${GuardPrefs.HTTP_PORT} usageAccess=${UsageAccessHelper.hasUsageAccess(this)}"
        )
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
        // Soft start: process may be woken without BootReceiver on some OEM paths
        try {
            GuardService.start(this)
            KeepAliveScheduler.schedule(this)
        } catch (e: Exception) {
            Log.w(TAG, "early GuardService start: ${e.message}")
        }
    }

    companion object {
        const val TAG = "TvGuard"
        lateinit var instance: TvGuardApp
            private set
    }
}

package com.family.tvguard

import android.app.Application
import android.util.Log
import com.family.tvguard.data.GuardPrefs

class TvGuardApp : Application() {
    lateinit var prefs: GuardPrefs
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = GuardPrefs(this)
        val token = prefs.ensureParentToken()
        Log.i(TAG, "TvGuard M1 started; parentToken=$token port=${GuardPrefs.HTTP_PORT}")
        // Also dump token path hint for adb
        try {
            val f = getExternalFilesDir(null)
            if (f != null) {
                val out = java.io.File(f, "parent_token.txt")
                out.writeText(
                    "parentToken=$token\n" +
                        "admin=http://<tv-ip>:${GuardPrefs.HTTP_PORT}/admin/\n" +
                        "Authorization: Bearer $token\n"
                )
                Log.i(TAG, "Wrote token file: ${out.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "token file write failed: ${e.message}")
        }
    }

    companion object {
        const val TAG = "TvGuard"
        lateinit var instance: TvGuardApp
            private set
    }
}

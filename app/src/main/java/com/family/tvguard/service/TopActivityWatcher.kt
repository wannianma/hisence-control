package com.family.tvguard.service

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.family.tvguard.TvGuardApp

/**
 * Best-effort top activity detection without Accessibility.
 * Prefer runningTasks / foreground process; UsageStats is last resort (often stale on TV).
 */
class TopActivityWatcher(private val context: Context) {

    fun topPackageAndActivity(): Pair<String?, String?> {
        runningTasksTop()?.let { return it }
        foregroundProcessTop()?.let { return it }
        usageStatsTop()?.let { return it }
        return null to null
    }

    @Suppress("DEPRECATION")
    private fun runningTasksTop(): Pair<String?, String?>? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(3)
            if (tasks.isNullOrEmpty()) return null
            val ti = tasks[0]
            val pkg = ti.topActivity?.packageName
            val cls = ti.topActivity?.className
            if (pkg.isNullOrBlank()) return null
            pkg to cls
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "runningTasks: ${e.message}")
            null
        }
    }

    private fun foregroundProcessTop(): Pair<String?, String?>? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val procs = am.runningAppProcesses ?: return null
            val fg = procs.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            } ?: return null
            val pkg = fg.pkgList?.firstOrNull() ?: fg.processName
            pkg to null
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "fgProcess: ${e.message}")
            null
        }
    }

    private fun usageStatsTop(): Pair<String?, String?>? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val end = System.currentTimeMillis()
            val begin = end - 10_000
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
            if (stats.isNullOrEmpty()) return null
            val top = stats.maxByOrNull { it.lastTimeUsed } ?: return null
            // Mark activity unknown so caller won't treat as LockActivity
            top.packageName to "unknown"
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "usageStats: ${e.message}")
            null
        }
    }
}

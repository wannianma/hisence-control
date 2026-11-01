package com.family.tvguard.service

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.util.UsageAccessHelper

/**
 * Top activity detection for lock enforcement.
 * When Usage Access is granted, prefer UsageStats / UsageEvents (more reliable on TV).
 * Soft-fail to runningTasks / foreground process when denied.
 */
class TopActivityWatcher(private val context: Context) {

    data class TopInfo(
        val packageName: String?,
        val activityClass: String?,
        val source: String
    )

    fun topPackageAndActivity(): Pair<String?, String?> {
        val info = topInfo()
        return info.packageName to info.activityClass
    }

    fun topInfo(): TopInfo {
        if (UsageAccessHelper.hasUsageAccess(context)) {
            usageEventsTop()?.let { return it }
            usageStatsTop()?.let { return it }
        }
        runningTasksTop()?.let { return it }
        foregroundProcessTop()?.let { return it }
        // Last resort even without permission (often empty when AppOps denies)
        usageStatsTop()?.let { return it }
        return TopInfo(null, null, "none")
    }

    fun detectionMode(): String =
        if (UsageAccessHelper.hasUsageAccess(context)) "usage_stats" else "heuristic"

    @Suppress("DEPRECATION")
    private fun runningTasksTop(): TopInfo? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(3)
            if (tasks.isNullOrEmpty()) return null
            val ti = tasks[0]
            val pkg = ti.topActivity?.packageName
            val cls = ti.topActivity?.className
            if (pkg.isNullOrBlank()) return null
            TopInfo(pkg, cls, "runningTasks")
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "runningTasks: ${e.message}")
            null
        }
    }

    private fun foregroundProcessTop(): TopInfo? {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val procs = am.runningAppProcesses ?: return null
            val fg = procs.firstOrNull {
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND ||
                    it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
            } ?: return null
            val pkg = fg.pkgList?.firstOrNull() ?: fg.processName
            TopInfo(pkg, null, "fgProcess")
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "fgProcess: ${e.message}")
            null
        }
    }

    /** Prefer UsageEvents MOVE_TO_FOREGROUND for fresher top app on API 28+. */
    private fun usageEventsTop(): TopInfo? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val end = System.currentTimeMillis()
            val begin = end - 60_000
            val events = usm.queryEvents(begin, end) ?: return null
            val event = UsageEvents.Event()
            var lastPkg: String? = null
            var lastClass: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    event.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) {
                    lastPkg = event.packageName
                    lastClass = event.className
                }
            }
            if (lastPkg.isNullOrBlank()) return null
            TopInfo(lastPkg, lastClass, "usageEvents")
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "usageEvents: ${e.message}")
            null
        }
    }

    private fun usageStatsTop(): TopInfo? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return null
            val end = System.currentTimeMillis()
            val begin = end - 15_000
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, begin, end)
            if (stats.isNullOrEmpty()) return null
            val top = stats.maxByOrNull { it.lastTimeUsed } ?: return null
            if (top.packageName.isNullOrBlank()) return null
            // Activity unknown from queryUsageStats — mark so LockActivity check stays strict
            TopInfo(top.packageName, "unknown", "usageStats")
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "usageStats: ${e.message}")
            null
        }
    }
}

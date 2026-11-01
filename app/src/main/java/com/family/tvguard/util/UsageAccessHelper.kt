package com.family.tvguard.util

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.family.tvguard.TvGuardApp

/**
 * PACKAGE_USAGE_STATS / AppOps check + settings launch helpers for Hisense Android 9.
 * Soft-fail everywhere: denied permission must never brick the lock path.
 */
object UsageAccessHelper {

    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "hasUsageAccess: ${e.message}")
            false
        }
    }

    /** Best-effort open Usage Access settings. Returns false if no activity handles it. */
    fun openUsageAccessSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
            },
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.d(TvGuardApp.TAG, "openUsageAccessSettings try failed: ${e.message}")
            }
        }
        return false
    }

    fun statusLabel(context: Context): String =
        if (hasUsageAccess(context)) "已开启（UsageStats 可用）" else "未开启（回退 runningTasks）"
}

package com.family.tvguard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.receiver.KeepAliveReceiver

/**
 * AlarmManager-based restart nudges for GuardService.
 * OEM (Hisense/VIDAA) may still kill the process; whitelist in 启动管理 is required.
 */
object KeepAliveScheduler {
    const val INTERVAL_MS = 5L * 60_000 // 5 minutes
    private const val REQ_CODE = 7101

    fun schedule(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = pending(context)
            val trigger = SystemClock.elapsedRealtime() + INTERVAL_MS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
            Log.d(TvGuardApp.TAG, "KeepAlive scheduled +${INTERVAL_MS}ms")
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "KeepAlive schedule failed: ${e.message}")
        }
    }

    fun cancel(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context))
        } catch (_: Exception) {}
    }

    private fun pending(context: Context): PendingIntent {
        val i = Intent(context, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_PING
        }
        return PendingIntent.getBroadcast(
            context,
            REQ_CODE,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

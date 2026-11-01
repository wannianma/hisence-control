package com.family.tvguard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.receiver.KeepAliveReceiver

/**
 * Restart nudges for [GuardService] without Device Owner.
 *
 * - Exact non-wakeup alarms: fire while the TV is on, and flush when it wakes
 *   from suspend, without turning the panel on by itself.
 * - A persisted one-shot job plus a 15-minute periodic job. JobScheduler keeps
 *   these across reboot even if Hisense never delivers BOOT_COMPLETED.
 *
 * 启动管理 whitelist is still the fallback when the OEM blocks both.
 */
object KeepAliveScheduler {
    /** Heartbeat while the process is alive. Refreshed so it only fires if ticks stop. */
    const val INTERVAL_MS = 30_000L

    /** First retry after boot, destroy, or a failed start. */
    const val FAST_RETRY_MS = 8_000L

    private const val RESCHEDULE_GAP_MS = 12_000L
    private const val REQ_HEARTBEAT = 7101
    private const val REQ_FAST = 7102
    private const val JOB_SOON = 7104
    private const val JOB_PERIODIC = 7105
    private const val PERIODIC_MS = 15L * 60_000L

    @Volatile
    private var lastScheduleElapsed = 0L

    fun schedule(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScheduleElapsed < RESCHEDULE_GAP_MS) return
        lastScheduleElapsed = now
        val appCtx = context.applicationContext
        armAlarm(appCtx, INTERVAL_MS, REQ_HEARTBEAT)
        armPersistedJobs(appCtx)
    }

    /** Always arm an 8s retry plus the normal heartbeat. Used at boot and after death. */
    fun scheduleFastRetry(context: Context) {
        val appCtx = context.applicationContext
        lastScheduleElapsed = SystemClock.elapsedRealtime()
        armAlarm(appCtx, FAST_RETRY_MS, REQ_FAST)
        armAlarm(appCtx, INTERVAL_MS, REQ_HEARTBEAT)
        armPersistedJobs(appCtx)
        Log.i(TvGuardApp.TAG, "KeepAlive fast retry +${FAST_RETRY_MS}ms, heartbeat +${INTERVAL_MS}ms")
    }

    fun cancel(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context.applicationContext, REQ_HEARTBEAT))
            am.cancel(pending(context.applicationContext, REQ_FAST))
        } catch (_: Exception) {
        }
        try {
            val js = context.applicationContext.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(JOB_SOON)
            js.cancel(JOB_PERIODIC)
        } catch (_: Exception) {
        }
    }

    /**
     * ELAPSED_REALTIME (not WAKEUP): do not power the TV on from standby.
     * setExactAndAllowWhileIdle still breaks Doze while the panel is already on.
     */
    private fun armAlarm(context: Context, delayMs: Long, requestCode: Int) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = SystemClock.elapsedRealtime() + delayMs
            val pi = pending(context, requestCode)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.ELAPSED_REALTIME, trigger, pi)
                }
            } catch (se: SecurityException) {
                Log.w(TvGuardApp.TAG, "exact alarm denied, falling back: ${se.message}")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME, trigger, pi)
                } else {
                    am.set(AlarmManager.ELAPSED_REALTIME, trigger, pi)
                }
            }
            Log.d(TvGuardApp.TAG, "KeepAlive alarm req=$requestCode +${delayMs}ms")
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "KeepAlive alarm failed: ${e.message}")
        }
    }

    private fun pending(context: Context, requestCode: Int): PendingIntent {
        val i = Intent(context, KeepAliveReceiver::class.java).apply {
            action = KeepAliveReceiver.ACTION_PING
        }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun armPersistedJobs(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, GuardRestartJob::class.java)
            // Only schedule when missing. Replacing a persisted job every heartbeat
            // rewrites jobs.xml and pushes the deadline out so it never fires.
            if (js.getPendingJob(JOB_SOON) == null) {
                armSoonJob(context)
            }
            if (js.getPendingJob(JOB_PERIODIC) == null) {
                val period = maxOf(PERIODIC_MS, JobInfo.getMinPeriodMillis())
                val periodic = JobInfo.Builder(JOB_PERIODIC, component)
                    .setPersisted(true)
                    .setPeriodic(period)
                    .build()
                val periodicResult = js.schedule(periodic)
                Log.i(TvGuardApp.TAG, "KeepAlive periodic job scheduled result=$periodicResult period=$period")
            }
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "KeepAlive job schedule failed: ${e.message}")
        }
    }

    private fun armSoonJob(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, GuardRestartJob::class.java)
            val soon = JobInfo.Builder(JOB_SOON, component)
                .setPersisted(true)
                .setMinimumLatency(20_000L)
                .setOverrideDeadline(60_000L)
                .build()
            val soonResult = js.schedule(soon)
            Log.i(TvGuardApp.TAG, "KeepAlive soon job scheduled result=$soonResult")
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "KeepAlive soon job failed: ${e.message}")
        }
    }
}

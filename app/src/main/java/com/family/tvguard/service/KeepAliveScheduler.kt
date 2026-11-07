package com.family.tvguard.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.family.tvguard.TvGuardApp
import com.family.tvguard.receiver.KeepAliveReceiver
import com.family.tvguard.ui.LockActivity

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
    /** Rolling wall-clock alarms. Refreshed while we are alive; become overdue during standby. */
    private const val REQ_RTC_EXACT = 7106
    private const val REQ_RTC_INEXACT = 7107
    /** Planted on every screen-off. schedule() must not push this one back out. */
    private const val REQ_STANDBY_EXACT = 7108
    private const val REQ_STANDBY_INEXACT = 7109
    /** System-delivered activity. Hisense drops startActivity from a service. */
    private const val REQ_LOCK_ACTIVITY = 7110
    private const val PERIODIC_MS = 15L * 60_000L
    private const val RTC_SENTINEL_MS = 20_000L
    private const val STANDBY_QUICK_MS = 2_000L
    /** Re-arm while the panel stays off but the CPU is still running. Not a 1s storm. */
    private const val STANDBY_REQUEUE_MS = 8_000L

    @Volatile
    private var lastScheduleElapsed = 0L

    /** Wall clock. elapsedRealtime freezes across MediaTek IPO, so it cannot debounce this. */
    @Volatile
    private var lastStandbyArmWallMs = 0L

    fun schedule(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScheduleElapsed < RESCHEDULE_GAP_MS) return
        lastScheduleElapsed = now
        val appCtx = context.applicationContext
        armAlarm(appCtx, INTERVAL_MS, REQ_HEARTBEAT)
        armRtc(appCtx, RTC_SENTINEL_MS, REQ_RTC_EXACT, exact = true)
        armRtc(appCtx, RTC_SENTINEL_MS, REQ_RTC_INEXACT, exact = false)
        armPersistedJobs(appCtx)
    }

    /** Always arm an 8s retry plus the normal heartbeat. Used at boot and after death. */
    fun scheduleFastRetry(context: Context) {
        val appCtx = context.applicationContext
        lastScheduleElapsed = SystemClock.elapsedRealtime()
        armAlarm(appCtx, FAST_RETRY_MS, REQ_FAST)
        armAlarm(appCtx, INTERVAL_MS, REQ_HEARTBEAT)
        armRtc(appCtx, FAST_RETRY_MS, REQ_RTC_EXACT, exact = true)
        armRtc(appCtx, FAST_RETRY_MS, REQ_RTC_INEXACT, exact = false)
        armPersistedJobs(appCtx)
        Log.i(TvGuardApp.TAG, "KeepAlive fast retry +${FAST_RETRY_MS}ms, heartbeat +${INTERVAL_MS}ms")
    }

    /**
     * Call on every SCREEN_OFF / IPO shutdown, including the 2nd and later cycles.
     * Wall-clock, non-wakeup: standby advances RTC even when MTK freezes elapsedRealtime,
     * and the panel is not powered on by itself.
     * Separate request codes so the live heartbeat cannot postpone this alarm.
     */
    fun armForStandby(context: Context, fromAlarmWhileOff: Boolean = false) {
        val now = System.currentTimeMillis()
        // Screen-off and the service tick both call this. Don't let the second call
        // replace a 2s alarm with a later one.
        // A firing alarm must always plant the next one: during IPO, elapsedRealtime
        // does not move, and the old elapsed debounce dropped that follow-up.
        if (!fromAlarmWhileOff && now - lastStandbyArmWallMs < 1_500L) return
        lastStandbyArmWallMs = now
        val delay = if (fromAlarmWhileOff) STANDBY_REQUEUE_MS else STANDBY_QUICK_MS
        val appCtx = context.applicationContext
        armRtc(appCtx, delay, REQ_STANDBY_EXACT, exact = true)
        armRtc(appCtx, delay, REQ_STANDBY_INEXACT, exact = false)
        armStandbyJob(appCtx)
        Log.i(TvGuardApp.TAG, "Standby sentinel armed +${delay}ms RTC (non-wakeup)")
    }

    /**
     * Raise the lock only when the panel is already on.
     * Tries startActivity from the caller (boot/alarm receivers still have a
     * short start exemption on Android 9) and also asks AlarmManager to deliver
     * the activity, because Hisense ignores startActivity from GuardService.
     */
    fun bringLockToFront(context: Context) {
        val appCtx = context.applicationContext
        val interactive = (appCtx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        if (!interactive) return
        val app = appCtx as? TvGuardApp ?: return
        if (!app.ensurePrefs()) return
        if (!app.prefs.isEffectivelyLocked()) return
        try {
            appCtx.startActivity(LockActivity.intent(appCtx))
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "start LockActivity failed: ${e.message}", e)
        }
        armLockActivityAlarm(appCtx)
    }

    fun cancel(context: Context) {
        try {
            val am = context.applicationContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(pending(context.applicationContext, REQ_HEARTBEAT))
            am.cancel(pending(context.applicationContext, REQ_FAST))
            am.cancel(pending(context.applicationContext, REQ_RTC_EXACT))
            am.cancel(pending(context.applicationContext, REQ_RTC_INEXACT))
            am.cancel(pending(context.applicationContext, REQ_STANDBY_EXACT))
            am.cancel(pending(context.applicationContext, REQ_STANDBY_INEXACT))
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

    /** RTC, not ELAPSED_REALTIME_WAKEUP. Overdue alarms flush on the next user wake. */
    private fun armRtc(context: Context, delayMs: Long, requestCode: Int, exact: Boolean) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = System.currentTimeMillis() + delayMs
            val pi = pending(context, requestCode)
            if (!exact) {
                am.set(AlarmManager.RTC, trigger, pi)
                return
            }
            // setExact, not setExactAndAllowWhileIdle. The idle-exact quota is about
            // one a minute; refreshing it from the heartbeat was delaying the standby alarm.
            // Overdue setExact(RTC) flushes when the user turns the panel on, and does not
            // wake a suspended TV by itself.
            try {
                am.setExact(AlarmManager.RTC, trigger, pi)
            } catch (se: Exception) {
                Log.w(TvGuardApp.TAG, "RTC exact denied req=$requestCode: ${se.message}")
                am.set(AlarmManager.RTC, trigger, pi)
            }
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "RTC alarm failed req=$requestCode: ${e.message}")
        }
    }

    /** System starts the activity. A service startActivity is dropped on this TV. */
    private fun armLockActivityAlarm(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val trigger = System.currentTimeMillis() + 300L
            val pi = PendingIntent.getActivity(
                context,
                REQ_LOCK_ACTIVITY,
                LockActivity.intent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExact(AlarmManager.RTC, trigger, pi)
            } catch (se: Exception) {
                Log.w(TvGuardApp.TAG, "lock activity exact denied: ${se.message}")
                am.set(AlarmManager.RTC, trigger, pi)
            }
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "lock activity alarm failed: ${e.message}")
        }
    }

    /** Replace the one-shot so its deadline passes during this standby and runs on the next wake. */
    private fun armStandbyJob(context: Context) {
        try {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val component = ComponentName(context, GuardRestartJob::class.java)
            val soon = JobInfo.Builder(JOB_SOON, component)
                .setPersisted(true)
                .setMinimumLatency(1_000L)
                .setOverrideDeadline(8_000L)
                .build()
            val result = js.schedule(soon)
            Log.i(TvGuardApp.TAG, "Standby job armed result=$result")
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "Standby job failed: ${e.message}")
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

    fun armSoonJob(context: Context) {
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

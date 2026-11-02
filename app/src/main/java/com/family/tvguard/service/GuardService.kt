package com.family.tvguard.service

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.family.tvguard.BuildConfig
import com.family.tvguard.R
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.net.AdminHttpServer
import com.family.tvguard.ui.LockActivity
import com.family.tvguard.ui.MainActivity
import com.family.tvguard.util.UsageAccessHelper

/**
 * Foreground service: LAN HTTP + TopActivityWatcher + M2 quiz timer + keepalive schedule.
 */
class GuardService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var http: AdminHttpServer? = null
    private var watcher: TopActivityWatcher? = null
    private var relaunchAttempts = 0
    private var lastLockAlarmElapsed = 0L
    /** False until we have seen the panel on, so the first tick after a wake forces the lock. */
    private var sawInteractive = false
    private var standbyArmedWhileOff = false
    /** Wall clock of the previous tick. A multi-second jump means IPO standby, even if elapsedRealtime froze. */
    private var lastTickWallMs = 0L

    @Volatile var lastTopSource: String = "none"
        private set
    @Volatile var lastTopPackage: String? = null
        private set

    private val tick = object : Runnable {
        override fun run() {
            try {
                onTick()
            } catch (e: Exception) {
                Log.e(TvGuardApp.TAG, "tick error: ${e.message}", e)
            }
            handler.postDelayed(this, GuardPrefs.WATCHER_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createChannel()
        // Must call startForeground even if we bail out — otherwise startForegroundService crashes.
        promoteForeground("守护运行中")
        val app = application as TvGuardApp
        if (!app.ensurePrefs()) {
            Log.w(TvGuardApp.TAG, "GuardService created before user unlock; retry after unlock")
            KeepAliveScheduler.scheduleFastRetry(this)
            stopSelf()
            return
        }
        val prefs = app.prefs
        prefs.ensureParentToken()
        if (!UsageAccessHelper.hasUsageAccess(this)) {
            Log.w(
                TvGuardApp.TAG,
                "Usage Access missing at service start — guarding anyway, top-app detection falls back. " +
                    "开启：设置 → 特殊应用权限 → 使用情况访问权限 → 家庭电视锁"
            )
        }
        try {
            http = AdminHttpServer(prefs) { reason ->
                Log.i(TvGuardApp.TAG, "HTTP state change: $reason")
                handler.post {
                    enforceLockIfNeeded(force = true)
                    handler.postDelayed({ enforceLockIfNeeded(force = true) }, 400)
                    handler.postDelayed({ enforceLockIfNeeded(force = true) }, 1200)
                    handler.postDelayed({ enforceLockIfNeeded(force = true) }, 2500)
                }
            }
            http?.start()
            Log.i(TvGuardApp.TAG, "HTTP listening on :${GuardPrefs.HTTP_PORT}")
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "HTTP start failed: ${e.message}", e)
        }
        watcher = TopActivityWatcher(this)
        KeepAliveScheduler.schedule(this)
        handler.post(tick)
        val graceLeft = bootGraceRemaining()
        handler.postDelayed({ enforceLockIfNeeded(force = false) }, graceLeft + 300L)
        Log.i(
            TvGuardApp.TAG,
            "GuardService created usageAccess=${UsageAccessHelper.hasUsageAccess(this)} " +
                "mode=${watcher?.detectionMode()} bootGraceLeft=${graceLeft}ms"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // System can recreate a killed FGS before onCreate's startForeground on some OEMs.
        createChannel()
        promoteForeground("守护运行中")
        val app = application as TvGuardApp
        if (!app.ensurePrefs()) {
            KeepAliveScheduler.scheduleFastRetry(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SHOW_LOCK) {
            handler.post {
                if (!isPanelInteractive()) {
                    LockActivity.clearResumed()
                    sawInteractive = false
                    standbyArmedWhileOff = true
                    KeepAliveScheduler.armForStandby(this)
                } else {
                    sawInteractive = true
                    standbyArmedWhileOff = false
                    enforceLockIfNeeded(force = true)
                }
            }
        }
        KeepAliveScheduler.schedule(this)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        try { http?.stop() } catch (_: Exception) {}
        http = null
        watcher = null
        if (instance === this) instance = null
        Log.w(TvGuardApp.TAG, "GuardService destroyed — scheduling fast restart")
        // Alarm only. Starting the service from onDestroy races the teardown.
        KeepAliveScheduler.scheduleFastRetry(applicationContext)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TvGuardApp.TAG, "onTaskRemoved — re-arm guard")
        KeepAliveScheduler.scheduleFastRetry(applicationContext)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onTick() {
        val prefs = TvGuardApp.instance.prefs
        if (prefs.isFullUnlockActive()) {
            // Thorough unlock: never re-lock from quota/quiz; clear stray quiz flags.
            if (prefs.quizPending) prefs.quizPending = false
        } else {
            if (!prefs.locked && !prefs.isInUnlockWindow() && !prefs.quizPending) {
                Log.i(TvGuardApp.TAG, "Unlock window expired - lock")
                prefs.lockNow()
            }
            if (prefs.remainingQuotaMs() <= 0L && !prefs.isInUnlockWindow() && !prefs.quizPending) {
                Log.i(TvGuardApp.TAG, "Daily quota exhausted - lock")
                prefs.lockNow()
            }
        }

        // Watch-time: only credit real content while unlocked. Never count LockActivity,
        // MainActivity, or other own-UI, and never count while quiz-locked.
        val top = watcher?.topInfo()
        lastTopSource = top?.source ?: lastTopSource
        lastTopPackage = top?.packageName ?: lastTopPackage
        val watchingContent =
            prefs.isInUnlockWindow() &&
                !prefs.quizPending &&
                !prefs.isEffectivelyLocked() &&
                top?.packageName != null &&
                top.packageName != packageName

        val interactive = isPanelInteractive()
        if (!interactive) {
            // Standby / panel off. Do not credit watch time, and do not trust isResumed:
            // MTK IPO freezes the process without onPause, so the next wake would skip the lock.
            sawInteractive = false
            prefs.stopWatchSession()
            LockActivity.clearResumed()
            if (!standbyArmedWhileOff) {
                standbyArmedWhileOff = true
                KeepAliveScheduler.armForStandby(this)
                cancelSystemLockAlarm()
            }
            KeepAliveScheduler.schedule(this)
            updateNotification()
            return
        }
        val wallNow = System.currentTimeMillis()
        val wallGap = if (lastTickWallMs == 0L) 0L else wallNow - lastTickWallMs
        lastTickWallMs = wallNow
        // 1.5s ticks. A gap this large means the process was frozen in standby (2nd IPO included).
        val resumedFromSleep = wallGap > 5_000L
        val woke = !sawInteractive || resumedFromSleep
        sawInteractive = true
        standbyArmedWhileOff = false
        if (woke) {
            // Frozen IPO resume: isResumed can still be true even though the launcher is on top.
            LockActivity.clearResumed()
        }

        if (watchingContent) {
            prefs.ensureWatchSession()
            prefs.flushSessionUsage()
            prefs.checkAndTriggerQuiz()
        } else {
            prefs.stopWatchSession()
        }

        // woke: 2nd and later IPO resumes must lock even inside the 8s boot grace.
        enforceLockIfNeeded(force = woke)
        KeepAliveScheduler.schedule(this)
        updateNotification()
    }

    private fun isPanelInteractive(): Boolean =
        (getSystemService(POWER_SERVICE) as PowerManager).isInteractive

    private fun enforceLockIfNeeded(force: Boolean) {
        val prefs = TvGuardApp.instance.prefs
        if (!prefs.isEffectivelyLocked()) {
            relaunchAttempts = 0
            lastLockAlarmElapsed = 0L
            cancelSystemLockAlarm()
            return
        }

        val graceLeft = bootGraceRemaining()
        if (inBootGrace(force)) {
            Log.d(TvGuardApp.TAG, "device boot grace ${graceLeft}ms left, defer relaunch")
            armSystemLockAlarm(graceLeft + 400L)
            return
        }

        val info = watcher?.topInfo()
        val topPkg = info?.packageName
        val topAct = info?.activityClass
        lastTopSource = info?.source ?: "none"
        lastTopPackage = topPkg
        // LockActivity.onResume is authoritative. Usage events are often empty right after boot,
        // and "unknown" from queryUsageStats must not count as the lock UI.
        val isLock = LockActivity.isResumed ||
            (topPkg == packageName &&
                topAct != null &&
                topAct.contains("LockActivity"))

        if (force || !isLock) {
            Log.i(TvGuardApp.TAG, "bring LockActivity force=$force top=$topPkg/$topAct src=$lastTopSource resumed=${LockActivity.isResumed}")
            bringLockToFront()
            armSystemLockAlarm(1_500L)
            relaunchAttempts++
            if (!isLock && relaunchAttempts <= 8) {
                // force=false: once LockActivity is resumed, stop relaunching (quiz focus).
                handler.postDelayed({ enforceLockIfNeeded(force = false) }, 800)
            }
        } else {
            relaunchAttempts = 0
        }
    }

    /** Grace applies only to device uptime, and never to an explicit show-lock. */
    private fun inBootGrace(force: Boolean): Boolean {
        if (force) return false
        return bootGraceRemaining() > 0L
    }

    private fun bootGraceRemaining(): Long {
        val uptime = SystemClock.elapsedRealtime()
        return (GuardPrefs.BOOT_GRACE_MS - uptime).coerceAtLeast(0L)
    }

    private fun bringLockToFront() {
        try {
            val intent = LockActivity.intent(this).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TvGuardApp.TAG, "start LockActivity failed: ${e.message}", e)
        }
        try {
            @Suppress("DEPRECATION")
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val tasks = am.getRunningTasks(8)
            val lockTask = tasks?.firstOrNull {
                it.topActivity?.className?.contains("LockActivity") == true
            }
            if (lockTask != null) {
                am.moveTaskToFront(lockTask.id, 0)
            }
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "moveTaskToFront: ${e.message}")
        }
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_LOCK_ID, buildLockFullscreenNotification())
        } catch (e: Exception) {
            Log.d(TvGuardApp.TAG, "fullscreen notif: ${e.message}")
        }
    }

    /**
     * System-delivered activity PendingIntent. On API 28 this still runs when a
     * background startActivity from the service is ignored by the OEM.
     * Non-wakeup so standby does not turn the panel on.
     */
    private fun armSystemLockAlarm(delayMs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (lastLockAlarmElapsed != 0L && now - lastLockAlarmElapsed < 8_000L) return
        lastLockAlarmElapsed = now
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            // Wall clock, not elapsed. MTK IPO can freeze elapsedRealtime across standby.
            val trigger = System.currentTimeMillis() + delayMs.coerceAtLeast(500L)
            val pi = lockActivityPending()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC, trigger, pi)
                } else {
                    @Suppress("DEPRECATION")
                    am.setExact(AlarmManager.RTC, trigger, pi)
                }
            } catch (se: SecurityException) {
                Log.w(TvGuardApp.TAG, "lock alarm exact denied: ${se.message}")
                am.set(AlarmManager.RTC, trigger, pi)
            }
            Log.i(TvGuardApp.TAG, "lock activity alarm +${delayMs}ms")
        } catch (e: Exception) {
            Log.w(TvGuardApp.TAG, "lock alarm failed: ${e.message}")
        }
    }

    private fun cancelSystemLockAlarm() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            am.cancel(lockActivityPending())
        } catch (_: Exception) {
        }
    }

    private fun lockActivityPending(): PendingIntent {
        return PendingIntent.getActivity(
            this,
            REQ_LOCK_ALARM,
            LockActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.channel_guard),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_LOCK,
                    "锁屏弹出",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    setSound(null, null)
                    enableVibration(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
        }
    }

    private fun promoteForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pi)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun buildLockFullscreenNotification(): Notification {
        val full = PendingIntent.getActivity(
            this, 2,
            LockActivity.intent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_LOCK)
            .setContentTitle("电视已锁定")
            .setContentText("点击返回锁屏 / 答题")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification() {
        val prefs = TvGuardApp.instance.prefs
        val usage = if (UsageAccessHelper.hasUsageAccess(this)) "Usage✓" else "Usage✗"
        val text = when {
            prefs.isFullUnlockActive() ->
                "彻底解锁 ${prefs.remainingFullUnlockMs() / 60000}分 · $usage"
            prefs.quizPending -> "答题中：$usage"
            prefs.isInUnlockWindow() ->
                "解锁中 ${prefs.remainingUnlockMs() / 60000}分 · $usage"
            prefs.isEffectivelyLocked() -> "已锁屏 · $usage"
            else -> "守护中 · $usage"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        private const val CHANNEL_ID = "tvguard_guard"
        private const val CHANNEL_LOCK = "tvguard_lock_alert"
        private const val NOTIF_ID = 1001
        private const val NOTIF_LOCK_ID = 1002
        private const val REQ_LOCK_ALARM = 7103
        const val ACTION_SHOW_LOCK = "com.family.tvguard.action.SHOW_LOCK"
        const val VERSION_NAME = BuildConfig.VERSION_NAME
        const val VERSION_CODE = BuildConfig.VERSION_CODE

        @Volatile
        var instance: GuardService? = null
            private set

        fun start(context: Context, showLock: Boolean = false) {
            val appCtx = context.applicationContext
            val app = appCtx as? TvGuardApp
            if (app != null && !app.ensurePrefs()) {
                Log.i(TvGuardApp.TAG, "GuardService.start deferred until user unlock showLock=$showLock")
                KeepAliveScheduler.scheduleFastRetry(appCtx)
                return
            }
            if (instance == null) {
                KeepAliveScheduler.scheduleFastRetry(appCtx)
            } else {
                KeepAliveScheduler.schedule(appCtx)
            }
            val i = Intent(appCtx, GuardService::class.java)
            if (showLock) i.action = ACTION_SHOW_LOCK
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(i)
                } else {
                    appCtx.startService(i)
                }
            } catch (e: Exception) {
                Log.e(TvGuardApp.TAG, "startForegroundService failed: ${e.javaClass.simpleName} ${e.message}", e)
                KeepAliveScheduler.scheduleFastRetry(appCtx)
                try {
                    appCtx.startService(i)
                } catch (e2: Exception) {
                    Log.e(TvGuardApp.TAG, "startService fallback failed: ${e2.message}", e2)
                }
            }
        }
    }
}

package com.family.tvguard.service

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
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
    private var startedElapsed: Long = 0L
    private var relaunchAttempts = 0
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
        startedElapsed = SystemClock.elapsedRealtime()
        createChannel()
        startForeground(NOTIF_ID, buildNotification("守护运行中"))
        val prefs = TvGuardApp.instance.prefs
        prefs.ensureParentToken()
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
        Log.i(
            TvGuardApp.TAG,
            "GuardService created usageAccess=${UsageAccessHelper.hasUsageAccess(this)} mode=${watcher?.detectionMode()}"
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_LOCK) {
            handler.post { enforceLockIfNeeded(force = true) }
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
        Log.w(TvGuardApp.TAG, "GuardService destroyed — scheduling keepalive restart")
        KeepAliveScheduler.schedule(applicationContext)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun onTick() {
        val prefs = TvGuardApp.instance.prefs
        if (!prefs.locked && !prefs.isInUnlockWindow() && !prefs.quizPending) {
            Log.i(TvGuardApp.TAG, "Unlock window expired → lock")
            prefs.lockNow()
        }
        if (prefs.remainingQuotaMs() <= 0L && !prefs.isEffectivelyLocked()) {
            Log.i(TvGuardApp.TAG, "Daily quota exhausted → lock")
            prefs.lockNow()
        }
        if (prefs.isInUnlockWindow() && !prefs.quizPending) {
            prefs.flushSessionUsage()
            prefs.checkAndTriggerQuiz()
        }
        enforceLockIfNeeded(force = false)
        updateNotification()
    }

    private fun enforceLockIfNeeded(force: Boolean) {
        val prefs = TvGuardApp.instance.prefs
        if (!prefs.isEffectivelyLocked()) {
            relaunchAttempts = 0
            return
        }

        val sinceBoot = SystemClock.elapsedRealtime() - startedElapsed
        val uptime = SystemClock.elapsedRealtime()
        val inGrace = uptime < GuardPrefs.BOOT_GRACE_MS || sinceBoot < GuardPrefs.BOOT_GRACE_MS
        if (inGrace && !force) {
            Log.d(TvGuardApp.TAG, "boot grace ${GuardPrefs.BOOT_GRACE_MS}ms, skip relaunch")
            return
        }

        val info = watcher?.topInfo()
        val topPkg = info?.packageName
        val topAct = info?.activityClass
        lastTopSource = info?.source ?: "none"
        lastTopPackage = topPkg
        // Require explicit LockActivity class — never treat "same package / unknown activity" as locked UI
        val isLock =
            topPkg == packageName &&
                topAct != null &&
                topAct.contains("LockActivity")

        if (force || !isLock) {
            Log.i(TvGuardApp.TAG, "bring LockActivity force=$force top=$topPkg/$topAct src=$lastTopSource")
            bringLockToFront()
            relaunchAttempts++
            if (!isLock && relaunchAttempts <= 8) {
                handler.postDelayed({ enforceLockIfNeeded(force = true) }, 800)
            }
        } else {
            relaunchAttempts = 0
        }
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
                }
            )
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
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .setAutoCancel(true)
            .build()
    }

    private fun updateNotification() {
        val prefs = TvGuardApp.instance.prefs
        val usage = if (UsageAccessHelper.hasUsageAccess(this)) "Usage✓" else "Usage✗"
        val text = when {
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
        const val ACTION_SHOW_LOCK = "com.family.tvguard.action.SHOW_LOCK"
        const val VERSION_NAME = "1.0.0-m2.11"

        @Volatile
        var instance: GuardService? = null
            private set

        fun start(context: Context) {
            val i = Intent(context, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }
    }
}

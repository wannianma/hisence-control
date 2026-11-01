package com.family.tvguard.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.family.tvguard.TvGuardApp
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persisted guard state. M1 SharedPreferences + M2 quiz fields + fullUnlock.
 */
class GuardPrefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var locked: Boolean
        get() = sp.getBoolean(KEY_LOCKED, true)
        set(v) = sp.edit().putBoolean(KEY_LOCKED, v).apply()

    var unlockUntilEpochMs: Long
        get() = sp.getLong(KEY_UNLOCK_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_UNLOCK_UNTIL, v).apply()

    /** Parent "thorough unlock": wall-clock window with no quiz / no quota re-lock. */
    var fullUnlockUntilEpochMs: Long
        get() = sp.getLong(KEY_FULL_UNLOCK_UNTIL, 0L)
        set(v) = sp.edit().putLong(KEY_FULL_UNLOCK_UNTIL, v).apply()

    var dailyUsedMs: Long
        get() {
            rolloverIfNeeded()
            return sp.getLong(KEY_DAILY_USED, 0L)
        }
        set(v) = sp.edit().putLong(KEY_DAILY_USED, v.coerceAtLeast(0L)).apply()

    var dailyDate: String
        get() = sp.getString(KEY_DAILY_DATE, "") ?: ""
        set(v) = sp.edit().putString(KEY_DAILY_DATE, v).apply()

    var parentToken: String
        get() = sp.getString(KEY_TOKEN, "") ?: ""
        set(v) = sp.edit().putString(KEY_TOKEN, v).apply()

    var pinHash: String
        get() = sp.getString(KEY_PIN_HASH, "") ?: ""
        set(v) = sp.edit().putString(KEY_PIN_HASH, v).apply()

    var sessionStartEpochMs: Long
        get() = sp.getLong(KEY_SESSION_START, 0L)
        set(v) = sp.edit().putLong(KEY_SESSION_START, v).apply()

    /** True when a quiz must be answered before watching continues. */
    var quizPending: Boolean
        get() = sp.getBoolean(KEY_QUIZ_PENDING, false)
        set(v) = sp.edit().putBoolean(KEY_QUIZ_PENDING, v).apply()

    /** Accumulated watch ms since last quiz clear / unlock. */
    var msSinceLastQuiz: Long
        get() = sp.getLong(KEY_MS_SINCE_QUIZ, 0L)
        set(v) = sp.edit().putLong(KEY_MS_SINCE_QUIZ, v).apply()

    var currentQuizId: String
        get() = sp.getString(KEY_QUIZ_ID, "") ?: ""
        set(v) = sp.edit().putString(KEY_QUIZ_ID, v).apply()

    /** Minutes of watching between quizzes (default 10). */
    var quizIntervalMinutes: Int
        get() = sp.getInt(KEY_QUIZ_INTERVAL_MIN, DEFAULT_QUIZ_INTERVAL_MINUTES).coerceIn(1, 180)
        set(v) = sp.edit().putInt(KEY_QUIZ_INTERVAL_MIN, v.coerceIn(1, 180)).apply()

    /** Daily watch quota in minutes (the "2 hours" limit; default 120). */
    var dailyQuotaMinutes: Int
        get() = sp.getInt(KEY_DAILY_QUOTA_MIN, DEFAULT_DAILY_QUOTA_MINUTES).coerceIn(1, 12 * 60)
        set(v) = sp.edit().putInt(KEY_DAILY_QUOTA_MIN, v.coerceIn(1, 12 * 60)).apply()

    /** Default length (hours) for normal unlock & full unlock buttons/API when hours omitted. */
    var defaultUnlockHours: Double
        get() = sp.getFloat(KEY_DEFAULT_UNLOCK_HOURS, DEFAULT_UNLOCK_HOURS.toFloat()).toDouble()
            .coerceIn(0.25, 24.0)
        set(v) = sp.edit().putFloat(KEY_DEFAULT_UNLOCK_HOURS, v.coerceIn(0.25, 24.0).toFloat()).apply()

    val quizIntervalMs: Long
        get() = quizIntervalMinutes * 60_000L

    val dailyQuotaMs: Long
        get() = dailyQuotaMinutes * 60_000L

    fun ensureParentToken(): String {
        migratePrefsSchemaIfNeeded()
        var t = parentToken
        if (t.isBlank()) {
            t = generateToken()
            parentToken = t
            Log.i(TvGuardApp.TAG, "Generated new parentToken=$t")
        }
        if (pinHash.isBlank()) {
            pinHash = hashPin(DEFAULT_PIN)
            Log.i(TvGuardApp.TAG, "Default PIN set to $DEFAULT_PIN (change later)")
        }
        return t
    }

    /**
     * Schema 2 (m2.12): watch clock no longer counts LockActivity / own UI / status polls.
     * One-shot wipe of inflated dailyUsedMs from the pre-fix bug.
     */
    private fun migratePrefsSchemaIfNeeded() {
        val schema = sp.getInt(KEY_PREFS_SCHEMA, 0)
        if (schema >= PREFS_SCHEMA) return
        val oldUsed = sp.getLong(KEY_DAILY_USED, 0L)
        sp.edit()
            .putInt(KEY_PREFS_SCHEMA, PREFS_SCHEMA)
            .putLong(KEY_DAILY_USED, 0L)
            .putLong(KEY_SESSION_START, 0L)
            .putLong(KEY_MS_SINCE_QUIZ, 0L)
            .commit()
        Log.i(
            TvGuardApp.TAG,
            "prefs schema $schema -> $PREFS_SCHEMA; reset dailyUsedMs (was ${oldUsed}ms)"
        )
    }

    fun isFullUnlockActive(now: Long = System.currentTimeMillis()): Boolean =
        fullUnlockUntilEpochMs > now

    fun isEffectivelyLocked(now: Long = System.currentTimeMillis()): Boolean {
        rolloverIfNeeded(now)
        // Thorough parent unlock: ignore quiz + daily quota until wall window ends.
        if (isFullUnlockActive(now)) return false
        // Quiz lock always wins (even inside normal parent unlock window).
        if (quizPending) return true
        // Active parent unlock window overrides daily quota until it expires.
        if (unlockUntilEpochMs > now) return false
        if (dailyUsedMs >= dailyQuotaMs) return true
        return locked
    }

    fun isInUnlockWindow(now: Long = System.currentTimeMillis()): Boolean =
        unlockUntilEpochMs > now || isFullUnlockActive(now)

    fun lockNow() {
        flushSessionUsage()
        sp.edit()
            .putBoolean(KEY_LOCKED, true)
            .putLong(KEY_UNLOCK_UNTIL, 0L)
            .putLong(KEY_FULL_UNLOCK_UNTIL, 0L)
            .putLong(KEY_SESSION_START, 0L)
            .putBoolean(KEY_QUIZ_PENDING, false)
            .putLong(KEY_MS_SINCE_QUIZ, 0L)
            .putString(KEY_QUIZ_ID, "")
            .commit()
    }

    fun unlockHours(hours: Double) {
        val now = System.currentTimeMillis()
        rolloverIfNeeded(now)
        unlockUntilEpochMs = now + (hours * 3600_000).toLong()
        // Normal unlock is not thorough — clear any leftover fullUnlock session.
        fullUnlockUntilEpochMs = 0L
        locked = false
        sessionStartEpochMs = now
        quizPending = false
        msSinceLastQuiz = 0L
        currentQuizId = ""
    }

    /**
     * Parent thorough unlock: wall-clock [hours] window.
     * No quiz, daily quota does not re-lock, LockActivity stays dismissed.
     */
    fun fullUnlockHours(hours: Double = defaultUnlockHours) {
        val now = System.currentTimeMillis()
        rolloverIfNeeded(now)
        val until = now + (hours * 3600_000).toLong()
        unlockUntilEpochMs = until
        fullUnlockUntilEpochMs = until
        locked = false
        sessionStartEpochMs = now
        quizPending = false
        msSinceLastQuiz = 0L
        currentQuizId = ""
        Log.i(TvGuardApp.TAG, "fullUnlock until $until (${hours}h wall)")
    }

    fun cancelUnlock() {
        lockNow()
    }

    /**
     * Credit elapsed watch time since [sessionStartEpochMs], then advance the cursor.
     * Only call while the child is actually watching (unlocked, not on LockActivity / our UI).
     */
    fun flushSessionUsage(now: Long = System.currentTimeMillis()) {
        clampDailyUsedToElapsedDay(now)
        val start = sessionStartEpochMs
        if (start > 0L && now > start) {
            val delta = now - start
            // Cap a single flush so a stuck clock / long sleep cannot dump hours at once.
            val credited = delta.coerceAtMost(WATCHER_INTERVAL_MS * 8)
            dailyUsedMs = dailyUsedMs + credited
            // During fullUnlock, do not accumulate toward the next quiz.
            if (!quizPending && isInUnlockWindow(now) && !isFullUnlockActive(now)) {
                msSinceLastQuiz = msSinceLastQuiz + credited
            }
            sessionStartEpochMs = now
            clampDailyUsedToElapsedDay(now)
        }
    }

    /** Pause the watch clock without crediting idle / lock / own-UI time. */
    fun stopWatchSession() {
        sessionStartEpochMs = 0L
    }

    fun ensureWatchSession(now: Long = System.currentTimeMillis()) {
        if (sessionStartEpochMs <= 0L) {
            sessionStartEpochMs = now
        }
    }

    /** Watch minutes cannot exceed wall-clock since local midnight (Asia/Shanghai). */
    fun clampDailyUsedToElapsedDay(now: Long = System.currentTimeMillis()) {
        rolloverIfNeeded(now)
        val maxPossible = (now - startOfDayEpochMs(now)).coerceAtLeast(0L)
        val used = sp.getLong(KEY_DAILY_USED, 0L)
        if (used > maxPossible) {
            sp.edit().putLong(KEY_DAILY_USED, maxPossible).commit()
            Log.w(TvGuardApp.TAG, "Clamped dailyUsedMs $used -> $maxPossible (since midnight)")
        }
    }

    /** Call while unlocked: if watch since last quiz >= interval, require quiz. */
    fun checkAndTriggerQuiz(now: Long = System.currentTimeMillis()): Boolean {
        if (isFullUnlockActive(now)) {
            // Suppress quiz entirely during thorough unlock.
            if (quizPending) {
                quizPending = false
                currentQuizId = ""
                msSinceLastQuiz = 0L
            }
            return false
        }
        if (quizPending) return true
        if (!isInUnlockWindow(now)) return false
        flushSessionUsage(now)
        if (msSinceLastQuiz >= quizIntervalMs) {
            quizPending = true
            sessionStartEpochMs = 0L
            if (currentQuizId.isBlank()) {
                currentQuizId = QuizBank.next().id
            }
            Log.i(TvGuardApp.TAG, "Quiz due after ${msSinceLastQuiz}ms watch")
            return true
        }
        return false
    }

    fun ensureQuizSelected(): QuizQuestion {
        val id = currentQuizId
        val existing = QuizBank.questions.find { it.id == id }
        if (existing != null) return existing
        val q = QuizBank.next()
        currentQuizId = q.id
        return q
    }

    fun submitQuizAnswer(answerIndex: Int): Boolean {
        val q = ensureQuizSelected()
        if (answerIndex == q.answerIndex) {
            val now = System.currentTimeMillis()
            quizPending = false
            msSinceLastQuiz = 0L
            currentQuizId = ""
            locked = false
            if (!isInUnlockWindow(now)) {
                val remainingQuota = (dailyQuotaMs - dailyUsedMs).coerceAtLeast(0L)
                unlockUntilEpochMs = now + maxOf(remainingQuota, quizIntervalMs)
            }
            sessionStartEpochMs = now
            Log.i(TvGuardApp.TAG, "Quiz ${q.id} correct; unlocked until ${unlockUntilEpochMs}")
            return true
        }
        // Wrong: pick a new question so they keep trying
        currentQuizId = QuizBank.next(excludeId = q.id).id
        Log.i(TvGuardApp.TAG, "Quiz ${q.id} wrong -> next ${currentQuizId}")
        return false
    }

    fun remainingUnlockMs(now: Long = System.currentTimeMillis()): Long =
        (unlockUntilEpochMs - now).coerceAtLeast(0L)

    fun remainingFullUnlockMs(now: Long = System.currentTimeMillis()): Long =
        (fullUnlockUntilEpochMs - now).coerceAtLeast(0L)

    fun remainingQuotaMs(now: Long = System.currentTimeMillis()): Long {
        rolloverIfNeeded(now)
        clampDailyUsedToElapsedDay(now)
        // Do not flush here — status polls / quota checks must not invent watch time.
        return (dailyQuotaMs - dailyUsedMs).coerceAtLeast(0L)
    }

    /** Snapshot used today without advancing the session clock. */
    fun dailyUsedMsSnapshot(now: Long = System.currentTimeMillis()): Long {
        rolloverIfNeeded(now)
        clampDailyUsedToElapsedDay(now)
        return dailyUsedMs
    }

    /**
     * Apply parent config. Null fields are left unchanged.
     * Returns the effective values after write.
     */
    fun updateConfig(
        quizIntervalMinutes: Int? = null,
        dailyQuotaMinutes: Int? = null,
        defaultUnlockHours: Double? = null,
    ): Triple<Int, Int, Double> {
        quizIntervalMinutes?.let { this.quizIntervalMinutes = it }
        dailyQuotaMinutes?.let { this.dailyQuotaMinutes = it }
        defaultUnlockHours?.let { this.defaultUnlockHours = it }
        return Triple(this.quizIntervalMinutes, this.dailyQuotaMinutes, this.defaultUnlockHours)
    }

    fun verifyPin(pin: String): Boolean {
        if (pin.isBlank()) return false
        return hashPin(pin) == pinHash
    }

    fun tokenMatches(authHeader: String?): Boolean {
        if (authHeader.isNullOrBlank()) return false
        val expected = "Bearer $parentToken"
        return authHeader.trim() == expected || authHeader.trim() == parentToken
    }

    private fun rolloverIfNeeded(now: Long = System.currentTimeMillis()) {
        val today = localDateString(now)
        if (dailyDate != today) {
            sp.edit()
                .putString(KEY_DAILY_DATE, today)
                .putLong(KEY_DAILY_USED, 0L)
                .putLong(KEY_SESSION_START, 0L)
                .putLong(KEY_MS_SINCE_QUIZ, 0L)
                .apply()
            Log.i(TvGuardApp.TAG, "Daily usage reset for $today (Asia/Shanghai)")
        }
    }

    companion object {
        const val PREFS = "tvguard_prefs"
        const val HTTP_PORT = 8787
        const val DEFAULT_QUIZ_INTERVAL_MINUTES = 10
        const val DEFAULT_DAILY_QUOTA_MINUTES = 120
        const val DEFAULT_UNLOCK_HOURS = 2.0
        /** Fallback defaults (pre-config); runtime uses instance quizIntervalMs / dailyQuotaMs. */
        const val DAILY_QUOTA_MS = DEFAULT_DAILY_QUOTA_MINUTES * 60_000L
        const val QUIZ_INTERVAL_MS = DEFAULT_QUIZ_INTERVAL_MINUTES * 60_000L
        const val DEFAULT_PIN = "000000"
        /**
         * Only the first seconds of *device* uptime. Do not reuse this as a delay
         * after every service restart — that left the TV unlocked after a kill.
         */
        const val BOOT_GRACE_MS = 8_000L
        const val WATCHER_INTERVAL_MS = 1_500L

        const val PREFS_SCHEMA = 2
        private const val KEY_PREFS_SCHEMA = "prefsSchema"
        private const val KEY_LOCKED = "locked"
        private const val KEY_UNLOCK_UNTIL = "unlockUntilEpochMs"
        private const val KEY_FULL_UNLOCK_UNTIL = "fullUnlockUntilEpochMs"
        private const val KEY_DAILY_USED = "dailyUsedMs"
        private const val KEY_DAILY_DATE = "dailyDate"
        private const val KEY_TOKEN = "parentToken"
        private const val KEY_PIN_HASH = "pinHash"
        private const val KEY_SESSION_START = "sessionStartEpochMs"
        private const val KEY_QUIZ_PENDING = "quizPending"
        private const val KEY_MS_SINCE_QUIZ = "msSinceLastQuiz"
        private const val KEY_QUIZ_ID = "currentQuizId"
        private const val KEY_QUIZ_INTERVAL_MIN = "quizIntervalMinutes"
        private const val KEY_DAILY_QUOTA_MIN = "dailyQuotaMinutes"
        private const val KEY_DEFAULT_UNLOCK_HOURS = "defaultUnlockHours"

        fun generateToken(): String {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        fun hashPin(pin: String): String {
            val md = MessageDigest.getInstance("SHA-256")
            val dig = md.digest(pin.toByteArray(Charsets.UTF_8))
            return dig.joinToString("") { "%02x".format(it) }
        }

        private val SHANGHAI: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

        fun localDateString(epochMs: Long): String {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            fmt.timeZone = SHANGHAI
            return fmt.format(Date(epochMs))
        }

        fun startOfDayEpochMs(epochMs: Long): Long {
            val cal = java.util.Calendar.getInstance(SHANGHAI)
            cal.timeInMillis = epochMs
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
            cal.set(java.util.Calendar.SECOND, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }
    }
}

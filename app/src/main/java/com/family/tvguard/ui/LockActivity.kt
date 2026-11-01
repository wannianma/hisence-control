package com.family.tvguard.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.tvguard.R
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.data.QuizQuestion
import com.family.tvguard.service.GuardService
import com.family.tvguard.util.IpUtils

/**
 * Fullscreen lock. M2 quiz is D-pad friendly: arrows move focus, OK/Enter selects.
 * Focus highlight uses both android focus + selected + slight scale for TV remotes.
 */
class LockActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: GuardPrefs
    private var currentQuiz: QuizQuestion? = null
    private var focusedChoice = 0

    private var wasQuizPending = false

    private val refresh = object : Runnable {
        override fun run() {
            if (isFinishing) return
            if (!prefs.isEffectivelyLocked()) {
                finish()
                return
            }
            val quizNow = prefs.quizPending
            if (quizNow != wasQuizPending) {
                wasQuizPending = quizNow
                updateUi(requestQuizFocus = quizNow)
            } else {
                updateStatusOnly()
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = TvGuardApp.instance.prefs
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        setContentView(R.layout.activity_lock)
        enterImmersive()

        GuardService.start(this)

        val ip = IpUtils.localIpv4() ?: "192.168.x.x"
        findViewById<TextView>(R.id.adminUrl).text =
            "手机打开 http://$ip:${GuardPrefs.HTTP_PORT}/admin/"

        findViewById<TextView>(R.id.tokenHint).text =
            "遥控器：黄框=当前选项，确认键作答 · PIN 家长绕过"

        val btnPin = findViewById<Button>(R.id.btnPin)
        btnPin.setOnClickListener {
            val pin = findViewById<EditText>(R.id.pinInput).text?.toString().orEmpty()
            if (prefs.verifyPin(pin)) {
                val hours = prefs.defaultUnlockHours
                prefs.unlockHours(hours)
                Toast.makeText(this, "已解锁 ${hours} 小时", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "PIN 错误", Toast.LENGTH_SHORT).show()
            }
        }
        btnPin.setOnFocusChangeListener { v, hasFocus ->
            applyFocusVisual(v, hasFocus)
        }

        val choiceIds = listOf(R.id.btnChoice0, R.id.btnChoice1, R.id.btnChoice2, R.id.btnChoice3)
        choiceIds.forEachIndexed { index, id ->
            val btn = findViewById<Button>(id)
            // Ensure custom selector is not overridden by AppCompat tint
            btn.backgroundTintList = null
            btn.setOnClickListener { onQuizChoice(index) }
            btn.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) focusedChoice = index
                applyFocusVisual(v, hasFocus)
                // Keep selected in sync so highlight stays visible even if focus blips
                choiceIds.forEachIndexed { i, cid ->
                    findViewById<Button>(cid).isSelected = hasFocus && i == index
                }
            }
        }

        updateUi(requestQuizFocus = true)
    }

    private fun applyFocusVisual(v: View, hasFocus: Boolean) {
        val scale = if (hasFocus) 1.08f else 1.0f
        v.animate().scaleX(scale).scaleY(scale).setDuration(120L).start()
        v.isSelected = hasFocus
        v.invalidate()
    }

    override fun onResume() {
        super.onResume()
        enterImmersive()
        if (!prefs.isEffectivelyLocked()) {
            finish()
            return
        }
        wasQuizPending = prefs.quizPending
        updateUi(requestQuizFocus = prefs.quizPending)
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        if (prefs.quizPending) {
            handler.post { focusChoice(focusedChoice) }
        }
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            enterImmersive()
            if (prefs.quizPending) focusChoice(focusedChoice)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // stay on lock
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (prefs.quizPending) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    focusChoice(if (focusedChoice < 2) focusedChoice + 2 else focusedChoice - 2)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    focusChoice(if (focusedChoice < 2) focusedChoice + 2 else focusedChoice - 2)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    focusChoice(if (focusedChoice % 2 == 0) focusedChoice + 1 else focusedChoice - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    focusChoice(if (focusedChoice % 2 == 0) focusedChoice + 1 else focusedChoice - 1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER,
                KeyEvent.KEYCODE_BUTTON_A -> {
                    onQuizChoice(focusedChoice)
                    return true
                }
                KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_NUMPAD_1 -> { onQuizChoice(0); return true }
                KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_NUMPAD_2 -> { onQuizChoice(1); return true }
                KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_NUMPAD_3 -> { onQuizChoice(2); return true }
                KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_NUMPAD_4 -> { onQuizChoice(3); return true }
                KeyEvent.KEYCODE_BACK,
                KeyEvent.KEYCODE_ESCAPE -> return true
            }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_ESCAPE -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun focusChoice(index: Int) {
        focusedChoice = index.coerceIn(0, 3)
        val ids = listOf(R.id.btnChoice0, R.id.btnChoice1, R.id.btnChoice2, R.id.btnChoice3)
        ids.forEachIndexed { i, id ->
            val b = findViewById<Button>(id)
            b.isSelected = i == focusedChoice
            if (i != focusedChoice) {
                b.scaleX = 1f
                b.scaleY = 1f
            }
        }
        val btn = findViewById<Button>(ids[focusedChoice])
        btn.isFocusable = true
        btn.isFocusableInTouchMode = true
        btn.requestFocus()
        applyFocusVisual(btn, true)
    }

    private fun onQuizChoice(index: Int) {
        if (!prefs.quizPending) return
        val ok = prefs.submitQuizAnswer(index)
        if (ok) {
            Toast.makeText(this, "答对了，继续看吧", Toast.LENGTH_SHORT).show()
            finish()
        } else {
            Toast.makeText(this, "不对，再试一次", Toast.LENGTH_SHORT).show()
            focusedChoice = 0
            updateUi(requestQuizFocus = true)
        }
    }

    private fun updateUi(requestQuizFocus: Boolean) {
        val quizMode = prefs.quizPending
        val title = findViewById<TextView>(R.id.title)
        val subtitle = findViewById<TextView>(R.id.subtitle)
        val quizPanel = findViewById<LinearLayout>(R.id.quizPanel)
        val pinRow = findViewById<LinearLayout>(R.id.pinRow)
        val pinInput = findViewById<EditText>(R.id.pinInput)

        if (quizMode) {
            title.text = "答题解锁"
            subtitle.text = "黄框高亮当前选项；方向键移动，确认键作答（也可按 1–4）"
            quizPanel.visibility = View.VISIBLE
            pinInput.isFocusable = true
            pinInput.isFocusableInTouchMode = true
            currentQuiz = prefs.ensureQuizSelected()
            val q = currentQuiz!!
            findViewById<TextView>(R.id.quizPrompt).text = q.prompt
            val btns = listOf(
                findViewById<Button>(R.id.btnChoice0),
                findViewById<Button>(R.id.btnChoice1),
                findViewById<Button>(R.id.btnChoice2),
                findViewById<Button>(R.id.btnChoice3),
            )
            q.choices.forEachIndexed { i, text ->
                btns[i].text = "${'A' + i}. $text"
                btns[i].visibility = View.VISIBLE
                btns[i].isFocusable = true
                btns[i].isFocusableInTouchMode = true
                btns[i].backgroundTintList = null
            }
            if (requestQuizFocus) {
                handler.post { focusChoice(focusedChoice) }
            }
        } else {
            title.setText(R.string.lock_title)
            subtitle.setText(R.string.lock_hint)
            quizPanel.visibility = View.GONE
        }

        pinRow.visibility = View.VISIBLE
        updateStatusOnly()
    }

    private fun updateStatusOnly() {
        val usedMin = prefs.dailyUsedMs / 60_000.0
        val quotaMin = prefs.dailyQuotaMinutes.toDouble()
        val quizMin = prefs.msSinceLastQuiz / 60_000.0
        val intervalMin = prefs.quizIntervalMinutes
        findViewById<TextView>(R.id.statusLine).text =
            if (prefs.quizPending) {
                String.format("今日已看 %.0f / %.0f 分钟 · 答题后继续", usedMin, quotaMin)
            } else {
                String.format("今日已看 %.0f / %.0f 分钟 · 距下题约 %.0f/%d 分钟", usedMin, quotaMin, quizMin, intervalMin)
            }
    }

    private fun enterImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN)
    }

    companion object {
        fun intent(ctx: android.content.Context) =
            android.content.Intent(ctx, LockActivity::class.java).apply {
                addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                        android.content.Intent.FLAG_ACTIVITY_NO_USER_ACTION
                )
            }
    }
}
package com.family.tvguard.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.family.tvguard.R
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.service.KeepAliveScheduler
import com.family.tvguard.util.IpUtils
import com.family.tvguard.util.UsageAccessHelper

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = TvGuardApp.instance.prefs
        GuardService.start(this)
        KeepAliveScheduler.schedule(this)

        refreshInfo()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.lockNow()
            startActivity(LockActivity.intent(this))
        }

        findViewById<Button>(R.id.btnUsageAccess).setOnClickListener {
            val opened = UsageAccessHelper.openUsageAccessSettings(this)
            if (!opened) {
                Toast.makeText(
                    this,
                    "无法打开设置页。请用遥控器：设置 → 应用 → 特殊应用权限 → 使用情况访问权限 → 家庭电视锁",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(this, "请在列表中开启「家庭电视锁」的使用情况访问权限", Toast.LENGTH_LONG).show()
            }
        }

        if (prefs.isEffectivelyLocked()) {
            startActivity(LockActivity.intent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
        val prefs = TvGuardApp.instance.prefs
        if (prefs.isEffectivelyLocked()) {
            startActivity(LockActivity.intent(this))
        }
    }

    private fun refreshInfo() {
        val prefs = TvGuardApp.instance.prefs
        val ip = IpUtils.localIpv4() ?: "?"
        val usage = UsageAccessHelper.statusLabel(this)
        findViewById<TextView>(R.id.info).text = buildString {
            appendLine("版本: ${GuardService.VERSION_NAME} (17)")
            appendLine("IP: $ip:${GuardPrefs.HTTP_PORT}")
            appendLine("管理页: http://$ip:${GuardPrefs.HTTP_PORT}/admin/")
            appendLine("Token: ${prefs.parentToken}")
            appendLine("锁定: ${prefs.isEffectivelyLocked()}")
            appendLine("今日已用: ${prefs.dailyUsedMs / 60000} / ${prefs.dailyQuotaMinutes} 分钟")
            appendLine("答题间隔: ${prefs.quizIntervalMinutes} 分钟")
            appendLine("使用情况访问: $usage")
            appendLine("前台检测: ${if (UsageAccessHelper.hasUsageAccess(this@MainActivity)) "UsageStats" else "启发式回退"}")
            appendLine()
            appendLine("不会替换 CleanYourTV 桌面。")
            appendLine("开机保活: BootReceiver + 5 分钟闹钟 sidekick。")
            appendLine("海信请在「启动管理」白名单本应用。")
            appendLine("Device Owner: 未启用（需清机，家庭电视不安全）。")
        }
    }
}

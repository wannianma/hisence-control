package com.family.tvguard.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.family.tvguard.R
import com.family.tvguard.TvGuardApp
import com.family.tvguard.data.GuardPrefs
import com.family.tvguard.service.GuardService
import com.family.tvguard.util.IpUtils

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = TvGuardApp.instance.prefs
        GuardService.start(this)

        val ip = IpUtils.localIpv4() ?: "?"
        findViewById<TextView>(R.id.info).text = buildString {
            appendLine("IP: $ip:${GuardPrefs.HTTP_PORT}")
            appendLine("管理页: http://$ip:${GuardPrefs.HTTP_PORT}/admin/")
            appendLine("Token: ${prefs.parentToken}")
            appendLine("锁定: ${prefs.isEffectivelyLocked()}")
            appendLine("今日已用: ${prefs.dailyUsedMs / 60000} 分钟")
            appendLine()
            appendLine("不会替换 CleanYourTV 桌面。")
            appendLine("海信可能需在「启动管理」白名单本应用。")
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            prefs.lockNow()
            startActivity(LockActivity.intent(this))
        }

        // If already locked, jump straight to lock UI
        if (prefs.isEffectivelyLocked()) {
            startActivity(LockActivity.intent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = TvGuardApp.instance.prefs
        if (prefs.isEffectivelyLocked()) {
            startActivity(LockActivity.intent(this))
        }
    }
}

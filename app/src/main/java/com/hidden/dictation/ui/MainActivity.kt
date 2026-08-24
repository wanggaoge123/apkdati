package com.hidden.dictation.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hidden.dictation.R
import com.hidden.dictation.service.FloatWindowManager
import com.hidden.dictation.service.MainService
import com.hidden.dictation.service.GuardService
import com.hidden.dictation.service.ScreenTimeTracker

/**
 * MainActivity —— 带"开启按钮"的主界面（2026-08-24 新增，需求：有界面、开启后才运行）
 *
 * 设计要点：
 *  - 普通可见 Activity，桌面有图标，进入即看到界面（不再是"无界面隐身 APP"）。
 *  - 顶部显示服务运行状态；中部【开启/停止】按钮：点"开启"才拉起 MainService + GuardService，
 *    点"停止"才停掉（满足用户"开启之后它才会安全返回"——不开不跑）。
 *  - 状态行实时显示"距离下次听写弹窗还有多久"（开服务后先等 2 分钟首次弹窗）。
 *  - "权限设置"按钮跳转 4 项权限引导（PermissionGuideActivity 改造为可从界面重复进入）。
 *  - 底部"其他功能"占位区，后续新增功能从这里扩展。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvNext: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnPermissions: Button

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("dictation_config", Context.MODE_PRIVATE)
    }

    // 主线程 Handler，用于每秒刷新倒计时
    private val uiHandler = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tv_status)
        tvNext = findViewById(R.id.tv_next)
        btnToggle = findViewById(R.id.btn_toggle)
        btnPermissions = findViewById(R.id.btn_permissions)

        btnToggle.setOnClickListener {
            if (isServiceRunning()) {
                stopServices()
            } else {
                startServices()
            }
            refreshStatus()
        }

        btnPermissions.setOnClickListener {
            // 跳转 4 项权限引导（改造后可重复进入，不依赖首次标记）
            startActivity(Intent(this, PermissionGuideActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        uiHandler.postDelayed(tickRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(tickRunnable)
    }

    /** 拉起主服务 + 守护进程（用户点"开启"才执行） */
    private fun startServices() {
        try {
            val i = Intent(this, MainService::class.java)
            startForegroundService(i)
        } catch (_: Exception) {}
        try {
            val g = Intent(this, GuardService::class.java)
            startForegroundService(g)
        } catch (_: Exception) {}
        // 记录"用户已开启"，用于状态判断（非持久保活，仅 UI 参考）
        prefs.edit().putBoolean("user_started", true).apply()
    }

    /** 停止主服务 + 守护进程（用户点"停止"才执行） */
    private fun stopServices() {
        try { stopService(Intent(this, MainService::class.java)) } catch (_: Exception) {}
        try { stopService(Intent(this, GuardService::class.java)) } catch (_: Exception) {}
        FloatWindowManager.destroy()
        prefs.edit().putBoolean("user_started", false).apply()
    }

    /** 服务是否处于运行状态（依据 tracker 是否注入 + 用户标记） */
    private fun isServiceRunning(): Boolean {
        return try {
            FloatWindowManager.ScreenTimeTrackerHolder.isInitialized() &&
                    prefs.getBoolean("user_started", false)
        } catch (_: Exception) { false }
    }

    /** 刷新状态文字 + 倒计时 */
    private fun refreshStatus() {
        val running = isServiceRunning()
        if (running) {
            tvStatus.text = getString(R.string.status_running)
            btnToggle.text = getString(R.string.btn_stop)
            // 显示距离下次弹窗剩余时间
            val leftMs = try {
                FloatWindowManager.ScreenTimeTrackerHolder.tracker.getRemainingMs()
            } catch (_: Exception) { 0L }
            val sec = (leftMs / 1000).toInt()
            tvNext.text = getString(R.string.next_hint_running, sec / 60, sec % 60)
        } else {
            tvStatus.text = getString(R.string.status_stopped)
            btnToggle.text = getString(R.string.btn_start)
            tvNext.text = getString(R.string.next_hint_idle)
        }
    }
}

package com.hidden.dictation.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import kotlinx.coroutines.*

/**
 * ScreenTimeTracker —— 全局亮屏累计计时器（需求二.3 / 四.1）
 *
 * 关键约束（需求二.3）：
 *  - 切换游戏、其他软件、切后台都不会重置计时；
 *  - 仅"屏幕熄灭"暂停累计，"屏幕亮起"继续累计（不是从零开始）；
 *  - 持有 CPU 唤醒锁（PARTIAL_WAKE_LOCK），保证息屏瞬间/系统调度不丢计时精度。
 *
 * 计时达标后通过回调通知外部（由外部调用 JS 桥唤起听写弹窗）。
 * 触发间隔可在设置里选 1/2/3/5 分钟（默认 3 分钟）。
 */
class ScreenTimeTracker(
    private val context: Context,
    private val onThresholdReached: () -> Unit
) {
    companion object {
        // 可选触发档位（分钟）
        val TRIGGER_MINUTES_OPTIONS = listOf(1, 2, 3, 5)
        const val PREFS = "dictation_config"
        const val KEY_TRIGGER_MIN = "trigger_min"
    }

    private var accumulatedMs = 0L          // 累计亮屏毫秒（不重置，跨越 APP 切换）
    private var lastTickTs = 0L             // 上次累加时间戳
    private var isScreenOn = true           // 当前屏幕状态
    private var running = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickerJob: Job? = null

    private val wakeLock: PowerManager.WakeLock by lazy {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "HiddenDictation::ScreenTimeWake")
            .apply { setReferenceCounted(false) }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> {
                    isScreenOn = true
                    lastTickTs = System.currentTimeMillis()
                }
                Intent.ACTION_SCREEN_OFF -> {
                    // 息屏：把已亮屏的时间补进累计，然后暂停
                    if (isScreenOn && lastTickTs > 0) {
                        accumulatedMs += (System.currentTimeMillis() - lastTickTs)
                    }
                    isScreenOn = false
                }
            }
        }
    }

    /** 当前触发阈值（分钟） */
    private fun triggerMs(): Long {
        val min = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TRIGGER_MIN, 3)
        return min * 60_000L
    }

    fun setTriggerMinutes(min: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TRIGGER_MIN, min).apply()
    }

    /** 启动计时（服务/无障碍启动时调用） */
    fun start() {
        if (running) return
        running = true
        isScreenOn = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .isInteractive
        lastTickTs = System.currentTimeMillis()
        if (!wakeLock.isHeld) wakeLock.acquire( /*不超时，由 stop 释放*/ )

        // 注册屏幕广播
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        context.registerReceiver(screenReceiver, filter)

        // 1 秒精度累加（亮屏时累计，息屏时暂停）
        tickerJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                if (isScreenOn && lastTickTs > 0) {
                    accumulatedMs += (now - lastTickTs)
                    lastTickTs = now
                }
                if (accumulatedMs >= triggerMs()) {
                    // 达标：通知外部唤起弹窗，并重置累计（答对后由外部再确认重置，这里先归零重新计时）
                    accumulatedMs = 0L
                    onThresholdReached()
                }
            }
        }
    }

    /** 由外部（答对）显式重置计时（需求三.2：答对后重置全局亮屏计时器） */
    fun reset() {
        accumulatedMs = 0L
        lastTickTs = System.currentTimeMillis()
    }

    /** 取得当前累计（调试/扩展用） */
    fun getAccumulatedMs(): Long = accumulatedMs

    fun stop() {
        running = false
        tickerJob?.cancel()
        tickerJob = null
        try { context.unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        if (wakeLock.isHeld) wakeLock.release()
    }
}

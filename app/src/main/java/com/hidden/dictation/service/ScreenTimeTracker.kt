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
        // 首次延迟（分钟）：服务开启后，亮屏累计达到该值才弹"第一次"听写窗（需求：开服务后先等2分钟）
        const val KEY_FIRST_DELAY_MIN = "first_delay_min"
        const val DEFAULT_FIRST_DELAY_MIN = 2
    }

    private var accumulatedMs = 0L          // 累计亮屏毫秒（不重置，跨越 APP 切换）
    private var lastTickTs = 0L             // 上次累加时间戳
    private var isScreenOn = true           // 当前屏幕状态
    private var running = false
    private var firstPopupDone = false      // 是否已弹过"首次"听写窗

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

    /** 当前触发阈值（分钟，正常循环间隔） */
    private fun triggerMs(): Long {
        val min = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_TRIGGER_MIN, 3)
        return min * 60_000L
    }

    /** 首次延迟阈值（分钟）：开服务后先累计这么久才弹第一次 */
    private fun firstDelayMs(): Long {
        val min = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_FIRST_DELAY_MIN, DEFAULT_FIRST_DELAY_MIN)
        return min * 60_000L
    }

    fun setTriggerMinutes(min: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_TRIGGER_MIN, min).apply()
    }

    fun setFirstDelayMinutes(min: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_FIRST_DELAY_MIN, min).apply()
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
                // 先判断"首次延迟"：开服务后先累计满 firstDelay 才弹第一次
                if (!firstPopupDone) {
                    if (accumulatedMs >= firstDelayMs()) {
                        firstPopupDone = true
                        accumulatedMs = 0L   // 首次达标后归零，进入正常循环间隔
                        onThresholdReached()
                    }
                    continue   // 首次延迟未到，继续累加，不进入正常间隔判断
                }
                // 正常循环间隔
                if (accumulatedMs >= triggerMs()) {
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

    /**
     * 取得"距离下次弹窗还剩多少毫秒"（供主界面实时显示）。
     * - 首次延迟阶段：用 firstDelay 计算；
     * - 正常循环：用 triggerMs 计算。
     */
    fun getRemainingMs(): Long {
        val target = if (!firstPopupDone) firstDelayMs() else triggerMs()
        val left = target - accumulatedMs
        return if (left < 0) 0L else left
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

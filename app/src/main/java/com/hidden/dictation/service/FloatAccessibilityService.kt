package com.hidden.dictation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.view.accessibility.AccessibilityEvent
import com.hidden.dictation.receiver.WatchdogReceiver

/**
 * FloatAccessibilityService —— 无障碍服务（需求一.3 / 三.1 方案2）
 *
 * 双作用：
 *  1) 作为隐身 APP 的"启动入口"：用户开启无障碍后，系统回调 onServiceConnected，
 *     在这里拉起主服务 MainService 与守护进程（需求一.3：无桌面图标，靠无障碍自启）。
 *  2) 方案2 兜底绘制：当 SYSTEM_ALERT_WINDOW 悬浮窗失效（被系统/定制 UI 限制）时，
 *     由本服务用"顶层绘制视图"弹出听写窗（需求三.1 方案2）。
 *
 * 注意：onAccessibilityEvent 不处理具体事件，仅保持服务存活。
 */
class FloatAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 配置：全局事件、可检索窗口内容（用于兜底绘制坐标）
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = (AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS)
            notificationTimeout = 100
        }
        // 无障碍已启用 → 拉起主服务与守护（启动整套后台）
        val ctx = this
        try {
            val i = Intent(ctx, MainService::class.java)
            startForegroundService(i)
        } catch (_: Exception) {}
        try {
            val g = Intent(ctx, GuardService::class.java)
            startForegroundService(g)
        } catch (_: Exception) {}

        // 初始化浮动窗（方案2 兜底绘制）：把 WebView 挂到无障碍窗口
        FloatWindowManager.init(ctx, this)
        FloatWindowManager.attachToA11yWindow(this)

        // 注册方案2 切换广播（当方案1 悬浮窗失效时，由 FloatWindowManager 发来）
        val filter = IntentFilter("com.hidden.dictation.USE_A11Y_FLOAT")
        registerReceiver(a11yFloatReceiver, filter)

        // 通知 WatchdogReceiver：无障碍已就绪，可取消"缺少无障碍"提示
        sendBroadcast(Intent(WatchdogReceiver.ACTION_ACCESSIBILITY_READY))
    }

    /** 方案2 切换接收者：收到后把 WebView 挂到本无障碍窗口 */
    private val a11yFloatReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.hidden.dictation.USE_A11Y_FLOAT") {
                FloatWindowManager.attachToA11yWindow(this@FloatAccessibilityService)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需处理具体事件；保持存活即可。
        // 若需要"实时监听某个弹窗被遮挡"，可在此扩展。
    }

    override fun onInterrupt() {
        // 被系统中断时尝试自我恢复（通过 Watchdog 再拉）
        sendBroadcast(Intent("com.hidden.dictation.WATCHDOG_TICK"))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 无障碍被用户关闭：发出广播，上层开始循环弹"请重新开启无障碍"提示
        sendBroadcast(Intent(WatchdogReceiver.ACTION_ACCESSIBILITY_LOST))
        return false
    }
}

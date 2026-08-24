package com.hidden.dictation.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.hidden.dictation.receiver.WatchdogReceiver

/**
 * FloatAccessibilityService —— 无障碍服务（仅作"保活辅助"，需求二.2 补充）
 *
 * 重要变更（2026-08-24）：
 *  - 去掉了 canRetrieveWindowContent 与 FLAG_REQUEST_FILTER_KEY_EVENTS。
 *    这两个 flag 在小米/MIUI 等定制 ROM 上会触发"过度获取隐私"判定，
 *    导致无障碍服务即使开关显示已开启，系统也实际不让它生效（根因：开了没用、重启再开也没用）。
 *  - 不再作为 APP 的启动入口，也不再负责绘制悬浮窗（方案2 兜底已废弃）。
 *  - 现在的唯一作用：APP 被系统杀死后，借助无障碍的"服务存活"特性由 Watchdog 辅助拉回主服务。
 *    即便该服务在个别机型上仍不生效，主服务 + 双进程守护 + SYSTEM_ALERT_WINDOW 悬浮窗也能独立运行。
 */
class FloatAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 极简配置：仅监听"全局事件"用于保活，不取窗口内容、不拦截按键（避开 MIUI 隐私拦截）
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }
        // 通知 WatchdogReceiver：无障碍已就绪（仅用于记录状态，不依赖它启动任何东西）
        sendBroadcast(Intent(WatchdogReceiver.ACTION_ACCESSIBILITY_READY))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 无需处理具体事件；保持服务存活即可（被系统回收时 onInterrupt 会通知 Watchdog 重拉主服务）
    }

    override fun onInterrupt() {
        // 被系统中断：发出广播，由 Watchdog 尝试重拉主服务/守护进程
        sendBroadcast(Intent("com.hidden.dictation.WATCHDOG_TICK"))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // 无障碍被用户关闭：发出广播，上层可记录"缺少无障碍"状态（但不强制弹窗，因已有双进程守护兜底）
        sendBroadcast(Intent(WatchdogReceiver.ACTION_ACCESSIBILITY_LOST))
        return false
    }
}

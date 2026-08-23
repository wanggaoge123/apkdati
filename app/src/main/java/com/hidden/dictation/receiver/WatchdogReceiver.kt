package com.hidden.dictation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import com.hidden.dictation.service.GuardService
import com.hidden.dictation.service.MainService
import com.hidden.dictation.ui.PermissionGuideActivity

/**
 * WatchdogReceiver —— 定时自检 + 状态广播（需求二.5 / 二.4）
 *
 * 功能：
 *  - 每 60 秒（由 GuardService 周期发送 WATCHDOG_TICK）检测主服务/守护/无障碍是否存活，
 *    被冻结或杀死则重新拉起全套服务；
 *  - 监听屏幕亮灭（统计由 ScreenTimeTracker 处理，这里仅用于极限省电提示触发）；
 *  - 监听 PULL_UP 自拉广播；
 *  - 无障碍丢失时，循环触发权限引导 Activity（需求二.4）。
 *
 * 运行于 :guard 进程（Manifest 指定），与主进程隔离，主进程被杀仍可自检。
 */
class WatchdogReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_WATCHDOG = "com.hidden.dictation.WATCHDOG_TICK"
        const val ACTION_PULL_UP = "com.hidden.dictation.PULL_UP"
        const val ACTION_ACCESSIBILITY_READY = "com.hidden.dictation.ACCESSIBILITY_READY"
        const val ACTION_ACCESSIBILITY_LOST = "com.hidden.dictation.ACCESSIBILITY_LOST"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            ACTION_WATCHDOG, ACTION_PULL_UP -> runSelfCheck(context)
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
                // 开机/更新后尝试拉起（Android 10+ 开机广播受限，仅作补充）
                pullServices(context)
            }
            Intent.ACTION_SCREEN_ON -> {
                // 屏幕亮起：检查是否极限省电，若是提示
                if (isExtremePowerMode(context)) {
                    // 通过权限引导 Activity 弹出友好提示（需求二.6）
                    val i = Intent(context, PermissionGuideActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(i)
                }
                // 每次亮屏也尝试拉起全套
                pullServices(context)
            }
            ACTION_ACCESSIBILITY_LOST -> {
                // 无障碍被关：循环触发引导，直到用户重新开启
                val i = Intent(context, PermissionGuideActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(i)
            }
        }
    }

    /** 自检：主服务/守护/无障碍任一缺失则拉起 */
    private fun runSelfCheck(context: Context) {
        if (!isServiceRunning(context, MainService::class.java)) {
            pullMain(context)
        }
        if (!isServiceRunning(context, GuardService::class.java)) {
            pullGuard(context)
        }
        if (!isAccessibilityEnabled(context)) {
            // 无障碍未开启：触发引导（持续提醒，不静默）
            val i = Intent(context, PermissionGuideActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(i)
        }
    }

    private fun pullServices(context: Context) {
        pullMain(context)
        pullGuard(context)
    }

    private fun pullMain(context: Context) {
        try {
            val i = Intent(context, MainService::class.java)
            context.startForegroundService(i)
        } catch (_: Exception) {}
    }

    private fun pullGuard(context: Context) {
        try {
            val i = Intent(context, GuardService::class.java)
            context.startForegroundService(i)
        } catch (_: Exception) {}
    }

    private fun isServiceRunning(context: Context, cls: Class<*>): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (s in am.getRunningServices(Int.MAX_VALUE)) {
            if (s.service.className == cls.name) return true
        }
        return false
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(-1)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }

    /** 极限/超级省电检测（需求二.6 友好提示） */
    private fun isExtremePowerMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        // Android 无统一 API，用 isPowerSaveMode 近似；定制系统的"极限省电"通常也会置此标志
        return pm.isPowerSaveMode
    }
}

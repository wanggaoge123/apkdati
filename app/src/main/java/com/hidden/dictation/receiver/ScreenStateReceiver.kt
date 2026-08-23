package com.hidden.dictation.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hidden.dictation.service.MainService

/**
 * ScreenStateReceiver —— 屏幕状态辅助接收者（主进程）
 * 监听亮屏/息屏/解锁，用于：
 *  - 亮屏时确保主服务与计时器在跑；
 *  - 息屏时由 ScreenTimeTracker 暂停累计（不重置，需求二.3）。
 */
class ScreenStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                // 亮屏/解锁：确保服务存活
                try {
                    context.startForegroundService(Intent(context, MainService::class.java))
                } catch (_: Exception) {}
            }
            // 息屏不必主动处理，计时器内部自行暂停
        }
    }
}

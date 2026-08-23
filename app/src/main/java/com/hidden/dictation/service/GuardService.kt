package com.hidden.dictation.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hidden.dictation.R
import kotlinx.coroutines.*

/**
 * GuardService —— 守护进程(:guard)（需求二.2 双向守护）
 *
 * 作用：
 *  1) 反向拉起 :main 进程（若 MainService 被系统杀死，这里把它拉回）；
 *  2) 自身被杀死时，MainService / 无障碍也会把它拉回，形成双向守护；
 *  3) 承载 60 秒定时自检的"发起"（需求二.5），通过 WatchdogReceiver 周期告警；
 *  4) 同样以媒体前台服务常驻，最低优先级静音通知。
 */
class GuardService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var selfCheckJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundSafely()
        // 拉起主进程
        pullMain()
        // 启动 60 秒定时自检（需求二.5）
        startSelfCheck()
    }

    /** 拉起主进程服务 */
    private fun pullMain() {
        try {
            val i = Intent(this, MainService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
        } catch (_: Exception) {}
    }

    /** 60 秒定时自检（需求二.5）：检查主服务/无障碍状态，必要时重拉 */
    private fun startSelfCheck() {
        selfCheckJob = scope.launch {
            while (isActive) {
                delay(60_000L)
                // 周期性确认主进程活着
                if (!isServiceRunning(MainService::class.java)) {
                    pullMain()
                }
                // 触发一次自检广播（WatchdogReceiver 会再校验无障碍/权限）
                sendBroadcast(Intent("com.hidden.dictation.WATCHDOG_TICK"))
            }
        }
    }

    private fun isServiceRunning(cls: Class<*>): Boolean {
        // 轻量检测：用 ActivityManager 判断服务是否运行
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (s in am.getRunningServices(Int.MAX_VALUE)) {
            if (s.service.className == cls.name) return true
        }
        return false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "hidden_dictation_guard"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "守护", NotificationManager.IMPORTANCE_MIN).apply {
                setSound(null, null); setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val nf = NotificationCompat.Builder(this, channelId)
            .setContentTitle("守护进程")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(2, nf, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(2, nf)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        selfCheckJob?.cancel()
        // 守护进程被杀，尝试最后一次拉起主进程（双向守护兜底）
        pullMain()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

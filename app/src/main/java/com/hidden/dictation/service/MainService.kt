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
import com.hidden.dictation.db.DictationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * MainService —— 主进程(:main)前台服务（需求二.1 / 二.2）
 *
 * 职责：
 *  1) 以"媒体播放"前台服务类型常驻，通知最低优先级、静音、无角标（需求二.1）；
 *  2) 启动全局亮屏计时器 ScreenTimeTracker，达标后唤起听写弹窗；
 *  3) 启动双进程守护：拉起 :guard 进程（需求二.2 双向守护）；
 *  4) 自身被杀时，:guard 会把它拉回（在 GuardService 中互拉）。
 *
 * 媒体前台服务在 Android 10~14 均可用；Android 14 必须声明 FOREGROUND_SERVICE_MEDIA_PLAYBACK
 * 权限与 foregroundServiceType，已在 Manifest 配置。
 */
class MainService : Service() {

    private lateinit var tracker: ScreenTimeTracker
    private lateinit var repo: DictationRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        repo = DictationRepository.get(this)

        // 1) 启动前台通知（最低优先级、静音）
        startForegroundSafely()

        // 2) 初始化计时器，达标后唤起弹窗（通过悬浮窗管理器）
        tracker = ScreenTimeTracker(this) {
            onTriggerReached()
        }
        // 把计时器交给 FloatWindowManager 持有，便于答对后重置（需求三.2）
        FloatWindowManager.ScreenTimeTrackerHolder.tracker = tracker
        tracker.start()

        // 3) 拉起守护进程（双向守护）
        startGuardProcess()

        // 4) 数据种子
        scope.launch { repo.ensureSeed() }
    }

    /** 计时达标：通过广播/桥唤起听写弹窗（具体弹窗由 FloatWindowManager 处理） */
    private fun onTriggerReached() {
        // 通知悬浮窗管理器弹出（若悬浮窗不可用，由无障碍兜底）
        FloatWindowManager.requestOpen(this)
        scope.launch { repo.reportTrigger() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被杀后系统若重启服务，仍保持前台
        startForegroundSafely()
        return START_STICKY
    }

    private fun startForegroundSafely() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "hidden_dictation_chan"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                channelId, "后台计时", NotificationManager.IMPORTANCE_MIN
            ).apply {
                // 最低优先级、静音、无角标（需求二.1）
                setSound(null, null)
                vibrationPattern = null
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
        val nf = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.foreground_notification_title))
            .setContentText(getString(R.string.foreground_notification_text))
            .setSmallIcon(android.R.drawable.ic_media_play) // 媒体类型图标
            .setPriority(NotificationCompat.PRIORITY_MIN)    // 最低优先级
            .setSilent(true)                                 // 静音
            .setOngoing(true)
            .build()
        // Android 14 要求传入 foregroundServiceType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, nf, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(1, nf)
        }
    }

    /** 拉起 :guard 守护进程 */
    private fun startGuardProcess() {
        try {
            val i = Intent(this, GuardService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(i)
            } else {
                startService(i)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::tracker.isInitialized) tracker.stop()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

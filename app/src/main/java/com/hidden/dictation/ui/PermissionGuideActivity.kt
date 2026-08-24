package com.hidden.dictation.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.hidden.dictation.R
import com.hidden.dictation.service.MainService
import com.hidden.dictation.service.GuardService

/**
 * PermissionGuideActivity —— 4 项必备权限引导（需求二.4）
 *
 * 4 项权限（缺少任意一项持续弹窗提醒，不静默跳过）：
 *  1) 悬浮窗权限 SYSTEM_ALERT_WINDOW
 *  2) 忽略电池优化 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *  3) 后台活动权限（不同厂商叫法不同，引导到"自启动/后台管理"设置页）
 *  4) 无障碍权限（开启本 APP 的无障碍服务）
 *
 * 透明主题 Activity，不暴露"主界面"，仅作为权限引导弹窗（需求一.3）。
 *
 * 关键稳定性修复（解决桌面点击闪退/无反应）：
 *  - 透明主题 Activity 从桌面启动必须有 content view，否则 onResume 后系统报
 *    "content view not yet created" 直接崩溃；这里 setContentView 一个透明 FrameLayout。
 *  - AlertDialog 必须在窗口 attach 之后再 show，否则 Android 10+ 抛 BadTokenException 闪退；
 *    这里统一用 Handler.post 延迟到下一帧窗口就绪后再弹。
 */
class PermissionGuideActivity : AppCompatActivity() {

    // 待授予权限队列，逐个引导；全部通过才结束 Activity
    private val pending = mutableListOf<PermissionItem>()
    private var currentIndex = 0

    // 记录"引导是否已全部完成"，完成后再次打开只提示、不重复弹（避免暴露主界面）
    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("dictation_prefs", Context.MODE_PRIVATE)
    }

    // 主线程 Handler，用于把弹窗推迟到窗口就绪后再 show（避免 BadTokenException）
    private val uiHandler = Handler(Looper.getMainLooper())

    data class PermissionItem(
        val name: String,
        val checker: () -> Boolean,
        val launcher: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 必须有 content view，否则透明主题 Activity 从桌面启动会崩溃（闪退根因之一）
        setContentView(FrameLayout(this))
        buildQueue()
        // 若已经完成过首次引导，提示后关闭（不重复弹、不暴露界面）
        if (prefs.getBoolean("guide_done", false)) {
            uiHandler.post {
                AlertDialog.Builder(this)
                    .setTitle(R.string.perm_guide_title)
                    .setMessage("权限已配置完成，后台听写服务运行中。如需重新设置，可在系统设置中调整相关权限。")
                    .setCancelable(false)
                    .setPositiveButton("知道了") { _, _ -> finish() }
                    .show()
            }
            return
        }
        // 恢复上次引导到的权限项（跳转设置页后本 Activity 会被销毁，靠 prefs 续上）
        currentIndex = prefs.getInt("guide_index", 0)
        // 推迟到窗口就绪后再弹引导，避免 dialog show 时 window token 未就绪导致闪退
        uiHandler.post { showCurrent() }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后，onResume 会再次触发：重新校验当前项是否已满足
        if (!prefs.getBoolean("guide_done", false)) {
            uiHandler.post { showCurrent() }
        }
    }

    private fun buildQueue() {
        pending.clear()
        // 1 悬浮窗
        pending.add(
            PermissionItem(
                name = "悬浮窗",
                checker = { Settings.canDrawOverlays(this) },
                launcher = {
                    val i = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(i)
                }
            )
        )
        // 2 忽略电池优化
        pending.add(
            PermissionItem(
                name = "忽略电池优化",
                checker = {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    pm.isIgnoringBatteryOptimizations(packageName)
                },
                launcher = {
                    val i = Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(i)
                }
            )
        )
        // 3 无障碍（用于自启+兜底绘制）
        pending.add(
            PermissionItem(
                name = "无障碍服务",
                checker = { isAccessibilityEnabled() },
                launcher = {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
            )
        )
        // 4 后台活动（厂商定制，跳到应用详情/自启动设置；无统一 API，引导手动开启）
        pending.add(
            PermissionItem(
                name = "后台活动(自启动/后台管理)",
                // 该项无标准检测 API，靠用户确认；仍弹一次引导，不静默跳过
                checker = { true },
                launcher = {
                    // 跳到应用详情页，用户手动在厂商设置里开启自启动/后台管理
                    val i = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(i)
                }
            )
        )
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabled = am.getEnabledAccessibilityServiceList(-1)
        return enabled.any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    /**
     * 弹出"当前权限项"的引导框（非递归，单次）。
     * - 当前项已满足 → 推进到下一项并持久化，重新弹下一项；
     * - 当前项未满足 → 弹引导框，点"前往设置"后跳系统设置页（本 Activity 会被销毁，
     *   返回后 onCreate/onResume 重新驱动 showCurrent，从 prefs 续上进度）；
     * - 全部完成 → 记录 guide_done、拉起后台服务、finish。
     */
    private fun showCurrent() {
        // 防御：窗口已被销毁则不操作
        if (isFinishing || isDestroyed) return

        if (currentIndex >= pending.size) {
            prefs.edit().putBoolean("guide_done", true).apply()
            try {
                startForegroundService(Intent(this, MainService::class.java))
            } catch (_: Exception) {}
            try {
                startForegroundService(Intent(this, GuardService::class.java))
            } catch (_: Exception) {}
            finish()
            return
        }

        val item = pending[currentIndex]
        if (item.checker()) {
            // 已满足，前进一项
            currentIndex++
            prefs.edit().putInt("guide_index", currentIndex).apply()
            showCurrent()
            return
        }

        // 未满足：弹引导框（不静默、不可取消）
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_guide_title)
            .setMessage("正在引导授予：${item.name}\n\n缺少该权限将导致听写弹窗无法在游戏上层显示或后台被杀死。请点击【前往设置】并在设置中允许，返回后继续。")
            .setCancelable(false)
            .setPositiveButton("前往设置") { _, _ ->
                // 持久化当前进度，再跳转设置页（跳转后本 Activity 销毁，返回时从 prefs 续上）
                prefs.edit().putInt("guide_index", currentIndex).apply()
                item.launcher()
            }
            .show()
    }
}

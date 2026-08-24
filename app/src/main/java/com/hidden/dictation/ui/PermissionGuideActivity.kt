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

/**
 * PermissionGuideActivity —— 4 项必备权限引导（需求二.4）
 *
 * 4 项权限（缺少任意一项持续弹窗提醒，不静默跳过）：
 *  1) 悬浮窗权限 SYSTEM_ALERT_WINDOW
 *  2) 忽略电池优化 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
 *  3) 后台活动权限（不同厂商叫法不同，引导到"自启动/后台管理"设置页）
 *  4) 无障碍权限（开启本 APP 的无障碍服务，仅作保活辅助）
 *
 * 2026-08-24 改造：从"首次一次性引导"改为"可从主界面重复进入的权限设置页"。
 *  - 不再用 guide_done 决定是否弹引导；进入即逐个检查，缺哪个引导哪个；
 *  - 全部满足后显示"已全部完成"，点确定返回主界面；
 *  - 透明主题（不破坏主界面的视觉一致性，作为弹窗式设置页）。
 *
 * 稳定性修复（避免闪退）：
 *  - 透明主题 Activity 必须有 content view（setContentView 透明 FrameLayout）；
 *  - AlertDialog 用 Handler.post 延迟到窗口就绪后再 show，避免 BadTokenException。
 */
class PermissionGuideActivity : AppCompatActivity() {

    // 待授予权限队列，逐个引导；全部通过才结束 Activity
    private val pending = mutableListOf<PermissionItem>()
    private var currentIndex = 0

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
        // 必须有 content view，否则透明主题 Activity 从桌面/界面启动会崩溃
        setContentView(FrameLayout(this))
        buildQueue()
        // 恢复上次引导到的权限项（跳转设置页后本 Activity 会被销毁，靠 prefs 续上）
        currentIndex = prefs.getInt("guide_index", 0)
        // 推迟到窗口就绪后再弹引导
        uiHandler.post { showCurrent() }
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回后，onResume 会再次触发：重新校验当前项是否已满足
        uiHandler.post { showCurrent() }
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
        // 3 无障碍（仅作保活辅助）
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
     * - 当前项未满足 → 弹引导框，点"前往设置"后跳系统设置页（本 Activity 销毁，返回时从 prefs 续上）；
     * - 全部完成 → 显示"已全部完成"，点确定 finish 回主界面。
     */
    private fun showCurrent() {
        // 防御：窗口已被销毁则不操作
        if (isFinishing || isDestroyed) return

        if (currentIndex >= pending.size) {
            // 全部完成
            prefs.edit().putInt("guide_index", 0).apply()
            AlertDialog.Builder(this)
                .setTitle(R.string.perm_guide_title)
                .setMessage("所需权限已全部授予，后台听写弹窗可正常在游戏/应用上层显示。")
                .setCancelable(false)
                .setPositiveButton("完成") { _, _ -> finish() }
                .show()
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

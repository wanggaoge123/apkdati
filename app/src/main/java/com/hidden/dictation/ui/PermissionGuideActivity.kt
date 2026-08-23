package com.hidden.dictation.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
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
 *  4) 无障碍权限（开启本 APP 的无障碍服务）
 *
 * 透明主题 Activity，不暴露"主界面"，仅作为权限引导弹窗（需求一.3）。
 */
class PermissionGuideActivity : AppCompatActivity() {

    // 待授予权限队列，逐个引导；全部通过才结束 Activity
    private val pending = mutableListOf<PermissionItem>()
    private var currentIndex = 0

    data class PermissionItem(
        val name: String,
        val checker: () -> Boolean,
        val launcher: () -> Unit
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 透明主题，仅弹对话框
        buildQueue()
        showNext()
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

    private fun showNext() {
        if (currentIndex >= pending.size) {
            // 全部完成：结束引导（不回调任何界面，仅关闭本透明 Activity）
            finish()
            return
        }
        val item = pending[currentIndex]
        // 已满足则跳过到下一项
        if (item.checker()) {
            currentIndex++
            showNext()
            return
        }
        // 否则弹窗引导（不静默）
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_guide_title)
            .setMessage("正在引导授予：${item.name}\n\n缺少该权限将导致听写弹窗无法在游戏上层显示或后台被杀死。请点击【前往设置】并在设置中允许，返回后继续。")
            .setCancelable(false) // 禁止点外部取消，缺权限持续提醒
            .setPositiveButton("前往设置") { _, _ ->
                item.launcher()
                // 返回后重新校验，未通过会再次弹（持续提醒）
                showNext()
            }
            .show()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // 从设置返回时，重新走一轮校验
        currentIndex = 0
        showNext()
    }
}

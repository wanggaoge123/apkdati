package com.hidden.dictation.bridge

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * FloatHint —— 轻量级悬浮提示（用于"识别失败"等不阻塞提示，需求七边界）
 * 使用 SYSTEM_ALERT_WINDOW 悬浮窗，短暂显示后自动消失，不影响听写弹窗锁死逻辑。
 */
object FloatHint {
    fun show(context: Context, text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)) {
            return // 无悬浮窗权限则放弃提示，不报错
        }
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val tv = TextView(context).apply {
            this.text = text
            setTextColor(0xFF333333.toInt())
            setBackgroundColor(0xCCFFFFFF.toInt())
            setPadding(40, 24, 40, 24)
            textSize = 14f
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; y = 200 }

        try {
            wm.addView(tv, params)
            Handler(Looper.getMainLooper()).postDelayed({
                try { wm.removeView(tv) } catch (_: Exception) {}
            }, 3000)
        } catch (_: Exception) {}
    }
}

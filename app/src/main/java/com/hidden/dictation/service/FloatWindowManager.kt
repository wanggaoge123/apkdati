package com.hidden.dictation.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hidden.dictation.R
import com.hidden.dictation.bridge.JsBridge
import com.hidden.dictation.db.DictationRepository

/**
 * FloatWindowManager —— 双方案悬浮听写弹窗（需求三.1）
 *
 * 方案1（基础）：SYSTEM_ALERT_WINDOW 系统悬浮窗，内嵌 WebView 加载 assets/index.html（听写页）；
 * 方案2（兜底）：当悬浮窗权限失效或被定制 UI 限制时，由 FloatAccessibilityService
 *              用"A11y 顶层绘制视图"渲染同一 WebView（无障碍服务可绘制于所有应用之上）。
 *
 * 弹窗锁死逻辑（需求三.2，严格执行）：
 *  - 手写识别错误 → 弹窗无法关闭、无法拖动隐藏、无法切页面绕过（allowClose=false，且不响应关闭）；
 *  - 手写识别匹配正确 → 安卓主动关闭弹窗、重置计时器（allowClose=true 后 closeDictation()）。
 *
 * 全界面无死角：无论全屏游戏、其他 APP 上层都能弹出（TYPE_APPLICATION_OVERLAY 置顶）。
 */
object FloatWindowManager {

    private var webView: WebView? = null
    private var jsBridge: JsBridge? = null
    private var repo: DictationRepository? = null
    private var wm: WindowManager? = null
    private var floatParams: WindowManager.LayoutParams? = null

    // 是否正用方案2（无障碍兜底）绘制
    private var usingA11yFallback = false

    /**
     * 初始化：创建 WebView 并加载 assets 听写页，注入 JS 桥。
     * @param a11yService 若为非 null，则使用方案2（无障碍绘制），否则方案1（悬浮窗）。
     */
    fun init(context: Context, a11yService: AccessibilityService? = null) {
        repo = DictationRepository.get(context)
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        webView = WebView(context.applicationContext).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                // 允许混合内容（CDN 可能 http/https）
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // 不缓存到网页缓存（数据走 Room，需求三.3）
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = WebViewClient()
        }

        // 创建 JS 桥：安卓→JS 与 JS→安卓双向
        jsBridge = JsBridge(
            context = context.applicationContext,
            webView = webView!!,
            repo = repo!!,
            onNeedResetTimer = { ScreenTimeTrackerHolder.reset() },
            onWrongLocked = { /* 锁死：不关闭，allowClose 已置 false */ }
        )
        webView?.addJavascriptInterface(jsBridge, "AndroidBridge")

        // 加载 assets 听写页（复用指南的 writing-core.js + 统一弹窗 HTML/CSS）
        webView?.loadUrl("file:///android_asset/index.html")

        // 悬浮窗参数（置顶、全屏覆盖、不可拖动）
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        floatParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            // 不可触摸外部关闭、不可聚焦绕过；弹窗本身靠内部按钮交互
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        usingA11yFallback = (a11yService != null)
    }

    /** 计时达标 → 请求打开听写弹窗（需求三.1 唤起） */
    fun requestOpen(context: Context) {
        if (webView == null) init(context)
        // 先注入最新题库
        jsBridge?.injectWordList()
        // 显示悬浮窗（方案1）或由无障碍绘制（方案2）
        if (usingA11yFallback) {
            // 方案2：由 FloatAccessibilityService 在其 window 上 addView（见下方 attachToA11y）
        } else {
            try {
                if (webView?.windowToken == null && floatParams != null) {
                    wm?.addView(webView, floatParams)
                }
            } catch (_: Exception) {
                // 悬浮窗失败 → 切换到方案2（无障碍兜底）
                switchToA11yFallback(context)
            }
        }
        // 通知前端唤起弹窗
        jsBridge?.markWrongLock() // 默认锁死，答对才解锁
        jsBridge?.openDictation()
    }

    /** 方案1 失败 → 切换方案2（无障碍兜底绘制） */
    private fun switchToA11yFallback(context: Context) {
        usingA11yFallback = true
        // 实际绘制需在 AccessibilityService 的 Service 上下文里 addView。
        // 这里通过广播让 FloatAccessibilityService 接管（在它的 onServiceConnected/绘制逻辑里 attach）。
        context.sendBroadcast(android.content.Intent("com.hidden.dictation.USE_A11Y_FLOAT"))
    }

    /** 由 AccessibilityService 调用：把 WebView 挂到无障碍窗口（方案2） */
    fun attachToA11yWindow(a11yService: AccessibilityService) {
        usingA11yFallback = true
        try {
            if (webView?.windowToken == null && floatParams != null) {
                a11yService.windowManager.addView(webView, floatParams)
            }
        } catch (_: Exception) {}
    }

    /**
     * 答对后由安卓主动关闭弹窗（需求三.2）：
     *  解锁 allowClose → 关闭前端弹窗 → 移除悬浮窗视图 → 重置计时。
     */
    fun onAnsweredCorrect(context: Context, hanzi: String) {
        jsBridge?.markCorrectAndAllowClose()
        jsBridge?.closeDictation()
        removeFloatView()
        ScreenTimeTrackerHolder.reset()
        // 记录结果（已在 JsBridge.onResult 里写 Room，这里确保计时重置）
    }

    /** 答错：保持锁死，不移除视图（需求三.2） */
    fun onAnsweredWrong() {
        jsBridge?.markWrongLock()
        // 不调用 removeFloatView，弹窗持续存在，无法绕过
    }

    private fun removeFloatView() {
        try {
            if (webView?.windowToken != null) wm?.removeView(webView)
        } catch (_: Exception) {}
    }

    fun destroy() {
        try { webView?.destroy() } catch (_: Exception) {}
        webView = null
        jsBridge = null
    }

    /** 静态持有计时器入口（由 MainService 注入） */
    object ScreenTimeTrackerHolder {
        lateinit var tracker: com.hidden.dictation.service.ScreenTimeTracker
        fun reset() { if (::tracker.isInitialized) tracker.reset() }
    }
}

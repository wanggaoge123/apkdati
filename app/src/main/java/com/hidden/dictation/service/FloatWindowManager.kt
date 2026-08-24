package com.hidden.dictation.service

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
 * FloatWindowManager —— 悬浮听写弹窗（方案1：SYSTEM_ALERT_WINDOW，需求三.1）
 *
 * 重要变更（2026-08-24）：
 *  - 废弃了"方案2 无障碍兜底绘制"。原因：无障碍窗口绘制 WebView 在小米等定制 ROM 上不稳定，
 *    且原无障碍服务因 flag 问题在 MIUI 实际不生效，反而拖累整体。
 *  - 现在只走 SYSTEM_ALERT_WINDOW 系统悬浮窗，逻辑更简单、更可控。
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

    /**
     * 初始化：创建 WebView 并加载 assets 听写页，注入 JS 桥。
     * 注意：必须在已获得 SYSTEM_ALERT_WINDOW 权限后调用（否则 addView 会抛异常）。
     */
    fun init(context: Context) {
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
        webView?.addJavascriptInterface(jsBridge!!, "AndroidBridge")

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
    }

    /** 是否已初始化（WebView 已创建） */
    fun isInitialized(): Boolean = webView != null

    /** 计时达标 → 请求打开听写弹窗（需求三.1 唤起） */
    fun requestOpen(context: Context) {
        if (webView == null) init(context)
        // 先注入最新题库
        jsBridge?.injectWordList()
        // 显示悬浮窗（仅方案1：SYSTEM_ALERT_WINDOW）
        try {
            if (webView?.windowToken == null && floatParams != null) {
                wm?.addView(webView, floatParams)
            }
        } catch (_: Exception) {
            // 悬浮窗失败（例如权限被临时收回）：本次不弹，等下次计时达标再试
        }
        // 通知前端唤起弹窗
        jsBridge?.markWrongLock() // 默认锁死，答对才解锁
        jsBridge?.openDictation()
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
        /** 计时器是否已初始化（供主界面判断服务是否真正运行） */
        fun isInitialized(): Boolean = ::tracker.isInitialized
    }
}

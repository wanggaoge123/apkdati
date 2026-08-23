package com.hidden.dictation.bridge

import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.hidden.dictation.db.DictationRepository
import com.hidden.dictation.service.FloatWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * JsBridge —— WebView 与安卓原生的双向 JS 通信桥（需求三.3）
 *
 * 安卓 → JS（下发指令，通过 WebView.evaluateJavascript）：
 *   - open()            通知前端唤起听写弹窗
 *   - close()           通知前端关闭弹窗
 *   - resetTimer()      重置全局亮屏计时器（答对后调用）
 *   - setWordList(list) 注入题库（window.WORD_LIST，由 Room 提供，前端不自定语库）
 *
 * JS → 安卓（前端通过 window.AndroidBridge.xxx 调用，本类用 @JavascriptInterface 暴露）：
 *   - onResult(json)    答题结果回传：{hanzi, isCorrect, mode}
 *   - onCloseRequest()  前端请求关闭（安卓依据"锁死规则"决定是否允许）
 *   - onNetworkFail()   在线识别失败（断网/HanziWriter 未加载）时通知安卓弹提示
 *
 * 数据不依赖 WebView 缓存，全部经本桥写入 Room（需求三.3）。
 */
class JsBridge(
    private val context: Context,
    private val webView: WebView,
    private val repo: DictationRepository,
    private val onNeedResetTimer: () -> Unit,
    private val onWrongLocked: () -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 注入题库到前端 window.WORD_LIST（需求四.3：词库由 Room 提供） */
    fun injectWordList() {
        scope.launch {
            val list = repo.getWordListForFrontend()
            val arr = JSONArray()
            list.forEach { m ->
                val o = JSONObject()
                o.put("hanzi", m["hanzi"])
                o.put("pinyin", m["pinyin"])
                arr.put(o)
            }
            val js = "window.WORD_LIST = $arr; window.IS_RANDOM_MODE = true;"
            webView.post { webView.evaluateJavascript(js, null) }
        }
    }

    // ---------------- 安卓 → JS 指令 ----------------

    fun openDictation() {
        webView.post { webView.evaluateJavascript("if(window.WritingCore) WritingCore.open();", null) }
    }

    fun closeDictation() {
        webView.post { webView.evaluateJavascript("if(window.WritingCore) WritingCore.close();", null) }
    }

    fun resetTimer() {
        onNeedResetTimer()
    }

    // ---------------- JS → 安卓（@JavascriptInterface） ----------------

    @JavascriptInterface
    fun onResult(json: String) {
        // 前端回传答题结果：{hanzi, isCorrect, mode}
        try {
            val o = JSONObject(json)
            val hanzi = o.optString("hanzi")
            val isCorrect = o.optBoolean("isCorrect", false)
            val mode = o.optString("mode", "char")
            scope.launch {
                repo.reportResult(hanzi, isCorrect)
            }
            // 答对 → 安卓重置计时 + 主动关闭弹窗（需求三.2）
            if (isCorrect) {
                onNeedResetTimer()
                FloatWindowManager.onAnsweredCorrect(context, hanzi)
            } else {
                // 答错 → 触发锁死（弹窗无法关闭、无法绕过，需求三.2）
                onWrongLocked()
                FloatWindowManager.onAnsweredWrong()
            }
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun onCloseRequest(): Boolean {
        // 前端请求关闭。安卓依据"锁死规则"决定：
        // 仅当最后一次结果为"答对"才允许关闭；否则拒绝（返回 false）。
        // 实际关闭时机由安卓在收到 onResult(isCorrect=true) 后主动 closeDictation()。
        // 这里返回当前是否允许关闭（由外部状态决定）。
        return allowClose
    }

    @JavascriptInterface
    fun onNetworkFail() {
        // 在线识别失败（断网/HanziWriter 未加载）：安卓弹提示但不崩溃（需求三.3 / 七边界）
        showNetworkFailHint()
    }

    // 关闭许可状态（由 onResult 更新）
    var allowClose = false
        private set

    fun markCorrectAndAllowClose() {
        allowClose = true
    }

    fun markWrongLock() {
        allowClose = false
    }

    private fun showNetworkFailHint() {
        // 通过悬浮窗管理器弹一个轻提示（不阻塞答题，前端已有离线兜底）
        FloatHint.show(context, "在线识别暂不可用，已切换本地宽松判定，可继续书写。")
    }
}

package com.nekosu.manager.ui.webui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** WebUI 页面的单一事件通道：加载中 / 就绪 / 出错，以及 WebView 弹回主线程的交互。 */
sealed class WebUIEvent {
    data object Loading : WebUIEvent()

    data object WebViewReady : WebUIEvent()

    data class Error(
        val message: String,
    ) : WebUIEvent()

    data object Close : WebUIEvent()

    data class ShowAlert(
        val message: String,
        val result: JsResult,
    ) : WebUIEvent()

    data class ShowConfirm(
        val message: String,
        val result: JsResult,
    ) : WebUIEvent()

    data class ShowPrompt(
        val message: String,
        val defaultValue: String,
        val result: JsPromptResult,
    ) : WebUIEvent()

    data class ShowFileChooser(
        val intent: Intent,
    ) : WebUIEvent()
}

/**
 * WebUIActivity 的状态持有者。
 *
 * WebView 在 prepareWebView 里建好，页面只订阅 [uiEvent]；JsResult 必须回一次
 * confirm/cancel，否则 JS 侧的 alert/confirm/prompt 永远挂起，这里把回填都收拢到
 * 对应的 onXxxResult，Mochi 弹窗只管触发。
 */
class WebUIState {
    // Compose 可观察：WebUIScreen 依赖它在 WebView 建好后重组挂载 AndroidView
    var webView: WebView? by mutableStateOf(null)
    var webViewInterface: WebViewInterface? = null
    lateinit var modDir: String
    var moduleName: String = ""

    var uiEvent by mutableStateOf<WebUIEvent>(WebUIEvent.Loading)
    var isUrlLoaded = false

    /** fullScreen(true) 之后由 Compose 侧把 insets 换算好写进来。 */
    @Volatile
    var currentInsets: WebInsets = WebInsets(0, 0, 0, 0)

    var isInsetsEnabled by mutableStateOf(false)
    var webCanGoBack by mutableStateOf(false)
    var filePathCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    fun onAlertResult() {
        val event = uiEvent
        if (event is WebUIEvent.ShowAlert) {
            event.result.confirm()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    fun onConfirmResult(confirmed: Boolean) {
        val event = uiEvent
        if (event is WebUIEvent.ShowConfirm) {
            if (confirmed) event.result.confirm() else event.result.cancel()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    fun onPromptResult(result: String?) {
        val event = uiEvent
        if (event is WebUIEvent.ShowPrompt) {
            if (result != null) event.result.confirm(result) else event.result.cancel()
            uiEvent = WebUIEvent.WebViewReady
        }
    }

    fun onFileChooserResult(uris: Array<Uri>?) {
        filePathCallback?.onReceiveValue(uris)
        filePathCallback = null
        uiEvent = WebUIEvent.WebViewReady
    }

    fun requestExit() {
        uiEvent = WebUIEvent.Close
    }

    /** Activity 销毁时收尾：WebView 必须从视图树摘下来再 destroy，否则泄漏。 */
    fun dispose(activity: Activity) {
        webViewInterface?.destroy()
        webViewInterface = null
        webView?.let { view ->
            (view.parent as? android.view.ViewGroup)?.removeView(view)
            view.destroy()
        }
        webView = null
        filePathCallback?.onReceiveValue(null)
        filePathCallback = null
    }
}

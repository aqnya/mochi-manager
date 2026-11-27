package com.nekosu.manager.ui.webui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.webkit.WebViewAssetLoader
import com.nekosu.manager.R
import com.nekosu.manager.data.ModuleStore
import com.nekosu.manager.data.RootShell
import com.nekosu.manager.ui.theme.NekoSUTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.feice.mochi.theme.MochiTheme

/** WebUI 域名：WebViewAssetLoader 把模块 webroot 挂在这个 https 域上。 */
internal const val WEB_DOMAIN = "mui.nekosu.local"

/**
 * 打开模块 WebUI 的标准入口；模块卡片上的「打开」按钮用它。
 *
 * 模块 id 走 extra 传，不拼 URI——id 来自模块目录名，可能带特殊字符。
 */
fun webUiIntent(
    context: Context,
    moduleId: String,
): Intent = Intent(context, WebUIActivity::class.java).putExtra(EXTRA_MODULE_ID, moduleId)

private const val EXTRA_MODULE_ID = "id"

private const val TAG = "NekoSU/WebUI"

/**
 * 模块 WebUI 的宿主 Activity。
 *
 * 独立于主界面（和 KernelSU 一样）：WebView 有自己的生命周期与返回栈，模块页退出
 * 不该把管理器整个带崩；Manifest 里声明了 configChanges，旋转不重建、页面不重载。
 */
@SuppressLint("SetJavaScriptEnabled")
class WebUIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        super.onCreate(savedInstanceState)

        setContent {
            NekoSUTheme {
                WebUIContent(activity = this, onFinish = { finish() })
            }
        }
    }
}

@Composable
private fun WebUIContent(
    activity: WebUIActivity,
    onFinish: () -> Unit,
) {
    val moduleId = activity.intent.getStringExtra(EXTRA_MODULE_ID)
    val webUIState = remember { WebUIState() }

    // Mochi 配色 → WebUI 的 CSS 变量（/internal/colors.css）
    UpdateWebUiCss()

    // WebView 背景用主题底色而不是透明：透明背景在部分设备（HyperOS + WebView 157
    // 实测）上合成器不出首帧，页面加载正常但整屏只剩 Compose 背景透上来
    val webViewBackground = MochiTheme.colorScheme.background.toArgb()

    LaunchedEffect(moduleId) {
        if (moduleId == null) {
            onFinish()
            return@LaunchedEffect
        }
        prepareWebView(activity, moduleId, webUIState, webViewBackground)
    }

    DisposableEffect(Unit) {
        onDispose { webUIState.dispose(activity) }
    }

    when (val event = webUIState.uiEvent) {
        is WebUIEvent.Error -> {
            LaunchedEffect(event) {
                Toast.makeText(activity, event.message, Toast.LENGTH_SHORT).show()
                onFinish()
            }
        }

        is WebUIEvent.Close -> {
            LaunchedEffect(event) { onFinish() }
        }

        else -> {}
    }

    Crossfade(
        targetState = webUIState.uiEvent is WebUIEvent.Loading,
        animationSpec = tween(300),
    ) { loading ->
        if (loading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(ui.feice.mochi.theme.MochiDefaults.groupedBackground()),
                contentAlignment = Alignment.Center,
            ) {
                ui.feice.mochi.basic.MochiActivityIndicator(size = 28.dp)
            }
        } else {
            WebUIScreen(webUIState)
        }
    }
}

/**
 * 校验模块、创建 WebView 并挂上 JS 桥。
 *
 * 失败（找不到模块、模块禁用、没有 webroot）都走 [WebUIEvent.Error]，页面 toast 后退出。
 */
@SuppressLint("SetJavaScriptEnabled")
internal suspend fun prepareWebView(
    activity: ComponentActivity,
    moduleId: String,
    webUIState: WebUIState,
    webViewBackground: Int,
) {
    val moduleInfo =
        withContext(Dispatchers.IO) {
            runCatching { ModuleStore.load() }.getOrNull()?.firstOrNull { it.id == moduleId }
        }
    if (moduleInfo == null) {
        webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_webui_no_module, moduleId))
        return
    }
    if (!moduleInfo.enabled || moduleInfo.remove) {
        webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_webui_unavailable, moduleInfo.name))
        return
    }
    val modDir = "/data/adb/modules/$moduleId"
    val webRoot = java.io.File("$modDir/webroot")
    if (!withContext(Dispatchers.IO) { rootDirExists(webRoot) }) {
        webUIState.uiEvent = WebUIEvent.Error(activity.getString(R.string.module_webui_no_webroot, moduleInfo.name))
        return
    }

    webUIState.modDir = modDir
    webUIState.moduleName = moduleInfo.name

    withContext(Dispatchers.Main) {
        val webView = WebView(activity)
        webView.setBackgroundColor(webViewBackground)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
        }

        val assetLoader =
            WebViewAssetLoader.Builder()
                .setDomain(WEB_DOMAIN)
                .addPathHandler("/", SuFilePathHandler(webRoot) { webUIState.currentInsets })
                .build()

        webView.webViewClient =
            object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? = assetLoader.shouldInterceptRequest(request.url)

                override fun doUpdateVisitedHistory(
                    view: WebView?,
                    url: String?,
                    isReload: Boolean,
                ) {
                    webUIState.webCanGoBack = view?.canGoBack() ?: false
                    if (webUIState.isInsetsEnabled) {
                        view?.evaluateJavascript(webUIState.currentInsets.js, null)
                    }
                    super.doUpdateVisitedHistory(view, url, isReload)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) {
                        Log.w(TAG, "主框架加载失败 code=${error.errorCode}: ${error.description} url=${request.url}")
                    }
                    super.onReceivedError(view, request, error)
                }

                override fun onRenderProcessGone(
                    view: WebView,
                    detail: RenderProcessGoneDetail,
                ): Boolean {
                    Log.e(TAG, "WebView 渲染进程崩溃 didCrash=${detail.didCrash()}")
                    return false
                }
            }

        webView.webChromeClient =
            object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                    Log.i(
                        TAG,
                        "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})",
                    )
                    return true
                }
                override fun onJsAlert(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowAlert(message, result)
                    return true
                }

                override fun onJsConfirm(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    result: JsResult?,
                ): Boolean {
                    if (message == null || result == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowConfirm(message, result)
                    return true
                }

                override fun onJsPrompt(
                    view: WebView?,
                    url: String?,
                    message: String?,
                    defaultValue: String?,
                    result: JsPromptResult?,
                ): Boolean {
                    if (message == null || result == null || defaultValue == null) return false
                    webUIState.uiEvent = WebUIEvent.ShowPrompt(message, defaultValue, result)
                    return true
                }

                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<android.net.Uri>>?,
                    fileChooserParams: FileChooserParams?,
                ): Boolean {
                    webUIState.filePathCallback?.onReceiveValue(null)
                    webUIState.filePathCallback = filePathCallback
                    val intent =
                        fileChooserParams?.createIntent()
                            ?: Intent(Intent.ACTION_GET_CONTENT).apply { type = "*/*" }
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    webUIState.uiEvent = WebUIEvent.ShowFileChooser(intent)
                    return true
                }
            }

        val webViewInterface = WebViewInterface(webUIState)
        webUIState.webViewInterface = webViewInterface
        webUIState.webView = webView
        webView.addJavascriptInterface(webViewInterface, "ksu")
        webUIState.uiEvent = WebUIEvent.WebViewReady
    }
}

/** webroot 是否真的存在：应用进程读不了 /data/adb，只能问 root shell。 */
private suspend fun rootDirExists(dir: java.io.File): Boolean =
    RootShell.exec("test -d '${dir.absolutePath.replace("'", "'\\''")}' && echo __ok__")
        .output.contains("__ok__")

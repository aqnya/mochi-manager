package com.nekosu.manager.ui.webui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.WebViewAssetLoader
import com.nekosu.manager.R
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import ui.feice.mochi.basic.MochiTextField
import ui.feice.mochi.theme.MochiDefaults
import ui.feice.mochi.theme.MochiTheme

private const val TAG = "NekoSU/WebUI"

@Composable
private fun rememberFileLauncher(webUIState: WebUIState) =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uris: Array<Uri>? =
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.let { data ->
                    data.clipData?.let { clip -> Array(clip.itemCount) { i -> clip.getItemAt(i).uri } }
                        ?: data.data?.let { arrayOf(it) }
                }
            } else {
                null
            }
        webUIState.onFileChooserResult(uris)
    }

/**
 * WebView 本体 + Mochi 皮肤的 alert/confirm/prompt/文件选择。
 *
 * 结构对齐上游：WebView 在组合期 `remember` 创建并直接挂载（不放在 Crossfade /
 * alpha 动画容器里——动画层会吞掉 WebView 的首帧合成，页面跑着但不出画面）；
 * 加载圈只是叠加在 WebView 上层，由 onPageFinished / 主框架错误收起；
 * 拿到非零布局尺寸后才 loadUrl，SPA 按 0×0 视口渲染只会得到空白。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUIScreen(webUIState: WebUIState) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val deviceInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    // 普通页面由 Compose 排 safeDrawing；fullScreen(true) 后只补键盘，安全区交给页面的 CSS 变量
    val innerPadding =
        if (webUIState.isInsetsEnabled) {
            WindowInsets.ime.asPaddingValues()
        } else {
            WindowInsets.safeDrawing.asPaddingValues()
        }
    val fileLauncher = rememberFileLauncher(webUIState)

    var loading by remember { mutableStateOf(true) }
    var urlLoaded by remember { mutableStateOf(false) }

    // WebView 背景用主题底色而不是透明：透明背景在部分设备（HyperOS + WebView 157
    // 实测）上合成器不出首帧，页面加载正常但整屏只剩 Compose 背景透上来
    val webViewBackground = MochiTheme.colorScheme.background.toArgb()

    val modDir = webUIState.modDir
    val webRoot = remember(modDir) { java.io.File("$modDir/webroot") }

    val webView =
        remember(modDir) {
            WebView(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                setBackgroundColor(webViewBackground)
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                }

                val assetLoader =
                    WebViewAssetLoader.Builder()
                        .setDomain(WEB_DOMAIN)
                        .addPathHandler("/", SuFilePathHandler(webRoot) { webUIState.currentInsets })
                        .build()

                webViewClient =
                    object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView,
                            request: WebResourceRequest,
                        ): WebResourceResponse? {
                            // 预检：WebView 偶尔会对带 crossorigin 的资源发 OPTIONS，
                            // 不直接应答就会落到不存在的 DNS 域上失败
                            if (request.method.equals("OPTIONS", ignoreCase = true)) {
                                return WebResourceResponse(
                                    "text/plain",
                                    "utf-8",
                                    204,
                                    "No Content",
                                    WEBUI_RESPONSE_HEADERS,
                                    java.io.ByteArrayInputStream(ByteArray(0)),
                                )
                            }
                            val response = assetLoader.shouldInterceptRequest(request.url) ?: return null
                            // 兜底补 CORS 头：WebView 不把拦截响应当作同源可信响应，
                            // 带 crossorigin 的 CSS / ES module 缺头就会整页加载失败
                            val merged = HashMap(response.responseHeaders ?: emptyMap())
                            merged.putAll(WEBUI_RESPONSE_HEADERS)
                            response.responseHeaders = merged
                            return response
                        }

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

                        override fun onPageFinished(
                            view: WebView,
                            url: String,
                        ) {
                            loading = false
                            // 诊断：页面内容与视口尺寸，白屏排查时直接看这条就知道
                            // 内容是否进来、视口是不是 0×0
                            view.evaluateJavascript(
                                "(function(){try{var b=document.body;return (b?b.innerText.length:-1)+'/'+" +
                                    "(b?b.innerHTML.length:-1)+' viewport='+window.innerWidth+'x'+window.innerHeight;}catch(e){return 'err:'+e;}})()",
                            ) { result -> Log.i(TAG, "dom=$result") }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            request: WebResourceRequest,
                            error: WebResourceError,
                        ) {
                            if (request.isForMainFrame) {
                                loading = false
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

                webChromeClient =
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

                addJavascriptInterface(WebViewInterface(webUIState), "ksu")
                webUIState.webView = this
            }
        }

    // 普通返回：有历史就后退，没有就退出（对齐上游）
    BackHandler(enabled = true) {
        if (webView.canGoBack()) webView.goBack() else webUIState.requestExit()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
    ) {
        AndroidView(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { size ->
                        if (!urlLoaded && size.width > 0 && size.height > 0) {
                            urlLoaded = true
                            Log.i(TAG, "loadUrl $WEB_HOME_URL width=${size.width} height=${size.height}")
                            webView.loadUrl(WEB_HOME_URL)
                        }
                    },
            factory = { webView },
            update = { it.requestLayout() },
        )

        if (loading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MochiDefaults.groupedBackground()),
                contentAlignment = Alignment.Center,
            ) {
                MochiActivityIndicator(size = 28.dp)
            }
        }
    }

    HandleWebUIEventMochi(webUIState, fileLauncher)

    HandleWebViewLifecycle(webUIState)
    HandleConfigurationChanges(webUIState)
}

/** JS alert / confirm / prompt 和文件选择，全部换成 Mochi 的弹窗。 */
@Composable
private fun HandleWebUIEventMochi(
    webUIState: WebUIState,
    fileLauncher: androidx.activity.result.ActivityResultLauncher<Intent>,
) {
    when (val event = webUIState.uiEvent) {
        is WebUIEvent.ShowAlert -> {
            var shown by remember(event) { mutableStateOf(true) }
            MochiDialog(
                visible = shown,
                onDismissRequest = {
                    if (shown) webUIState.onAlertResult()
                    shown = false
                },
                title = event.message,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary),
                    ),
            )
        }

        is WebUIEvent.ShowConfirm -> {
            var shown by remember(event) { mutableStateOf(true) }
            var answered by remember(event) { mutableStateOf(false) }
            MochiDialog(
                visible = shown,
                onDismissRequest = {
                    if (!answered) webUIState.onConfirmResult(false)
                    shown = false
                },
                title = event.message,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(R.string.cancel), MochiDialogActionRole.Cancel, onClick = {
                            answered = true
                            webUIState.onConfirmResult(false)
                        }),
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary, onClick = {
                            answered = true
                            webUIState.onConfirmResult(true)
                        }),
                    ),
            )
        }

        is WebUIEvent.ShowPrompt -> {
            var shown by remember(event) { mutableStateOf(true) }
            var answered by remember(event) { mutableStateOf(false) }
            var text by remember(event) { mutableStateOf(event.defaultValue) }
            MochiDialog(
                visible = shown,
                onDismissRequest = {
                    if (!answered) webUIState.onPromptResult(null)
                    shown = false
                },
                title = event.message,
                actions =
                    persistentListOf(
                        MochiDialogAction(stringResource(R.string.cancel), MochiDialogActionRole.Cancel, onClick = {
                            answered = true
                            webUIState.onPromptResult(null)
                        }),
                        MochiDialogAction(stringResource(android.R.string.ok), MochiDialogActionRole.Primary, onClick = {
                            answered = true
                            webUIState.onPromptResult(text)
                        }),
                    ),
            ) {
                MochiTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
            }
        }

        is WebUIEvent.ShowFileChooser -> LaunchedEffect(event) {
            try {
                fileLauncher.launch(event.intent)
            } catch (_: Exception) {
                webUIState.onFileChooserResult(null)
            }
        }

        else -> Unit
    }
}

@Composable
private fun HandleWebViewLifecycle(webUIState: WebUIState) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, webUIState) {
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> webUIState.webView?.onResume()
                    Lifecycle.Event.ON_PAUSE -> webUIState.webView?.onPause()
                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun HandleConfigurationChanges(webUIState: WebUIState) {
    val configuration = LocalConfiguration.current
    LaunchedEffect(configuration.fontScale, webUIState.webView) {
        webUIState.webView?.settings?.textZoom = (configuration.fontScale * 100).toInt()
    }
}

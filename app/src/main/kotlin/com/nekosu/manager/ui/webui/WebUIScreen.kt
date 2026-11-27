package com.nekosu.manager.ui.webui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.collections.immutable.persistentListOf
import ui.feice.mochi.basic.MochiDialog
import ui.feice.mochi.basic.MochiDialogAction
import ui.feice.mochi.basic.MochiDialogActionRole
import com.nekosu.manager.R
import androidx.compose.ui.res.stringResource
import ui.feice.mochi.basic.MochiTextField

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

/** WebView 本体 + Mochi 皮肤的 alert/confirm/prompt/文件选择。 */
@Composable
fun WebUIScreen(webUIState: WebUIState) {
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

    // 全屏模式下把 insets 换算成 px 持续下发；模块页读 --safe-area-inset-* 排版
    LaunchedEffect(density, layoutDirection, deviceInsets, webUIState.isInsetsEnabled) {
        if (!webUIState.isInsetsEnabled) return@LaunchedEffect
        snapshotFlow {
            WebInsets(
                top = (deviceInsets.getTop(density) / density.density).toInt(),
                bottom = (deviceInsets.getBottom(density) / density.density).toInt(),
                left = (deviceInsets.getLeft(density, layoutDirection) / density.density).toInt(),
                right = (deviceInsets.getRight(density, layoutDirection) / density.density).toInt(),
            )
        }.collect { newInsets ->
            if (webUIState.currentInsets != newInsets) {
                webUIState.currentInsets = newInsets
                webUIState.webView?.evaluateJavascript(newInsets.js, null)
            }
        }
    }

    BackHandler(enabled = webUIState.webCanGoBack) {
        webUIState.webView?.goBack()
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
    ) {
        webUIState.webView?.let { webView ->
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { _ ->
                    webView.apply {
                        layoutParams =
                            ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT,
                            )
                        if (!webUIState.isUrlLoaded) {
                            loadUrl("https://" + WEB_DOMAIN + "/index.html")
                            webUIState.isUrlLoaded = true
                        }
                    }
                },
                update = { view -> view.requestLayout() },
            )
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

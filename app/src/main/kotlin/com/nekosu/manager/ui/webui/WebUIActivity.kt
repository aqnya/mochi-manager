package com.nekosu.manager.ui.webui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.ui.theme.NekoSUTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.feice.mochi.basic.MochiActivityIndicator
import ui.feice.mochi.theme.MochiDefaults

/** WebUI 域名：WebViewAssetLoader 把模块 webroot 挂在这个 https 域上。 */
internal const val WEB_DOMAIN = "mui.nekosu.local"

internal const val WEB_HOME_URL = "https://$WEB_DOMAIN/index.html"

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

    // 模块校验通过后才挂 WebView；校验期间全屏 loading
    var validated by remember { mutableStateOf(false) }

    LaunchedEffect(moduleId) {
        if (moduleId == null) {
            onFinish()
            return@LaunchedEffect
        }
        val error = validateModule(activity, moduleId, webUIState)
        if (error != null) {
            webUIState.uiEvent = WebUIEvent.Error(error)
        } else {
            validated = true
        }
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

    if (validated) {
        // 关键：WebView 不能挂在 Crossfade / alpha 动画容器里。带 graphicsLayer 的
        // 动画层会让部分设备的 WebView 合成器不出首帧（JS 正常执行但整屏空白），
        // 对齐上游：WebView 在组合期直接挂载，加载圈只是叠在它上层。
        WebUIScreen(webUIState)
    } else {
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

/**
 * 校验模块并把 modDir/moduleName 写进 [webUIState]。
 *
 * 失败（找不到模块、模块禁用、没有 webroot）返回错误文案，页面 toast 后退出。
 */
private suspend fun validateModule(
    activity: WebUIActivity,
    moduleId: String,
    webUIState: WebUIState,
): String? {
    val moduleInfo =
        withContext(Dispatchers.IO) {
            runCatching { com.nekosu.manager.data.ModuleStore.load() }.getOrNull()?.firstOrNull { it.id == moduleId }
        }
    if (moduleInfo == null) {
        return activity.getString(R.string.module_webui_no_module, moduleId)
    }
    if (!moduleInfo.enabled || moduleInfo.remove) {
        return activity.getString(R.string.module_webui_unavailable, moduleInfo.name)
    }
    val modDir = "/data/adb/modules/$moduleId"
    val webRoot = java.io.File("$modDir/webroot")
    if (!withContext(Dispatchers.IO) { rootDirExists(webRoot) }) {
        return activity.getString(R.string.module_webui_no_webroot, moduleInfo.name)
    }

    webUIState.modDir = modDir
    webUIState.moduleName = moduleInfo.name
    return null
}

/** webroot 是否真的存在：应用进程读不了 /data/adb，只能问 root shell。 */
private suspend fun rootDirExists(dir: java.io.File): Boolean =
    com.nekosu.manager.data.RootShell
        .exec("test -d '${dir.absolutePath.replace("'", "'\\''")}' && echo __ok__")
        .output.contains("__ok__")

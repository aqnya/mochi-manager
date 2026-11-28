// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.ui.webui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.nekosu.manager.ui.theme.NekoSUTheme

/**
 * 承载模块 WebUI 的独立 Activity —— 直接使用上游 NekoSU 的实现。
 *
 * 模块在 `<module>/webroot` 下放网页；[WebUIScreen] 用 [androidx.webkit.WebViewAssetLoader]
 * + [ModulePathHandler] 把它映射到 `https://<domain>/` 下（文件由 root 读取），
 * 注入 `window.ksu`（见 [WebViewBridge]），并处理 `ksu://icon`、`internal/insets.css`、
 * `internal/colors.css`。
 */
class WebUIActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val moduleId = intent.getStringExtra(EXTRA_ID) ?: intent.data?.getQueryParameter("id")
        if (moduleId.isNullOrBlank()) {
            finish()
            return
        }
        setContent {
            // 本工程的主题是 Mochi（配色由 MonetColorsProvider 导出），不是裸 MaterialTheme
            NekoSUTheme {
                WebUIScreen(moduleId = moduleId, onExit = { finish() })
            }
        }
    }

    companion object {
        const val EXTRA_ID = "id"

        fun intent(
            context: Context,
            moduleId: String,
        ): Intent =
            Intent(context, WebUIActivity::class.java)
                .putExtra(EXTRA_ID, moduleId)
                .setData(Uri.parse("nksu://webui?id=$moduleId"))
    }
}

/**
 * 打开模块 WebUI 的标准入口；模块卡片上的「打开」按钮用它。
 *
 * 模块 id 走 extra 传，不拼 URI——id 来自模块目录名，可能带特殊字符。
 */
fun webUiIntent(
    context: Context,
    moduleId: String,
): Intent = WebUIActivity.intent(context, moduleId)

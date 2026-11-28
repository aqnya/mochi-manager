// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nekosu.manager.R
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold

/**
 * 开放源代码许可页（对齐上游 `OpenSourceScreen`）：列出随应用分发的组件及许可证，
 * 点击行在浏览器打开对应项目主页。列表是本应用实际依赖的一份静态记录，
 * 不读运行时包——这样即使某个依赖被 shade 进去也能稳定展示。
 */
private data class LicenseItem(
    val name: String,
    val author: String,
    val license: String,
    val url: String,
)

private val LICENSES =
    listOf(
        LicenseItem("Jetpack Compose", "Google", "Apache-2.0", "https://developer.android.com/jetpack/compose"),
        LicenseItem("Material 3", "Google", "Apache-2.0", "https://m3.material.io/"),
        LicenseItem("Kotlin", "JetBrains", "Apache-2.0", "https://kotlinlang.org/"),
        LicenseItem("Mochi Compose", "feice11", "MIT", "https://github.com/feice11/mochi-compose"),
        LicenseItem("AndroidX WebKit", "Google", "Apache-2.0", "https://developer.android.com/reference/androidx/webkit/package-summary"),
        LicenseItem("Kotlin Coroutines", "JetBrains", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines"),
    )

@Composable
fun OpenSourceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.about_open_source),
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
    ) {
        item(key = "licenses") {
            MochiListSection(header = stringResource(R.string.about_open_source)) {
                LICENSES.forEach { item ->
                    MochiListRow(
                        item.name,
                        subtitle = "${item.author} · ${item.license}",
                        chevron = true,
                        onClick = { openLicenseUrl(context, item.url) },
                    )
                }
            }
        }
    }
}

/** 用浏览器打开组件主页；设备没有可处理的应用时静默忽略。 */
private fun openLicenseUrl(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.nekosu.manager.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nekosu.manager.R
import com.nekosu.manager.ui.mochi.KsuTile
import com.nekosu.manager.ui.mochi.KsuTileColors
import com.nekosu.manager.ui.mochi.MochiCredit
import com.nekosu.manager.ui.mochi.MochiFootnote
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.list.MochiListRow
import ui.feice.mochi.list.MochiListSection
import ui.feice.mochi.navigation.MochiBackButton
import ui.feice.mochi.navigation.MochiLazyLargeTitleScaffold
import ui.feice.mochi.theme.MochiTheme

private const val REPOSITORY_URL = "https://github.com/nksu-crew/NekoSU"

/**
 * 关于页（对齐上游 `AboutScreen`）：应用图标与名称、版本/许可证、项目链接与开源许可。
 *
 * 版本用 [managerVersion] 从 PackageManager 读取并做 SDK 兼容，取不到时回退到
 * 「未知版本」而不是抛异常；链接统一走浏览器 Intent，失败静默忽略（设备没有浏览器时
 * 不能让页面崩掉）。
 */
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    onOpenOpenSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val versionName = remember(context) { managerVersion(context) }
    val unknown = stringResource(R.string.about_unknown_version)

    MochiLazyLargeTitleScaffold(
        title = stringResource(R.string.about),
        modifier = modifier,
        navigationIcon = { MochiBackButton(onClick = onBack) },
    ) {
        item(key = "hero") {
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 用图标字体而不是 ic_launcher：自适应图标（XML mipmap）在部分
                // 宿主（含 Robolectric 渲染测试）下 painterResource 会抛异常。
                MochiIonicon(
                    "sparkles",
                    tint = MochiTheme.colorScheme.primary,
                    size = 72.dp,
                )
                MochiFootnote(
                    text = stringResource(R.string.app_name),
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }

        item(key = "information") {
            MochiListSection(header = stringResource(R.string.about_information)) {
                MochiListRow(
                    stringResource(R.string.home_manager_version),
                    value = versionName ?: unknown,
                    leading = { KsuTile("information-circle", KsuTileColors.Blue) },
                )
                MochiListRow(
                    stringResource(R.string.about_license),
                    value = stringResource(R.string.about_license_value),
                    leading = { KsuTile("document-text", KsuTileColors.Gray) },
                )
            }
        }

        item(key = "project") {
            MochiListSection(header = stringResource(R.string.about_project)) {
                MochiListRow(
                    stringResource(R.string.about_repository),
                    subtitle = stringResource(R.string.about_repository_summary),
                    leading = { KsuTile("code-slash", KsuTileColors.Indigo) },
                    chevron = true,
                    onClick = { openUrl(context, REPOSITORY_URL) },
                )
                MochiListRow(
                    stringResource(R.string.about_open_source),
                    subtitle = stringResource(R.string.about_open_source_summary),
                    leading = { KsuTile("sparkles", KsuTileColors.Green) },
                    chevron = true,
                    onClick = onOpenOpenSource,
                )
            }
        }

        item(key = "credit") { MochiCredit(stringResource(R.string.mochi_credit)) }
    }
}

/**
 * 读取管理器版本串。TIRAMISU 起 [PackageManager.getPackageInfo] 需要 [PackageInfoFlags]，
 * 旧版本走重载；任何失败都返回 null 让上层显示「未知版本」。
 */
private fun managerVersion(context: Context): String? =
    runCatching {
        val pm = context.packageManager
        val info =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, 0)
            }
        info.versionName
    }.getOrNull()?.takeIf { it.isNotBlank() }

/** 用浏览器打开链接；设备没有可处理的应用时静默忽略。 */
private fun openUrl(
    context: Context,
    url: String,
) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }
}

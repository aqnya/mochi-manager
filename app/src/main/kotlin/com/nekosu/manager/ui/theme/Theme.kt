package com.nekosu.manager.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController

/**
 * Mochi UI 主题。使用系统壁纸主色（Monet）生成配色，Android 12 以下退回默认种子色，
 * 明暗跟随系统，同时由 MochiTheme 负责状态栏 / 导航栏图标的明暗。
 */
@Composable
fun NekoSUTheme(content: @Composable () -> Unit) {
    MochiTheme(controller = remember { ThemeController(ColorSchemeMode.MonetSystem) }) {
        content()
    }
}

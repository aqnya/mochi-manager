package com.nekosu.manager.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nekosu.manager.data.AppearanceSettings
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiMaterials
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.theme.mochiGlassMaterials

/**
 * Mochi UI 主题。使用系统壁纸主色（Monet）生成配色，Android 12 以下退回默认种子色，
 * 明暗跟随系统，同时由 MochiTheme 负责状态栏 / 导航栏图标的明暗。
 *
 * 材质来自 [AppearanceSettings.glass]：开启时用 [mochiGlassMaterials]（bar / control /
 * menu / alert / sheet 全部换成玻璃），关闭时用默认的 [MochiMaterials]（不透明）。
 * 因为 glass 是 Compose 状态，设置页一拨开关，整棵界面立刻换材质。
 */
@Composable
fun NekoSUTheme(content: @Composable () -> Unit) {
    val appearance = AppearanceSettings.get(LocalContext.current)
    val materials = remember(appearance.glass) { mochiMaterialsFor(appearance.glass) }
    MochiTheme(
        controller = remember { ThemeController(ColorSchemeMode.MonetSystem) },
        materials = materials,
    ) {
        content()
    }
}

/**
 * 玻璃开关到材质的映射：开 = bar / control / menu / alert / sheet 全部玻璃，
 * 关 = 默认不透明材质。抽出来是为了能单测，避免开关接错材质还看不出来。
 */
internal fun mochiMaterialsFor(glass: Boolean): MochiMaterials =
    if (glass) mochiGlassMaterials() else MochiMaterials()

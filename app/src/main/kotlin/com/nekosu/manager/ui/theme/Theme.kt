package com.nekosu.manager.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.nekosu.manager.data.AppearanceSettings
import com.nekosu.manager.data.GlassStyle
import ui.feice.mochi.theme.ColorSchemeMode
import ui.feice.mochi.theme.MochiGlass
import ui.feice.mochi.theme.MochiGlassDefaults
import ui.feice.mochi.theme.MochiMaterials
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.theme.ThemeController
import ui.feice.mochi.theme.mochiGlassMaterials

/**
 * Mochi UI 主题。使用系统壁纸主色（Monet）生成配色，Android 12 以下退回默认种子色，
 * 明暗跟随系统，同时由 MochiTheme 负责状态栏 / 导航栏图标的明暗。
 *
 * 材质来自 [AppearanceSettings.glass] 与 [AppearanceSettings.glassStyle]：开启时用
 * [mochiGlassMaterials]（bar / control / menu / alert / sheet 全部换成玻璃），并按样式传入
 * 不同的 [MochiGlass]；关闭时用默认的 [MochiMaterials]（不透明）。因为两者都是 Compose 状态，
 * 设置页一改，整棵界面立刻换材质。
 */
@Composable
fun NekoSUTheme(content: @Composable () -> Unit) {
    val appearance = AppearanceSettings.get(LocalContext.current)
    val glass = appearance.glass
    val style = appearance.glassStyle
    val materials = remember(glass, style) { mochiMaterialsFor(glass, style) }
    MochiTheme(
        controller = remember { ThemeController(ColorSchemeMode.MonetSystem) },
        materials = materials,
    ) {
        content()
    }
}

/**
 * 玻璃开关 / 样式到材质的映射：开 = bar / control / menu / alert / sheet 全部玻璃，
 * 关 = 默认不透明材质。抽出来是为了能单测，避免开关或样式接错还看不出来。
 */
internal fun mochiMaterialsFor(
    glass: Boolean,
    style: GlassStyle = GlassStyle.Regular,
): MochiMaterials = if (glass) mochiGlassMaterials(glass = style.toMochiGlass()) else MochiMaterials()

/** 样式到库里的玻璃：标准 / 厚实，取自 MochiGlassDefaults，保证和库的默认配色一致。 */
internal fun GlassStyle.toMochiGlass(): MochiGlass =
    when (this) {
        GlassStyle.Regular -> MochiGlassDefaults.Regular
        GlassStyle.Thick -> MochiGlassDefaults.Thick
    }

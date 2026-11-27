package com.nekosu.manager.ui.webui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import ui.feice.mochi.theme.MochiTheme

/**
 * 模块 WebUI 的主题 CSS。
 *
 * 模块页按 KernelSU 的约定在 index.html 里引 `<link href="/internal/colors.css">`，
 * 这里把 Mochi 配色整套写成 CSS 变量给它；路径是保留字，不会落到模块目录上。
 * [vars] 是 handler 直接读的值：配色在 Compose 这边算好，请求线程只做拼接响应。
 */
object WebUiCss {
    @Volatile
    var vars: String = ""
}

/** MochiColorScheme → CSS 变量。配色随明暗/主题变化时重写一遍。 */
@Composable
fun UpdateWebUiCss() {
    val scheme = MochiTheme.colorScheme
    LaunchedEffect(scheme) {
        val colors =
            mapOf(
                "primary" to scheme.primary,
                "onPrimary" to scheme.onPrimary,
                "primaryContainer" to scheme.primaryContainer,
                "onPrimaryContainer" to scheme.onPrimaryContainer,
                "inversePrimary" to scheme.inversePrimary,
                "secondary" to scheme.secondary,
                "onSecondary" to scheme.onSecondary,
                "secondaryContainer" to scheme.secondaryContainer,
                "onSecondaryContainer" to scheme.onSecondaryContainer,
                "tertiary" to scheme.tertiary,
                "onTertiary" to scheme.onTertiary,
                "tertiaryContainer" to scheme.tertiaryContainer,
                "onTertiaryContainer" to scheme.onTertiaryContainer,
                "background" to scheme.background,
                "onBackground" to scheme.onBackground,
                "surface" to scheme.surface,
                "tonalSurface" to scheme.surfaceContainerLow,
                "onSurface" to scheme.onSurface,
                "surfaceVariant" to scheme.surfaceVariant,
                "onSurfaceVariant" to scheme.onSurfaceVariant,
                "surfaceTint" to scheme.surfaceTint,
                "inverseSurface" to scheme.inverseSurface,
                "inverseOnSurface" to scheme.inverseOnSurface,
                "error" to scheme.error,
                "onError" to scheme.onError,
                "errorContainer" to scheme.errorContainer,
                "onErrorContainer" to scheme.onErrorContainer,
                "outline" to scheme.outline,
                "outlineVariant" to scheme.outlineVariant,
                "scrim" to scheme.scrim,
                "surfaceBright" to scheme.surfaceBright,
                "surfaceDim" to scheme.surfaceDim,
                "surfaceContainer" to scheme.surfaceContainer,
                "surfaceContainerHigh" to scheme.surfaceContainerHigh,
                "surfaceContainerHighest" to scheme.surfaceContainerHighest,
                "surfaceContainerLow" to scheme.surfaceContainerLow,
                "surfaceContainerLowest" to scheme.surfaceContainerLowest,
                "filledTonalButtonContentColor" to scheme.onPrimaryContainer,
                "filledTonalButtonContainerColor" to scheme.secondaryContainer,
                "filledTonalButtonDisabledContentColor" to scheme.onSurfaceVariant,
                "filledTonalButtonDisabledContainerColor" to scheme.surfaceVariant,
                "filledCardContentColor" to scheme.onPrimaryContainer,
                "filledCardContainerColor" to scheme.primaryContainer,
                "filledCardDisabledContentColor" to scheme.onSurfaceVariant,
                "filledCardDisabledContainerColor" to scheme.surfaceVariant,
            )
        WebUiCss.vars = buildString {
            appendLine(":root {")
            for ((name, color) in colors) {
                appendLine("  --$name: ${color.toCssValue()};")
            }
            append("}")
        }
    }
}

private fun androidx.compose.ui.graphics.Color.toCssValue(): String {
    fun Float.toHex(): String = (this * 255).toInt().coerceIn(0, 255).toString(16).padStart(2, '0')
    return if (alpha == 1f) {
        "#${red.toHex()}${green.toHex()}${blue.toHex()}"
    } else {
        "#${red.toHex()}${green.toHex()}${blue.toHex()}${alpha.toHex()}"
    }
}

package com.nekosu.manager.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator

private const val RouteSettings = "settings/list"
private const val RouteTheme = "settings/theme"

/**
 * 设置 tab 的导航容器：设置列表 → 主题设置。
 *
 * 和超级用户一致，用 MochiNavHost 的 iOS 侧推转场，系统侧滑返回交给它的
 * PredictiveBackHandler，不会直接退出应用。
 */
@Composable
fun SettingsFlow(modifier: Modifier = Modifier) {
    val navigator = rememberMochiNavigator(root = RouteSettings)
    MochiNavHost(navigator = navigator, modifier = modifier.fillMaxSize()) { route ->
        when (route) {
            RouteTheme -> ThemeScreen(onBack = { navigator.pop() })
            else -> SettingsScreen(onOpenTheme = { navigator.push(RouteTheme) })
        }
    }
}

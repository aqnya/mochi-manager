package com.example.myapp

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabBarScrollBehavior
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.rememberMochiTabBarScrollBehavior
import ui.feice.mochi.icons.MochiIonicon

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute

    @Serializable
    data object Apps : AppRoute

    @Serializable
    data object Rules : AppRoute

    @Serializable
    data object Settings : AppRoute

    @Serializable
    data class Detail(
        val itemId: String,
    ) : AppRoute
}

internal data class TopLevelTab(
    val route: AppRoute,
    val title: String,
    val icon: String,
)

internal val topLevelTabs: List<TopLevelTab> =
    listOf(
        TopLevelTab(route = AppRoute.Home, title = "首页", icon = "home"),
        TopLevelTab(route = AppRoute.Apps, title = "应用", icon = "extension-puzzle"),
        TopLevelTab(route = AppRoute.Rules, title = "规则", icon = "shield-checkmark"),
        TopLevelTab(route = AppRoute.Settings, title = "设置", icon = "settings"),
    )

@Composable
fun MainBottomNavBar(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: MochiTabBarScrollBehavior = rememberMochiTabBarScrollBehavior(),
) {
    val items =
        remember {
            topLevelTabs.map { tab ->
                MochiTabItem(label = tab.title) { tint ->
                    MochiIonicon(tab.icon, tint = tint, size = 22.dp)
                }
            }.toImmutableList()
        }

    MochiTabBar(
        selectedIndex = selectedIndex,
        onSelect = onSelectIndex,
        items = items,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        behavior = MochiTabBarDefaults.behavior(),
        scrollBehavior = scrollBehavior,
        // 导航栏留白由外层 Scaffold 的 bottomBar 统一处理，避免重复叠加
        windowInsets = WindowInsets(0),
    )
}

package com.example.myapp

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabBarScrollBehavior
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.rememberMochiTabBarScrollBehavior
import ui.feice.mochi.navigation.MochiTabScaffold
import ui.feice.mochi.theme.MochiTheme
import ui.feice.mochi.basic.Text as MochiText

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

@Composable
fun MainBottomNavBar(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: MochiTabBarScrollBehavior = rememberMochiTabBarScrollBehavior(),
) {
    val tabs =
        remember {
            listOf(
                MochiTabItem(
                    "首页",
                    selectedIcon = { MochiText("⌂", style = MochiTheme.textStyles.titleLarge) },
                    icon = { MochiText("⌂", style = MochiTheme.textStyles.titleLarge) },
                ),
                MochiTabItem(
                    "应用",
                    selectedIcon = { MochiText("▦", style = MochiTheme.textStyles.titleLarge) },
                    icon = { MochiText("▦", style = MochiTheme.textStyles.titleLarge) },
                ),
                MochiTabItem(
                    "规则",
                    selectedIcon = { MochiText("⚙", style = MochiTheme.textStyles.titleLarge) },
                    icon = { MochiText("⚙", style = MochiTheme.textStyles.titleLarge) },
                ),
                MochiTabItem(
                    "设置",
                    selectedIcon = { MochiText("☰", style = MochiTheme.textStyles.titleLarge) },
                    icon = { MochiText("☰", style = MochiTheme.textStyles.titleLarge) },
                ),
            ).toImmutableList()
        }

/*    MochiTabBar(
        selectedIndex = selectedIndex,
        onSelect = onSelectIndex,


        scrollBehavior = scrollBehavior,

        windowInsets = WindowInsets(0),
    )*/
    MochiTabScaffold(
        items = tabs,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        behavior = MochiTabBarDefaults.behavior(),
        collapseOnScroll = false,
    )
}

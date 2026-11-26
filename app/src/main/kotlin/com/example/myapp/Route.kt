package com.example.myapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabBarScrollBehavior
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.rememberMochiTabBarScrollBehavior
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.MochiTabScaffold

private val TabIcons = listOf("home", "shield-checkmark", "extension-puzzle", "settings")

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
    onHomeContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("首页") }
    },
    onAppsContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("应用") }
    },
    onRulesContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("规则") }
    },
    onSettingsContent: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("设置") }
    },
) {
    val tabTitles = remember { listOf("首页", "应用", "规则", "设置") }
    val tabs =
        remember {
            tabTitles.mapIndexed { index, title ->
                val iconName = TabIcons[index]
                MochiTabItem(
                    label = title,
                    icon = { tint -> MochiIonicon(iconName, tint = tint, size = 22.dp) },
                )
            }.toImmutableList()
        }

    MochiTabScaffold(
        items = tabs,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        behavior = MochiTabBarDefaults.behavior(),
        collapseOnScroll = false,
    ) { page ->
        when (page) {
            0 -> onHomeContent()
            1 -> onAppsContent()
            2 -> onRulesContent()
            else -> onSettingsContent()
        }
    }
}

package com.example.myapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
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
import ui.feice.mochi.navigation.MochiTabScaffold

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
    val tabs =
        remember {
            listOf(
                MochiTabItem(
                    "首页",
                    selectedIcon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                    icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                ),
                MochiTabItem(
                    "应用",
                    selectedIcon = { Icon(Icons.Default.Apps, contentDescription = "应用") },
                    icon = { Icon(Icons.Default.Apps, contentDescription = "应用") },
                ),
                MochiTabItem(
                    "规则",
                    selectedIcon = { Icon(Icons.Default.Settings, contentDescription = "规则") },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "规则") },
                ),
                MochiTabItem(
                    "设置",
                    selectedIcon = { Icon(Icons.Default.Menu, contentDescription = "设置") },
                    icon = { Icon(Icons.Default.Menu, contentDescription = "设置") },
                ),
            ).toImmutableList()
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

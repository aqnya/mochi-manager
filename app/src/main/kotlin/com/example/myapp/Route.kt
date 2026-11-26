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
import com.example.myapp.ui.main.MainScreen

private val TabIcons = listOf("home")

@Serializable
sealed interface AppRoute : NavKey {
    @Serializable
    data object Home : AppRoute
}

@Composable
fun MainBottomNavBar(
    selectedIndex: Int,
    onSelectIndex: (Int) -> Unit,
    modifier: Modifier = Modifier,
    scrollBehavior: MochiTabBarScrollBehavior = rememberMochiTabBarScrollBehavior(),
) {
    val tabTitles = remember { listOf("首页") }
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
            0 -> MainScreen()
        }
    }
}

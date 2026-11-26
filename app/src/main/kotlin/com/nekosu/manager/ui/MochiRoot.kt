package com.nekosu.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.nekosu.manager.ui.home.HomeScreen
import com.nekosu.manager.ui.module.ModuleScreen
import com.nekosu.manager.ui.settings.SettingsScreen
import com.nekosu.manager.ui.superuser.SuperUserScreen
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.MochiTabScaffold
import ui.feice.mochi.navigation.rememberMochiTabScaffoldState

private data class RootTab(
    val label: String,
    val icon: String,
    val badge: String? = null,
)

private val rootTabs =
    listOf(
        RootTab("首页", "home"),
        RootTab("超级用户", "shield-checkmark", "3"),
        RootTab("模块", "extension-puzzle", "1"),
        RootTab("设置", "settings"),
    )

/** 底部四个 Tab 的根容器，页面内容各自带大标题栏。 */
@Composable
fun MochiRoot() {
    val tabState = rememberMochiTabScaffoldState(initialIndex = 0)
    val items =
        remember {
            rootTabs
                .map { tab ->
                    MochiTabItem(label = tab.label, badge = tab.badge) { tint ->
                        MochiIonicon(tab.icon, tint = tint, size = 22.dp)
                    }
                }.toImmutableList()
        }

    MochiTabScaffold(
        items = items,
        state = tabState,
        collapseOnScroll = false,
        behavior = MochiTabBarDefaults.iconOnlyBehavior(selectedLabelOnly = true),
    ) { index ->
        when (index) {
            0 -> HomeScreen(onOpenTab = { tabState.select(it) })
            1 -> SuperUserScreen()
            2 -> ModuleScreen()
            else -> SettingsScreen()
        }
    }
}

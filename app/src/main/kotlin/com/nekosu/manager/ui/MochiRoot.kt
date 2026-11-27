package com.nekosu.manager.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nekosu.manager.data.AppGroup
import com.nekosu.manager.data.registry.RepoModule
import com.nekosu.manager.ui.home.HomeScreen
import com.nekosu.manager.ui.module.ModuleInstallScreen
import com.nekosu.manager.ui.module.ModuleRepoDetailScreen
import com.nekosu.manager.ui.module.ModuleRepoScreen
import com.nekosu.manager.ui.module.ModuleScreen
import com.nekosu.manager.ui.profile.AppProfileScreen
import com.nekosu.manager.ui.settings.SettingsScreen
import com.nekosu.manager.ui.settings.ThemeScreen
import com.nekosu.manager.ui.superuser.SuperUserScreen
import kotlinx.collections.immutable.toImmutableList
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.icons.MochiIonicon
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.MochiTabScaffold
import ui.feice.mochi.navigation.rememberMochiNavigator
import ui.feice.mochi.navigation.rememberMochiTabScaffoldState

private const val RouteMain = "main"
private const val RouteAppProfile = "su/profile"
private const val RouteModuleRepo = "module/repo"
private const val RouteModuleDetail = "module/repo/detail"
private const val RouteModuleInstall = "module/install"
private const val RouteTheme = "settings/theme"

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

/**
 * 根导航：**标签骨架是 `MochiNavHost` 的根页**，二级页推到同一个栈上。
 *
 * 底部标签栏因此交给库按 iOS 的 `hidesBottomBarWhenPushed` 处理：推入新页时整页（连标签栏）
 * 往左退并被新页盖住，二级页里不会再出现标签栏，也不用手动开关它的可见性
 * （`MochiTabScaffold` 里的 `mochiHideWhenCovered` 依赖的正是同一条导航栈）。
 *
 * 标签页自己的状态（滚动位置、选中项）照旧保留：栈顶下面那一页始终组合着，返回时原样回来。
 */
@Composable
fun MochiRoot() {
    val navigator = rememberMochiNavigator(root = RouteMain)
    // 二级页的对象（应用组、仓库条目）放在导航栈外面，页面只按路由取值
    var openedGroup by remember { mutableStateOf<AppGroup?>(null) }
    var openedRepoModule by remember { mutableStateOf<RepoModule?>(null) }
    var moduleReloadKey by remember { mutableIntStateOf(0) }

    MochiNavHost(navigator = navigator) { route ->
        when (route) {
            // —— 二级页 ——
            RouteAppProfile -> {
                val group = openedGroup
                if (group == null) {
                    // 进程被回收后栈里还有这一页但对象没了：退回根页，避免白屏
                    LaunchedEffect(Unit) { navigator.popTo(0) }
                } else {
                    AppProfileScreen(group = group, onBack = { navigator.pop() })
                }
            }

            RouteModuleRepo ->
                ModuleRepoScreen(
                    onBack = { navigator.pop() },
                    onOpenModule = { module ->
                        openedRepoModule = module
                        navigator.push(RouteModuleDetail)
                    },
                )

            RouteModuleDetail -> {
                val module = openedRepoModule
                if (module == null) {
                    LaunchedEffect(Unit) { navigator.popTo(0) }
                } else {
                    ModuleRepoDetailScreen(module = module, onBack = { navigator.pop() })
                }
            }

            RouteModuleInstall ->
                ModuleInstallScreen(
                    onExit = {
                        // 退出安装页后让模块列表重读 /data/adb/modules
                        moduleReloadKey++
                        navigator.pop()
                    },
                )

            RouteTheme -> ThemeScreen(onBack = { navigator.pop() })

            // —— 根页：四个标签 ——
            else ->
                MainTabs(
                    moduleReloadKey = moduleReloadKey,
                    onOpenGroup = { group ->
                        openedGroup = group
                        navigator.push(RouteAppProfile)
                    },
                    onOpenRepo = { navigator.push(RouteModuleRepo) },
                    onOpenInstall = { navigator.push(RouteModuleInstall) },
                    onOpenTheme = { navigator.push(RouteTheme) },
                )
        }
    }
}

/** 底部四个 Tab 的骨架，页面内容各自带大标题栏。 */
@Composable
private fun MainTabs(
    moduleReloadKey: Int,
    onOpenGroup: (AppGroup) -> Unit,
    onOpenRepo: () -> Unit,
    onOpenInstall: () -> Unit,
    onOpenTheme: () -> Unit,
) {
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
            1 -> SuperUserScreen(onOpenApp = onOpenGroup)
            2 ->
                ModuleScreen(
                    onOpenRepo = onOpenRepo,
                    onOpenInstall = onOpenInstall,
                    reloadKey = moduleReloadKey,
                    modifier = Modifier,
                )
            else -> SettingsScreen(onOpenTheme = onOpenTheme)
        }
    }
}

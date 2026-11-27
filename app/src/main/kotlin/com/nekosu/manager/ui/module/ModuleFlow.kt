package com.nekosu.manager.ui.module

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nekosu.manager.data.registry.RepoModule
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator

private const val RouteList = "module/list"
private const val RouteRepo = "module/repo"
private const val RouteRepoDetail = "module/repo/detail"
private const val RouteInstall = "module/install"

/**
 * 模块 tab 的导航容器：已安装模块 → 模块仓库 → 仓库详情，以及本地 ZIP 的安装输出页。
 *
 * 转场用 MochiNavHost 的 iOS 侧推，侧滑返回由它的 PredictiveBackHandler 接管，
 * 和超级用户、设置两条链路一致。安装页返回时把 [reloadKey] 加一，列表重新读
 * `/data/adb/modules`——装完立刻能看到新模块，不用手动下拉。
 */
@Composable
fun ModuleFlow(modifier: Modifier = Modifier) {
    val navigator = rememberMochiNavigator(root = RouteList)
    var opened by remember { mutableStateOf<RepoModule?>(null) }
    var reloadKey by remember { mutableIntStateOf(0) }

    MochiNavHost(navigator = navigator, modifier = modifier.fillMaxSize()) { route ->
        when (route) {
            RouteRepo ->
                ModuleRepoScreen(
                    onBack = { navigator.pop() },
                    onOpenModule = { module ->
                        opened = module
                        navigator.push(RouteRepoDetail)
                    },
                )

            RouteRepoDetail -> {
                val module = opened
                if (module == null) {
                    // 进程被回收后栈里还有详情但对象没了：退回模块列表，避免白屏
                    LaunchedEffect(Unit) { navigator.popTo(0) }
                } else {
                    ModuleRepoDetailScreen(module = module, onBack = { navigator.pop() })
                }
            }

            RouteInstall ->
                ModuleInstallScreen(
                    onExit = {
                        reloadKey++
                        navigator.pop()
                    },
                )

            else ->
                ModuleScreen(
                    onOpenRepo = { navigator.push(RouteRepo) },
                    onOpenInstall = { navigator.push(RouteInstall) },
                    reloadKey = reloadKey,
                )
        }
    }
}

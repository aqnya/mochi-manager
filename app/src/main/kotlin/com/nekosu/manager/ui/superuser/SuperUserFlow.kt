package com.nekosu.manager.ui.superuser

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nekosu.manager.data.AppGroup
import com.nekosu.manager.ui.profile.AppProfileScreen
import ui.feice.mochi.navigation.MochiNavHost
import ui.feice.mochi.navigation.rememberMochiNavigator

private const val RouteList = "su/list"
private const val RouteProfile = "su/profile"

/**
 * 超级用户 tab 的导航容器：列表 → 应用配置页使用 MochiNavHost。
 *
 * 转场是库自带的 iOS 侧推：新页从右边推进来，下层列表往左退 30% 并压暗，新页左缘带投影；
 * 系统返回手势（侧滑）由 NavHost 的 PredictiveBackHandler 接管，跟手滑动、松手按末速度滑出，
 * 不再出现「侧滑直接退出应用」。
 */
@Composable
fun SuperUserFlow(modifier: Modifier = Modifier) {
    val navigator = rememberMochiNavigator(root = RouteList)
    var opened by remember { mutableStateOf<AppGroup?>(null) }

    MochiNavHost(navigator = navigator, modifier = modifier.fillMaxSize()) { route ->
        when (route) {
            RouteProfile -> {
                val group = opened
                if (group == null) {
                    // 进程被回收后重建时栈里还有 profile 但对象没了：退回列表，避免白屏
                    LaunchedEffect(Unit) { navigator.popTo(0) }
                } else {
                    AppProfileScreen(group = group, onBack = { navigator.pop() })
                }
            }

            else -> SuperUserScreen(
                onOpenApp = { group ->
                    opened = group
                    navigator.push(RouteProfile)
                },
            )
        }
    }
}

package com.example.myapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.myapp.ui.detail.DetailScreen
import com.example.myapp.ui.main.MainScreen
import com.example.myapp.ui.placeholder.PlaceholderScreen

private val topLevelRoutes: List<AppRoute> = topLevelTabs.map { it.route }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(AppRoute.Home)
    val currentRoute = backStack.lastOrNull()
    val currentTabIndexInStack = topLevelRoutes.indexOfFirst { it == currentRoute }

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentTabIndex = currentTabIndexInStack.takeIf { it >= 0 } ?: selectedTabIndex
    val showBottomBar = currentTabIndexInStack >= 0
    val showDetailBar = currentRoute is AppRoute.Detail

    fun navigateBack() {
        if (backStack.size > 1) {
            backStack.removeLastOrNull()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            when {
                showDetailBar -> {
                    CenterAlignedTopAppBar(
                        title = { Text("详情") },
                        navigationIcon = {
                            IconButton(onClick = { navigateBack() }) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }

                showBottomBar -> {
                    CenterAlignedTopAppBar(
                        title = { Text(stringResource(R.string.app_name)) },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MainBottomNavBar(
                    selectedIndex = currentTabIndex,
                    onSelectIndex = { index ->
                        val target = topLevelTabs.getOrNull(index)?.route
                        if (target != null) {
                            selectedTabIndex = index
                            if (currentRoute != target) {
                                // 先退回顶级层，再把当前顶级页替换成目标标签页，避免返回栈里堆叠顶层路由
                                while (backStack.size > 1 && topLevelRoutes.none { it == backStack.lastOrNull() }) {
                                    backStack.removeLastOrNull()
                                }
                                if (backStack.isEmpty()) {
                                    backStack.add(target)
                                } else {
                                    backStack[backStack.lastIndex] = target
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 6.dp),
                )
            }
        },
    ) { innerPadding ->
        NavDisplay(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            backStack = backStack,
            onBack = { navigateBack() },
            entryProvider = entryProvider {
                entry<AppRoute.Home> {
                    MainScreen(
                        onItemClick = { itemId ->
                            backStack.add(AppRoute.Detail(itemId))
                        },
                        bottomBarClearance = 16.dp,
                    )
                }
                entry<AppRoute.Apps> {
                    PlaceholderScreen(title = "应用")
                }
                entry<AppRoute.Rules> {
                    PlaceholderScreen(title = "规则")
                }
                entry<AppRoute.Settings> {
                    PlaceholderScreen(title = "设置")
                }
                entry<AppRoute.Detail> { route ->
                    DetailScreen(itemId = route.itemId)
                }
            },
        )
    }
}

package com.example.myapp

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.myapp.R
import com.example.myapp.ui.main.MainScreen

private val topLevelTabs: List<AppRoute> =
    listOf(
        AppRoute.Home,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainNavigation(modifier: Modifier = Modifier) {
    val backStack = rememberNavBackStack(AppRoute.Home)
    val currentRoute = backStack.lastOrNull()

    var selectedTabIndex by rememberSaveable { mutableIntStateOf(0) }
    val currentTabIndex = topLevelTabs.indexOf(currentRoute).takeIf { it >= 0 } ?: selectedTabIndex
    val showBottomBar = currentRoute in topLevelTabs

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            if (showBottomBar) {
                CenterAlignedTopAppBar(
                    title = { Text(stringResource(R.string.app_name)) },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.primary,
                        ),
                )
            }
        },
        bottomBar = {
            if (showBottomBar) {
                MainBottomNavBar(
                    selectedIndex = currentTabIndex,
                    onSelectIndex = { index ->
                        val target = topLevelTabs[index]
                        selectedTabIndex = index
                        if (currentRoute != target) {
                            // 切换到目标顶级 Tab（若当前在子页，先弹出子页回到顶级层级）
                            while (backStack.size > 1 && backStack.lastOrNull() !in topLevelTabs) {
                                backStack.removeLastOrNull()
                            }
                            if (backStack.isEmpty()) {
                                backStack.add(target)
                            } else {
                                backStack[backStack.lastIndex] = target
                            }
                        }
                    },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { innerPadding ->
        NavDisplay(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            backStack = backStack,
            onBack = {
                if (backStack.size > 1) {
                    backStack.removeLastOrNull()
                }
            },
            entryProvider =
                entryProvider {
                    entry<AppRoute.Home> {
                        MainScreen(
                            onItemClick = { itemId ->
                                backStack.add(AppRoute.Home(itemId = itemId))
                            },
                        )
                    }
                },
        )
    }
}

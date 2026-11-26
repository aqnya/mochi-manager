
package com.example.myapp.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey

import com.example.myapp.R
import com.example.myapp.data.DefaultDataRepository
import com.example.myapp.ui.theme.MyAppTheme

import kotlinx.collections.immutable.toImmutableList

import ui.feice.mochi.basic.MochiTabBar
import ui.feice.mochi.basic.MochiTabBarDefaults
import ui.feice.mochi.basic.MochiTabItem
import ui.feice.mochi.basic.Text as MochiText
import ui.feice.mochi.basic.rememberMochiTabBarScrollBehavior
import ui.feice.mochi.theme.MochiTheme

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel {
        MainScreenViewModel(DefaultDataRepository())
    },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        MainScreenUiState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }

        is MainScreenUiState.Success -> {
            MainScreen(
                data = state.data,
                modifier = modifier
            )
        }

        is MainScreenUiState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Error loading data: ${state.throwable.message}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MainScreen(
    data: List<String>,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable {
        mutableIntStateOf(0)
    }

    val scrollBehavior = rememberMochiTabBarScrollBehavior()

    val tabs = remember {
        listOf(
            MochiTabItem(
                "首页",
                selectedIcon = {
                    MochiText("⌂", style = MochiTheme.textStyles.titleLarge)
                }
            ) {
                MochiText("⌂", style = MochiTheme.textStyles.titleLarge)
            },
            MochiTabItem(
                "应用",
                selectedIcon = {
                    MochiText("▦", style = MochiTheme.textStyles.titleLarge)
                }
            ) {
                MochiText("▦", style = MochiTheme.textStyles.titleLarge)
            },
            MochiTabItem(
                "规则",
                selectedIcon = {
                    MochiText("⚙", style = MochiTheme.textStyles.titleLarge)
                }
            ) {
                MochiText("⚙", style = MochiTheme.textStyles.titleLarge)
            },
            MochiTabItem(
                "设置",
                selectedIcon = {
                    MochiText("☰", style = MochiTheme.textStyles.titleLarge)
                }
            ) {
                MochiText("☰", style = MochiTheme.textStyles.titleLarge)
            }
        ).toImmutableList()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(stringResource(R.string.app_name))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary
                )
            )
        },
        bottomBar = {
            MochiTabBar(
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                items = tabs,
                modifier = Modifier.padding(
                    horizontal = 16.dp,
                    vertical = 8.dp
                ),
                scrollBehavior = scrollBehavior,
                behavior = MochiTabBarDefaults.behavior(),
                windowInsets = WindowInsets(0)
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            when (selectedTab) {
                0 -> {
                    data.forEach { Greeting(it) }
                }

                1 -> {
                    Text(
                        "应用",
                        modifier = Modifier.padding(16.dp)
                    )
                }

                2 -> {
                    Text(
                        "规则",
                        modifier = Modifier.padding(16.dp)
                    )
                }

                3 -> {
                    Text(
                        "设置",
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun Greeting(
    name: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = "Hello $name !",
        modifier = modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MyAppTheme {
        MainScreen(listOf("Android"))
    }
}

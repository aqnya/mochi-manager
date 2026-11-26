package com.example.myapp.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapp.data.DefaultDataRepository
import com.example.myapp.ui.theme.MyAppTheme

@Composable
fun MainScreen(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomBarClearance: Dp = 88.dp,
    viewModel: MainScreenViewModel =
        viewModel {
            MainScreenViewModel(DefaultDataRepository())
        },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        MainScreenUiState.Loading -> {
            LoadingState(modifier = modifier)
        }

        is MainScreenUiState.Success -> {
            MainContent(
                data = state.data,
                onItemClick = onItemClick,
                bottomBarClearance = bottomBarClearance,
                modifier = modifier,
            )
        }

        is MainScreenUiState.Error -> {
            ErrorState(
                message = state.throwable.message ?: "未知错误",
                onRetry = viewModel::retry,
                modifier = modifier,
            )
        }
    }
}

@Composable
internal fun MainContent(
    data: List<String>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    bottomBarClearance: Dp = 88.dp,
) {
    if (data.isEmpty()) {
        EmptyState(modifier = modifier)
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding =
            PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 12.dp,
                bottom = bottomBarClearance,
            ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(
            items = data,
            key = { it },
        ) { item ->
            GreetingCard(
                name = item,
                onClick = { onItemClick(item) },
            )
        }
    }
}

@Composable
private fun GreetingCard(
    name: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Hello $name !",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "点击查看详情",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "暂无数据",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "加载失败：$message",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
        )
        Button(onClick = onRetry) {
            Text("重试")
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainScreenPreview() {
    MyAppTheme {
        MainContent(
            data = listOf("Android", "Compose", "Navigation 3"),
            onItemClick = {},
        )
    }
}

package com.example.myapp.ui.main

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.myapp.data.DefaultDataRepository
import com.example.myapp.ui.theme.MyAppTheme

@Composable
fun MainScreen(
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel =
        viewModel {
            MainScreenViewModel(DefaultDataRepository())
        },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    when (val state = uiState) {
        MainScreenUiState.Loading -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        is MainScreenUiState.Success -> {
            MainContent(
                data = state.data,
                onItemClick = onItemClick,
                modifier = modifier,
            )
        }

        is MainScreenUiState.Error -> {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text("Error loading data: ${state.throwable.message}")
            }
        }
    }
}

@Composable
internal fun MainContent(
    data: List<String>,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        data.forEach { item ->
            Greeting(
                name = item,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(item) },
            )
        }
    }
}

@Composable
fun Greeting(
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Hello $name !",
        modifier = modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun MainContentPreview() {
    MyAppTheme {
        MainContent(
            data = listOf("Android"),
            onItemClick = {},
        )
    }
}

package com.example.myapp.ui.main

import com.example.myapp.data.DataRepository
import junit.framework.TestCase.assertEquals
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainScreenViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun uiState_initiallyLoading() =
        runTest(dispatcher) {
            val viewModel = MainScreenViewModel(FakeMyModelRepository())
            assertEquals(MainScreenUiState.Loading, viewModel.uiState.value)
        }

    @Test
    fun uiState_emitsSuccess() =
        runTest(dispatcher) {
            val viewModel = MainScreenViewModel(FakeMyModelRepository())
            val state = viewModel.uiState.first { it is MainScreenUiState.Success }
            assertEquals(listOf("Sample"), (state as MainScreenUiState.Success).data)
        }
}

private class FakeMyModelRepository : DataRepository {
    override val data: Flow<List<String>> = flow { emit(listOf("Sample")) }
}

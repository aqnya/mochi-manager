package com.example.myapp.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapp.data.DataRepository
import com.example.myapp.ui.main.MainScreenUiState.Success
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

class MainScreenViewModel(
    private val dataRepository: DataRepository,
) : ViewModel() {
    private val retryTrigger = MutableStateFlow(0)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<MainScreenUiState> =
        retryTrigger
            .flatMapLatest {
                dataRepository.data
                    .map<List<String>, MainScreenUiState>(::Success)
                    .onStart { emit(MainScreenUiState.Loading) }
                    .catch { emit(MainScreenUiState.Error(it)) }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = MainScreenUiState.Loading,
            )

    fun retry() {
        retryTrigger.update { it + 1 }
    }
}

sealed interface MainScreenUiState {
    data object Loading : MainScreenUiState

    data class Error(
        val throwable: Throwable,
    ) : MainScreenUiState

    data class Success(
        val data: List<String>,
    ) : MainScreenUiState
}

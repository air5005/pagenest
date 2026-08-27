package com.wxn.reader.presentation.mainReader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ReaderIndexUiState {
    data object Idle : ReaderIndexUiState

    data class Building(
        val completed: Int,
        val total: Int?,
    ) : ReaderIndexUiState

    data object Failed : ReaderIndexUiState
}

class ReaderIndexProgress {
    private val _state = MutableStateFlow<ReaderIndexUiState>(ReaderIndexUiState.Idle)
    val state: StateFlow<ReaderIndexUiState> = _state.asStateFlow()

    fun start() {
        _state.value = ReaderIndexUiState.Building(completed = 0, total = null)
    }

    fun update(completed: Int, total: Int) {
        val safeTotal = total.coerceAtLeast(0)
        _state.value = ReaderIndexUiState.Building(
            completed = completed.coerceIn(0, safeTotal),
            total = safeTotal,
        )
    }

    fun complete() {
        _state.value = ReaderIndexUiState.Idle
    }

    fun fail() {
        _state.value = ReaderIndexUiState.Failed
    }

    fun dismissFailure() {
        if (_state.value == ReaderIndexUiState.Failed) {
            _state.value = ReaderIndexUiState.Idle
        }
    }
}

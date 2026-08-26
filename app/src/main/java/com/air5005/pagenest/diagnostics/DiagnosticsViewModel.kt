package com.air5005.pagenest.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wxn.base.diagnostics.DiagnosticLogEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class DiagnosticsFilter {
    ALL,
    RUNNING,
    WARNING,
    ERROR,
}

enum class DiagnosticsFailure {
    READ,
    CLEAR,
}

data class DiagnosticsUiState(
    val entries: List<DiagnosticLogEntry> = emptyList(),
    val filter: DiagnosticsFilter = DiagnosticsFilter.ALL,
    val totalBytes: Long = 0L,
    val isLoading: Boolean = true,
    val failure: DiagnosticsFailure? = null,
) {
    val visibleEntries: List<DiagnosticLogEntry>
        get() = entries.filter { entry ->
            filter == DiagnosticsFilter.ALL || entry.level.name == filter.name
        }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(DiagnosticsUiState())
    val state: StateFlow<DiagnosticsUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun selectFilter(filter: DiagnosticsFilter) {
        _state.update { it.copy(filter = filter) }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, failure = null) }
            try {
                publish(repository.readRecent())
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false, failure = DiagnosticsFailure.READ) }
            }
        }
    }

    fun clear() {
        viewModelScope.launch {
            try {
                repository.clear()
                publish(repository.readRecent())
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false, failure = DiagnosticsFailure.CLEAR) }
            }
        }
    }

    private fun publish(snapshot: DiagnosticsSnapshot) {
        _state.update {
            it.copy(
                entries = snapshot.entries.sortedWith(DiagnosticLogEntry.NEWEST_FIRST),
                totalBytes = snapshot.totalBytes,
                isLoading = false,
                failure = null,
            )
        }
    }
}

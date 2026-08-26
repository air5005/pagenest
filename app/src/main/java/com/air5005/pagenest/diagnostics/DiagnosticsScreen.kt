package com.air5005.pagenest.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wxn.base.diagnostics.DiagnosticLevel
import com.wxn.base.diagnostics.DiagnosticLogEntry
import com.wxn.reader.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DiagnosticsRoute(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiagnosticsScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::refresh,
        onFilterSelected = viewModel::selectFilter,
        onClear = viewModel::clear,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    state: DiagnosticsUiState,
    onBack: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onFilterSelected: (DiagnosticsFilter) -> Unit = {},
    onClear: () -> Unit = {},
) {
    var confirmClear by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.diagnostics_back),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Rounded.Refresh, stringResource(R.string.diagnostics_refresh))
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, stringResource(R.string.diagnostics_clear))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.diagnostics_retention_summary),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.diagnostics_used_storage, formatBytes(state.totalBytes)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DiagnosticsFilter.entries) { filter ->
                        FilterChip(
                            selected = state.filter == filter,
                            onClick = { onFilterSelected(filter) },
                            label = { Text(filter.label()) },
                        )
                    }
                }
            }
            state.failure?.let { failure ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Text(
                            text = stringResource(failure.messageResource()),
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
            when {
                state.isLoading -> item {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) { CircularProgressIndicator() }
                }
                state.visibleEntries.isEmpty() -> item {
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> items(
                    items = state.visibleEntries,
                    key = { "${it.timestampEpochMillis}-${it.level}-${it.category}-${it.message.hashCode()}" },
                ) { entry -> DiagnosticEntryCard(entry) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.diagnostics_clear_confirm_title)) },
            text = { Text(stringResource(R.string.diagnostics_clear_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    onClear()
                }) { Text(stringResource(R.string.diagnostics_clear_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) {
                    Text(stringResource(R.string.diagnostics_cancel))
                }
            },
        )
    }
}

@Composable
private fun DiagnosticEntryCard(entry: DiagnosticLogEntry) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 1.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(entry.level.label(), color = entry.level.color(), fontWeight = FontWeight.Bold)
                Text(entry.category, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            }
            Text(formatTimestamp(entry.timestampEpochMillis), style = MaterialTheme.typography.bodySmall)
            Text(entry.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun DiagnosticsFilter.label(): String = when (this) {
    DiagnosticsFilter.ALL -> stringResource(R.string.diagnostics_filter_all)
    DiagnosticsFilter.RUNNING -> stringResource(R.string.diagnostics_filter_running)
    DiagnosticsFilter.WARNING -> stringResource(R.string.diagnostics_filter_warning)
    DiagnosticsFilter.ERROR -> stringResource(R.string.diagnostics_filter_error)
}

@Composable
private fun DiagnosticLevel.label(): String = when (this) {
    DiagnosticLevel.RUNNING -> stringResource(R.string.diagnostics_filter_running)
    DiagnosticLevel.WARNING -> stringResource(R.string.diagnostics_filter_warning)
    DiagnosticLevel.ERROR -> stringResource(R.string.diagnostics_filter_error)
}

@Composable
private fun DiagnosticLevel.color(): Color = when (this) {
    DiagnosticLevel.RUNNING -> MaterialTheme.colorScheme.primary
    DiagnosticLevel.WARNING -> MaterialTheme.colorScheme.tertiary
    DiagnosticLevel.ERROR -> MaterialTheme.colorScheme.error
}

private fun DiagnosticsFailure.messageResource(): Int = when (this) {
    DiagnosticsFailure.READ -> R.string.diagnostics_read_failed
    DiagnosticsFailure.CLEAR -> R.string.diagnostics_clear_failed
}

private fun formatTimestamp(timestampEpochMillis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestampEpochMillis))

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0)
    else -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
}

package com.wxn.reader.presentation.mainReader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.wxn.reader.R

@Composable
fun ReaderIndexProgressNotice(
    state: ReaderIndexUiState,
    topPadding: Dp = 8.dp,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = state != ReaderIndexUiState.Idle,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = topPadding, start = 16.dp, end = 16.dp)
                .widthIn(max = 360.dp)
                .testTag("reader_index_progress_notice"),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                when (state) {
                    is ReaderIndexUiState.Building -> {
                        val total = state.total
                        Text(
                            text = if (total == null) {
                                stringResource(R.string.reader_index_building)
                            } else {
                                stringResource(R.string.reader_index_building_progress, state.completed, total)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (total == null || total == 0) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                        } else {
                            LinearProgressIndicator(
                                progress = { state.completed.toFloat() / total.toFloat() },
                                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            )
                        }
                    }

                    ReaderIndexUiState.Failed -> Text(
                        text = stringResource(R.string.reader_index_failed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )

                    ReaderIndexUiState.Idle -> Unit
                }
            }
        }
    }
}

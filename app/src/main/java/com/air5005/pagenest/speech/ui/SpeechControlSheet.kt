package com.air5005.pagenest.speech.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.wxn.reader.R

data class SpeechControlUiState(
    val playback: SpeechPlaybackState,
    val mode: SpeechMode,
    val activeEngineLabel: String,
    val rate: Float,
    val pitch: Float,
    val voiceId: String?,
    val sleepTimerMinutes: Int?,
)

@Composable
fun SpeechControlSheet(
    state: SpeechControlUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRateChange: (Float) -> Unit,
    onPitchChange: (Float) -> Unit,
    onTimerChange: (Int?) -> Unit,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth().padding(12.dp)) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(state.activeEngineLabel, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, stringResource(R.string.reader_speech_collapse))
                }
            }
            (state.playback as? SpeechPlaybackState.Error)?.let { failure ->
                Text(SpeechControlPolicy.messageFor(failure.error), color = MaterialTheme.colorScheme.error)
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = onPrevious) { Icon(Icons.Default.SkipPrevious, stringResource(R.string.speech_previous)) }
                if (state.playback is SpeechPlaybackState.Playing || state.playback is SpeechPlaybackState.Preparing) {
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, stringResource(R.string.speech_pause)) }
                } else {
                    IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, stringResource(R.string.speech_play)) }
                }
                IconButton(onClick = onNext) { Icon(Icons.Default.SkipNext, stringResource(R.string.speech_next)) }
                IconButton(onClick = onStop) { Icon(Icons.Default.Stop, stringResource(R.string.speech_stop)) }
            }
            Text(stringResource(R.string.speech_rate, state.rate))
            Slider(value = state.rate, onValueChange = onRateChange, valueRange = 0.25f..2f)
            Text(stringResource(R.string.speech_pitch, state.pitch))
            Slider(value = state.pitch, onValueChange = onPitchChange, valueRange = 0.25f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpeechControlPolicy.sleepTimerChoices.forEach { minutes ->
                    TextButton(onClick = { onTimerChange(minutes) }) {
                        Text(if (minutes == null) stringResource(R.string.speech_timer_off) else stringResource(R.string.speech_timer_minutes, minutes))
                    }
                }
            }
        }
    }
}

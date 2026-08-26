package com.wxn.reader.presentation.mainReader.chrome

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.ChromeReaderMode
import androidx.compose.material.icons.automirrored.sharp.ArrowBack
import androidx.compose.material.icons.automirrored.sharp.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.ui.SpeechControlUiState
import com.wxn.reader.R

@Composable
fun ReaderChrome(
    state: ReaderChromeState,
    bookTitle: String,
    chapterTitle: String,
    progression: Double,
    isBookmarked: Boolean,
    speech: SpeechControlUiState,
    progressExpanded: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onMore: () -> Unit,
    onChapters: () -> Unit,
    onProgressToggle: () -> Unit,
    onProgressChange: (Double) -> Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onSpeech: () -> Unit,
    onDisplay: () -> Unit,
    onPlaySpeech: () -> Unit,
    onPauseSpeech: () -> Unit,
    onPreviousSpeech: () -> Unit,
    onNextSpeech: () -> Unit,
    onStopSpeech: () -> Unit,
    onExpandSpeech: () -> Unit,
    onInteraction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val invoke: (() -> Unit) -> Unit = { action ->
        onInteraction()
        action()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderTopChrome(
                bookTitle = bookTitle,
                chapterTitle = chapterTitle,
                isBookmarked = isBookmarked,
                onBack = { invoke(onBack) },
                onBookmark = { invoke(onBookmark) },
                onMore = { invoke(onMore) },
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible && progressExpanded,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = if (state.speechMiniPlayerVisible) 196.dp else 116.dp),
        ) {
            ReaderProgressPanel(
                progression = progression,
                onProgressChange = {
                    onInteraction()
                    onProgressChange(it)
                },
                onPreviousPage = { invoke(onPreviousPage) },
                onNextPage = { invoke(onNextPage) },
            )
        }

        AnimatedVisibility(
            visible = state.speechMiniPlayerVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .padding(bottom = if (state.controlsVisible) 116.dp else 12.dp),
        ) {
            SpeechMiniPlayer(
                state = speech,
                onPlay = { invoke(onPlaySpeech) },
                onPause = { invoke(onPauseSpeech) },
                onPrevious = { invoke(onPreviousSpeech) },
                onNext = { invoke(onNextSpeech) },
                onStop = { invoke(onStopSpeech) },
                onExpand = { invoke(onExpandSpeech) },
            )
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            ReaderActionDock(
                onChapters = { invoke(onChapters) },
                onProgress = { invoke(onProgressToggle) },
                onSpeech = { invoke(onSpeech) },
                onDisplay = { invoke(onDisplay) },
            )
        }
    }
}

@Composable
private fun ReaderTopChrome(
    bookTitle: String,
    chapterTitle: String,
    isBookmarked: Boolean,
    onBack: () -> Unit,
    onBookmark: () -> Unit,
    onMore: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("reader_top_chrome"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        tonalElevation = 5.dp,
        shadowElevation = 7.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.reader_back))
            }
            Column(modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                Text(
                    text = bookTitle,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = chapterTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onBookmark) {
                Icon(
                    imageVector = if (isBookmarked) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                    contentDescription = stringResource(R.string.reader_bookmark),
                    tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(onClick = onMore) {
                Icon(Icons.Filled.MoreVert, stringResource(R.string.reader_more))
            }
        }
    }
}

@Composable
private fun ReaderActionDock(
    onChapters: () -> Unit,
    onProgress: () -> Unit,
    onSpeech: () -> Unit,
    onDisplay: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("reader_action_dock"),
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 10.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceAround,
        ) {
            ReaderAction(
                icon = Icons.AutoMirrored.Filled.List,
                label = stringResource(R.string.reader_chapters),
                onClick = onChapters,
            )
            ReaderAction(
                icon = Icons.AutoMirrored.Outlined.ChromeReaderMode,
                label = stringResource(R.string.reader_progress),
                onClick = onProgress,
            )
            ReaderAction(
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                label = stringResource(R.string.reader_listen),
                onClick = onSpeech,
            )
            ReaderAction(
                icon = Icons.Filled.FormatSize,
                label = stringResource(R.string.reader_display),
                onClick = onDisplay,
            )
        }
    }
}

@Composable
private fun ReaderAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = label, tint = MaterialTheme.colorScheme.primary)
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun ReaderProgressPanel(
    progression: Double,
    onProgressChange: (Double) -> Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
) {
    val safeProgress = progression.coerceIn(0.0, 1.0)
    var scrubState by remember { mutableStateOf(ReaderProgressScrubState(safeProgress)) }
    LaunchedEffect(safeProgress) {
        scrubState = scrubState.synchronize(safeProgress)
    }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("reader_progress_panel"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 5.dp,
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPreviousPage) {
                    Icon(Icons.AutoMirrored.Sharp.ArrowBack, stringResource(R.string.reader_previous_page))
                }
                Slider(
                    value = scrubState.previewProgress.toFloat(),
                    onValueChange = { scrubState = scrubState.preview(it.toDouble()) },
                    onValueChangeFinished = {
                        val target = scrubState.previewProgress
                        scrubState = if (onProgressChange(target)) {
                            scrubState.finish()
                        } else {
                            scrubState.cancel(safeProgress)
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onNextPage) {
                    Icon(Icons.AutoMirrored.Sharp.ArrowForward, stringResource(R.string.reader_next_page))
                }
            }
            Text(
                text = stringResource(R.string.reader_current_progress, scrubState.previewProgress * 100),
                modifier = Modifier.align(Alignment.CenterHorizontally),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpeechMiniPlayer(
    state: SpeechControlUiState,
    onPlay: () -> Unit,
    onPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
    onExpand: () -> Unit,
) {
    Surface(
        onClick = onExpand,
        modifier = Modifier.fillMaxWidth().testTag("speech_mini_player"),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.97f),
        tonalElevation = 6.dp,
        shadowElevation = 9.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.reader_speech_now_playing),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = state.activeEngineLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPrevious, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipPrevious, stringResource(R.string.reader_speech_previous))
            }
            if (state.playback is SpeechPlaybackState.Playing || state.playback is SpeechPlaybackState.Preparing) {
                IconButton(onClick = onPause, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.Pause, stringResource(R.string.reader_speech_pause))
                }
            } else {
                IconButton(onClick = onPlay, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Filled.PlayArrow, stringResource(R.string.reader_speech_play))
                }
            }
            IconButton(onClick = onNext, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.SkipNext, stringResource(R.string.reader_speech_next))
            }
            IconButton(onClick = onStop, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Stop, stringResource(R.string.reader_speech_stop))
            }
        }
    }
}

package com.wxn.reader.presentation.mainReader.chrome

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.ui.SpeechControlUiState
import com.wxn.base.bean.Locator
import com.wxn.reader.ui.theme.PageNestTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ReaderChromeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun hiddenChromeDoesNotExposeControls() {
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                ChromeUnderTest(state = ReaderChromeState())
            }
        }

        compose.onAllNodesWithTag("reader_top_chrome").assertCountEquals(0)
        compose.onAllNodesWithTag("reader_action_dock").assertCountEquals(0)
    }

    @Test
    fun visibleChromeShowsFourPrimaryActions() {
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                ChromeUnderTest(state = ReaderChromeState(controlsVisible = true))
            }
        }

        compose.onNodeWithTag("reader_top_chrome").assertIsDisplayed()
        compose.onNodeWithTag("reader_action_dock").assertIsDisplayed()
        listOf("目录", "进度", "听书", "显示").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun activeSpeechShowsMiniPlayerAndInvokesPause() {
        var paused = false
        compose.setContent {
            PageNestTheme(darkTheme = true) {
                ChromeUnderTest(
                    state = ReaderChromeState(speechSessionActive = true),
                    playback = SpeechPlaybackState.Playing(segment(), engineId = "system"),
                    onPauseSpeech = { paused = true },
                )
            }
        }

        compose.onNodeWithTag("speech_mini_player").assertIsDisplayed()
        compose.onNodeWithContentDescription("暂停朗读").performClick()

        assertTrue(paused)
    }

    @Test
    fun progressActionInvokesToggleCallback() {
        var toggled = false
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                ChromeUnderTest(
                    state = ReaderChromeState(controlsVisible = true),
                    onProgressToggle = { toggled = true },
                )
            }
        }

        compose.onNodeWithContentDescription("进度").performClick()

        assertTrue(toggled)
    }

    @androidx.compose.runtime.Composable
    private fun ChromeUnderTest(
        state: ReaderChromeState,
        playback: SpeechPlaybackState = SpeechPlaybackState.Idle,
        onPauseSpeech: () -> Unit = {},
        onProgressToggle: () -> Unit = {},
    ) {
        ReaderChrome(
            state = state,
            bookTitle = "围城",
            chapterTitle = "第六章",
            progression = 0.36,
            isBookmarked = false,
            speech = SpeechControlUiState(
                playback = playback,
                mode = SpeechMode.OFFLINE,
                activeEngineLabel = "系统离线",
                rate = 1f,
                pitch = 1f,
                voiceId = null,
                sleepTimerMinutes = null,
            ),
            progressExpanded = false,
            onBack = {},
            onBookmark = {},
            onMore = {},
            onChapters = {},
            onProgressToggle = onProgressToggle,
            onProgressChange = {},
            onPreviousPage = {},
            onNextPage = {},
            onSpeech = {},
            onDisplay = {},
            onPlaySpeech = {},
            onPauseSpeech = onPauseSpeech,
            onPreviousSpeech = {},
            onNextSpeech = {},
            onStopSpeech = {},
            onExpandSpeech = {},
            onInteraction = {},
        )
    }

    private fun segment() = SpeechSegment(
        id = "segment",
        position = SpeechPosition(
            bookId = 1,
            chapterIndex = 2,
            pageIndex = 3,
            paragraphIndex = 4,
            textOffset = 5,
        ),
        partIndex = 0,
        text = "正文",
        locator = Locator(text = "", progression = 0.36),
    )
}

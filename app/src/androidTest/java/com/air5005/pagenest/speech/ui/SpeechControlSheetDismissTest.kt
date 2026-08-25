package com.air5005.pagenest.speech.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.wxn.reader.ui.theme.PageNestTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SpeechControlSheetDismissTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closeButtonDismissesExpandedSpeechPanel() {
        var dismissed = false
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                SpeechControlSheet(
                    state = SpeechControlUiState(
                        playback = SpeechPlaybackState.Idle,
                        mode = SpeechMode.OFFLINE,
                        activeEngineLabel = "系统离线",
                        rate = 1f,
                        pitch = 1f,
                        voiceId = null,
                        sleepTimerMinutes = null,
                    ),
                    onPlay = {},
                    onPause = {},
                    onStop = {},
                    onPrevious = {},
                    onNext = {},
                    onRateChange = {},
                    onPitchChange = {},
                    onTimerChange = {},
                    onDismiss = { dismissed = true },
                )
            }
        }

        compose.onNodeWithContentDescription("收起语音设置").performClick()

        assertTrue(dismissed)
    }
}

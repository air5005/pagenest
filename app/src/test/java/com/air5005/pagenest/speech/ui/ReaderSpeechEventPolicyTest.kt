package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderSpeechEventPolicyTest {
    @Test
    fun successfulFallbackUsesReaderRouteStatusInsteadOfAnOverlappingMessage() {
        assertFalse(
            ReaderSpeechEventPolicy.shouldPresent(
                SpeechUiEvent.ShowFallbackMessage("Azure Speech Key 无效"),
            ),
        )
    }

    @Test
    fun directSpeechFailuresRemainVisibleToTheReader() {
        assertTrue(
            ReaderSpeechEventPolicy.shouldPresent(
                SpeechUiEvent.ShowMessage("朗读失败"),
            ),
        )
    }

    @Test
    fun completedOfflineFailureReplacesTheFallbackRouteLabel() {
        assertEquals(
            "系统语音引擎不可用",
            SpeechControlPolicy.readerStatusLabel(
                playback = SpeechPlaybackState.Error(SpeechError.SystemTtsPlaybackFailed, null),
                engineLabel = "系统离线（已从在线回退）",
            ),
        )
    }
}

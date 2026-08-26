package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.settings.SpeechUiEvent
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
}

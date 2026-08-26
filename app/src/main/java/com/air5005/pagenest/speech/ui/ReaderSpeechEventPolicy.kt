package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.settings.SpeechUiEvent

object ReaderSpeechEventPolicy {
    fun shouldPresent(event: SpeechUiEvent): Boolean = event is SpeechUiEvent.ShowMessage
}

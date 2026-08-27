package com.air5005.pagenest.speech.settings

import com.air5005.pagenest.speech.model.SpeechMode

object ReaderSpeechRoutingPolicy {
    fun effectiveMode(
        requested: SpeechMode,
        onlineConsentGranted: Boolean,
        azureConfigured: Boolean,
    ): SpeechMode = when {
        requested != SpeechMode.AUTO -> requested
        onlineConsentGranted && azureConfigured -> SpeechMode.AUTO
        else -> SpeechMode.OFFLINE
    }
}

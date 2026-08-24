package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.settings.SpeechUiEvent

sealed interface SpeechSettingsEventPresentation {
    data object OnlineConsentDialog : SpeechSettingsEventPresentation
    data class Snackbar(val message: String) : SpeechSettingsEventPresentation
}

object SpeechSettingsEventPolicy {
    fun presentationFor(event: SpeechUiEvent): SpeechSettingsEventPresentation = when (event) {
        SpeechUiEvent.RequestOnlineConsent -> SpeechSettingsEventPresentation.OnlineConsentDialog
        is SpeechUiEvent.ShowMessage -> SpeechSettingsEventPresentation.Snackbar(event.message)
        is SpeechUiEvent.ShowFallbackMessage -> SpeechSettingsEventPresentation.Snackbar(event.message)
    }
}

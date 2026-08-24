package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.air5005.pagenest.speech.settings.SpeechSettingsState

sealed interface SpeechSettingsEventPresentation {
    data object OnlineConsentDialog : SpeechSettingsEventPresentation
    data class Snackbar(val message: String) : SpeechSettingsEventPresentation
}

object SpeechSettingsEventPolicy {
    fun shouldShowOnlineConsent(state: SpeechSettingsState): Boolean = state.onlineConsentPending

    fun presentationFor(event: SpeechUiEvent): SpeechSettingsEventPresentation = when (event) {
        SpeechUiEvent.RequestOnlineConsent -> SpeechSettingsEventPresentation.OnlineConsentDialog
        is SpeechUiEvent.ShowMessage -> SpeechSettingsEventPresentation.Snackbar(event.message)
        is SpeechUiEvent.ShowFallbackMessage -> SpeechSettingsEventPresentation.Snackbar(event.message)
    }
}

package com.air5005.pagenest.speech.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.cloud.AzureSpeechService
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.security.AzureRegionValidator
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import com.air5005.pagenest.speech.ui.SpeechControlPolicy
import com.air5005.pagenest.speech.engine.SpeechRouteIndicator
import com.air5005.pagenest.speech.playback.AppSpeechController
import com.air5005.pagenest.speech.playback.SpeechControllerSnapshot
import com.air5005.pagenest.speech.engine.SpeechVoice
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

interface SpeechPlaybackActions {
    val playbackSnapshot: StateFlow<SpeechControllerSnapshot>
        get() = AppSpeechController.snapshot
    val routeIndicator: StateFlow<SpeechRouteIndicator>
        get() = EMPTY_ROUTE_INDICATOR
    val fallbackErrors: Flow<SpeechError>
        get() = emptyFlow()
    fun start()
    fun pause()
    fun stop()
    fun next()
    fun previous()
    fun setSleepTimer(minutes: Int?)
    fun applyPreferences() = Unit

    companion object {
        private val EMPTY_ROUTE_INDICATOR = MutableStateFlow(SpeechRouteIndicator("system", false))
    }
}

data class SpeechSettingsState(
    val preferences: SpeechPreferences = SpeechPreferences(),
    val keyConfigured: Boolean = false,
    val region: String = "",
    val keyDraft: String = "",
    val availableVoices: List<SpeechVoice> = emptyList(),
)

sealed interface SpeechUiEvent {
    data object RequestOnlineConsent : SpeechUiEvent
    data class ShowMessage(val message: String) : SpeechUiEvent
    data class ShowFallbackMessage(val message: String) : SpeechUiEvent
}

@HiltViewModel
class SpeechSettingsViewModel @Inject constructor(
    private val preferencesRepository: SpeechPreferencesRepository,
    private val credentialStore: SpeechCredentialStore,
    private val speechAudioCache: SpeechAudioCache,
    private val azureSpeechService: AzureSpeechService,
    private val playbackActions: SpeechPlaybackActions,
) : ViewModel() {
    val playbackSnapshot: StateFlow<SpeechControllerSnapshot> = playbackActions.playbackSnapshot
    val routeIndicator: StateFlow<SpeechRouteIndicator> = playbackActions.routeIndicator
    private val _state = MutableStateFlow(SpeechSettingsState())
    val state: StateFlow<SpeechSettingsState> = _state.asStateFlow()
    private val eventChannel = Channel<SpeechUiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    init {
        viewModelScope.launch {
            preferencesRepository.preferences.collect { preferences ->
                _state.value = _state.value.copy(preferences = preferences)
            }
        }
        viewModelScope.launch {
            val credentials = credentialStore.loadAzure()
            _state.value = _state.value.copy(
                keyConfigured = credentials != null,
                region = credentials?.region.orEmpty(),
                keyDraft = "",
            )
        }
        viewModelScope.launch {
            playbackActions.fallbackErrors.collect { error ->
                eventChannel.send(SpeechUiEvent.ShowFallbackMessage(SpeechControlPolicy.messageFor(error)))
            }
        }
    }

    fun start() {
        val preferences = state.value.preferences
        if (preferences.mode != SpeechMode.OFFLINE && !preferences.onlineConsentGranted) {
            eventChannel.trySend(SpeechUiEvent.RequestOnlineConsent)
        } else {
            playbackActions.start()
        }
    }

    fun confirmOnlineConsent() {
        viewModelScope.launch {
            preferencesRepository.update { it.copy(onlineConsentGranted = true) }
            playbackActions.start()
        }
    }

    fun selectMode(mode: SpeechMode) {
        playbackActions.stop()
        viewModelScope.launch { preferencesRepository.update { it.copy(mode = mode) } }
    }

    fun setRate(value: Float) = updatePreferencesAndPlayback { it.copy(rate = value.coerceIn(0.25f, 2f)) }
    fun setPitch(value: Float) = updatePreferencesAndPlayback { it.copy(pitch = value.coerceIn(0.25f, 2f)) }
    fun setVoice(voiceId: String?) = updatePreferencesAndPlayback { it.copy(voiceId = voiceId) }
    fun setLocale(localeTag: String) = updatePreferencesAndPlayback { it.copy(localeTag = localeTag) }

    fun pause() = playbackActions.pause()
    fun stop() = playbackActions.stop()
    fun next() = playbackActions.next()
    fun previous() = playbackActions.previous()
    fun setSleepTimer(minutes: Int?) = playbackActions.setSleepTimer(minutes)

    fun saveAzure(key: String, region: String) {
        val normalizedRegion = region.trim().lowercase()
        if (key.isBlank() || !AzureRegionValidator.isValid(normalizedRegion)) {
            eventChannel.trySend(SpeechUiEvent.ShowMessage(SpeechControlPolicy.messageFor(com.air5005.pagenest.speech.model.SpeechError.InvalidRegion)))
            return
        }
        viewModelScope.launch {
            credentialStore.saveAzure(key, normalizedRegion)
            _state.value = _state.value.copy(keyConfigured = true, region = normalizedRegion, keyDraft = "")
        }
    }

    fun deleteAzure() {
        viewModelScope.launch {
            credentialStore.clearAzure()
            speechAudioCache.clear()
            _state.value = _state.value.copy(keyConfigured = false, region = "", keyDraft = "")
        }
    }

    fun testConnection() {
        viewModelScope.launch {
            val credentials = credentialStore.loadAzure()
            if (credentials == null) {
                eventChannel.send(SpeechUiEvent.ShowMessage("请先配置 Azure Speech Key 和 Region"))
                return@launch
            }
            val result = azureSpeechService.voices(credentials)
            if (result.error == null) {
                _state.value = _state.value.copy(availableVoices = result.value.orEmpty())
            }
            val message = result.error?.let(SpeechControlPolicy::messageFor) ?: "Azure 连接成功"
            eventChannel.send(SpeechUiEvent.ShowMessage(message))
        }
    }

    private fun updatePreferences(transform: (SpeechPreferences) -> SpeechPreferences) {
        viewModelScope.launch { preferencesRepository.update(transform) }
    }

    private fun updatePreferencesAndPlayback(transform: (SpeechPreferences) -> SpeechPreferences) {
        viewModelScope.launch {
            preferencesRepository.update(transform)
            playbackActions.applyPreferences()
        }
    }
}

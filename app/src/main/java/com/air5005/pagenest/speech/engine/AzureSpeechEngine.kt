package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.cloud.AzureSpeechService
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.playback.EncodedAudioPlayer
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class AzureSpeechEngine(
    private val credentialStore: SpeechCredentialStore,
    private val client: AzureSpeechService,
    private val encodedAudioPlayer: EncodedAudioPlayer,
) : SpeechEngine {
    override val id: String = "azure"

    override suspend fun voices(localeTag: String): List<SpeechVoice> {
        val credentials = credentialStore.loadAzure() ?: return emptyList()
        val result = client.voices(credentials)
        return result.value.orEmpty().filter { voice ->
            localeTag.isBlank() || voice.localeTag.equals(localeTag, ignoreCase = true)
        }
    }

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult = try {
        val credentials = credentialStore.loadAzure()
            ?: return SpeechEngineResult.Failed(SpeechError.InvalidCredentials)
        val synthesis = client.synthesize(credentials, request)
        synthesis.error?.let { return SpeechEngineResult.Failed(it) }
        val audio = synthesis.value
            ?: return SpeechEngineResult.Failed(SpeechError.ServiceUnavailable)
        encodedAudioPlayer.playMp3(audio)
    } catch (cancelled: CancellationException) {
        withContext(NonCancellable) { encodedAudioPlayer.stop() }
        throw cancelled
    }

    override suspend fun stop() {
        encodedAudioPlayer.stop()
    }

    override fun close() {
        encodedAudioPlayer.close()
        client.close()
    }
}

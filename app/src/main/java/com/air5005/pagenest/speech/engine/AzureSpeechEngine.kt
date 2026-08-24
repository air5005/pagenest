package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.cloud.AzureSpeechService
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.playback.EncodedAudioPlayer
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class AzureSpeechEngine(
    private val credentialStore: SpeechCredentialStore,
    private val client: AzureSpeechService,
    private val encodedAudioPlayer: EncodedAudioPlayer,
) : OnlineSpeechEngine {
    override val id: String = "azure"
    private val closed = AtomicBoolean(false)

    override suspend fun voices(localeTag: String): List<SpeechVoice> {
        val credentials = credentialStore.loadAzure() ?: return emptyList()
        val result = client.voices(credentials)
        return result.value.orEmpty().filter { voice ->
            localeTag.isBlank() || voice.localeTag.equals(localeTag, ignoreCase = true)
        }
    }

    override suspend fun synthesize(request: SpeechRequest): OnlineSynthesisResult {
        val credentials = credentialStore.loadAzure()
            ?: return OnlineSynthesisResult.Failed(SpeechError.InvalidCredentials)
        val synthesis = client.synthesize(credentials, request)
        synthesis.error?.let { return OnlineSynthesisResult.Failed(it) }
        val audio = synthesis.value
            ?: return OnlineSynthesisResult.Failed(SpeechError.ServiceUnavailable)
        return OnlineSynthesisResult.Audio(audio)
    }

    override suspend fun playEncoded(audio: ByteArray): SpeechEngineResult = encodedAudioPlayer.playMp3(audio)

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult = try {
        when (val synthesis = synthesize(request)) {
            is OnlineSynthesisResult.Audio -> try {
                playEncoded(synthesis.bytes)
            } finally {
                synthesis.bytes.fill(0)
            }
            OnlineSynthesisResult.Cancelled -> SpeechEngineResult.Cancelled
            is OnlineSynthesisResult.Failed -> SpeechEngineResult.Failed(synthesis.error)
        }
    } catch (cancelled: CancellationException) {
        try {
            withContext(NonCancellable) { encodedAudioPlayer.stop() }
        } catch (_: Exception) {
            // Cancellation remains authoritative even if the playback backend cannot stop cleanly.
        }
        throw cancelled
    }

    override suspend fun stop() {
        encodedAudioPlayer.stop()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            encodedAudioPlayer.close()
        } catch (primary: Throwable) {
            try {
                client.close()
            } catch (secondary: Throwable) {
                if (primary !== secondary) primary.addSuppressed(secondary)
            }
            throw primary
        }
        client.close()
    }
}

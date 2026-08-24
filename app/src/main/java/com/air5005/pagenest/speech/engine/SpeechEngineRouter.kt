package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

interface OnlineSpeechEngine : SpeechEngine {
    suspend fun synthesize(request: SpeechRequest): OnlineSynthesisResult
    suspend fun playEncoded(audio: ByteArray): SpeechEngineResult
}

sealed interface OnlineSynthesisResult {
    data class Audio(val bytes: ByteArray) : OnlineSynthesisResult
    data object Cancelled : OnlineSynthesisResult
    data class Failed(val error: SpeechError) : OnlineSynthesisResult
}

data class RoutedSpeechResult(
    val result: SpeechEngineResult,
    val engineId: String,
    val fellBack: Boolean = false,
)

class SpeechEngineRouter(
    private val systemEngine: SpeechEngine,
    private val onlineEngine: OnlineSpeechEngine,
    private val cache: SpeechAudioCache,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun speak(request: SpeechRequest, mode: SpeechMode): RoutedSpeechResult = when (mode) {
        SpeechMode.OFFLINE -> routeOffline(request, fellBack = false)
        SpeechMode.ONLINE -> routeOnlineOnly(request)
        SpeechMode.AUTO -> routeAutomatically(request)
    }

    private suspend fun routeOffline(request: SpeechRequest, fellBack: Boolean): RoutedSpeechResult =
        RoutedSpeechResult(systemEngine.speak(request), systemEngine.id, fellBack)

    private suspend fun routeOnlineOnly(request: SpeechRequest): RoutedSpeechResult {
        cachedAudio(request)?.let { audio ->
            return RoutedSpeechResult(playAndWipe(audio), onlineEngine.id)
        }
        return when (val synthesis = onlineEngine.synthesize(request)) {
            is OnlineSynthesisResult.Audio -> RoutedSpeechResult(cachePlayAndWipe(request, synthesis.bytes), onlineEngine.id)
            OnlineSynthesisResult.Cancelled -> RoutedSpeechResult(SpeechEngineResult.Cancelled, onlineEngine.id)
            is OnlineSynthesisResult.Failed -> RoutedSpeechResult(SpeechEngineResult.Failed(synthesis.error), onlineEngine.id)
        }
    }

    private suspend fun routeAutomatically(request: SpeechRequest): RoutedSpeechResult {
        cachedAudio(request)?.let { audio ->
            val result = playAndWipe(audio)
            return if (result is SpeechEngineResult.Failed) routeOffline(request, fellBack = true)
            else RoutedSpeechResult(result, onlineEngine.id)
        }

        var retryIndex = 0
        while (true) {
            when (val synthesis = onlineEngine.synthesize(request)) {
                is OnlineSynthesisResult.Audio -> {
                    val result = cachePlayAndWipe(request, synthesis.bytes)
                    return if (result is SpeechEngineResult.Failed) routeOffline(request, fellBack = true)
                    else RoutedSpeechResult(result, onlineEngine.id)
                }
                OnlineSynthesisResult.Cancelled ->
                    return RoutedSpeechResult(SpeechEngineResult.Cancelled, onlineEngine.id)
                is OnlineSynthesisResult.Failed -> {
                    val delay = retryPolicy.delaysMillis.getOrNull(retryIndex)
                    if (synthesis.error.kind in retryPolicy.retryable && delay != null) {
                        retryIndex++
                        delayMillis(delay)
                    } else {
                        return routeOffline(request, fellBack = true)
                    }
                }
            }
        }
    }

    private suspend fun cachedAudio(request: SpeechRequest): ByteArray? = try {
        cache.get(request, nowMillis())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun cachePlayAndWipe(request: SpeechRequest, audio: ByteArray): SpeechEngineResult = try {
        try {
            cache.put(request, audio, nowMillis())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A private cache failure must not prevent immediate speech playback.
        }
        onlineEngine.playEncoded(audio)
    } finally {
        audio.fill(0)
    }

    private suspend fun playAndWipe(audio: ByteArray): SpeechEngineResult = try {
        onlineEngine.playEncoded(audio)
    } finally {
        audio.fill(0)
    }
}

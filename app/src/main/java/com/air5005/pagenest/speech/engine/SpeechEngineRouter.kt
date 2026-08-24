package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.cache.SpeechCacheScopeToken
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicLong

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
    private val requestSequence = AtomicLong()

    suspend fun speak(request: SpeechRequest, mode: SpeechMode): RoutedSpeechResult {
        val requestGeneration = requestSequence.incrementAndGet()
        val cacheScope = retainCacheScope(requestGeneration, request)
        return when (mode) {
            SpeechMode.OFFLINE -> routeOffline(request, fellBack = false)
            SpeechMode.ONLINE -> routeOnlineOnly(cacheScope, request)
            SpeechMode.AUTO -> routeAutomatically(cacheScope, request)
        }
    }

    private suspend fun routeOffline(request: SpeechRequest, fellBack: Boolean): RoutedSpeechResult =
        RoutedSpeechResult(systemEngine.speak(request), systemEngine.id, fellBack)

    private suspend fun routeOnlineOnly(
        cacheScope: SpeechCacheScopeToken?,
        request: SpeechRequest,
    ): RoutedSpeechResult {
        cachedAudio(cacheScope, request)?.let { audio ->
            val result = playAndWipe(audio)
            if (result !is SpeechEngineResult.Failed) {
                return RoutedSpeechResult(result, onlineEngine.id)
            }
            removeCached(cacheScope, request)
        }
        return synthesizeOnlineOnly(cacheScope, request)
    }

    private suspend fun synthesizeOnlineOnly(
        cacheScope: SpeechCacheScopeToken?,
        request: SpeechRequest,
    ): RoutedSpeechResult {
        return when (val synthesis = onlineEngine.synthesize(request)) {
            is OnlineSynthesisResult.Audio -> RoutedSpeechResult(
                playCacheAndWipe(cacheScope, request, synthesis.bytes),
                onlineEngine.id,
            )
            OnlineSynthesisResult.Cancelled -> RoutedSpeechResult(SpeechEngineResult.Cancelled, onlineEngine.id)
            is OnlineSynthesisResult.Failed -> RoutedSpeechResult(SpeechEngineResult.Failed(synthesis.error), onlineEngine.id)
        }
    }

    private suspend fun routeAutomatically(
        cacheScope: SpeechCacheScopeToken?,
        request: SpeechRequest,
    ): RoutedSpeechResult {
        cachedAudio(cacheScope, request)?.let { audio ->
            val result = playAndWipe(audio)
            if (result !is SpeechEngineResult.Failed) {
                return RoutedSpeechResult(result, onlineEngine.id)
            }
            removeCached(cacheScope, request)
        }

        var retryIndex = 0
        while (true) {
            when (val synthesis = onlineEngine.synthesize(request)) {
                is OnlineSynthesisResult.Audio -> {
                    val result = playCacheAndWipe(cacheScope, request, synthesis.bytes)
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

    private suspend fun retainCacheScope(
        requestGeneration: Long,
        request: SpeechRequest,
    ): SpeechCacheScopeToken? =
        try {
            cache.retainScope(requestGeneration, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Cache maintenance is best-effort and cannot block speech availability.
            null
        }

    private suspend fun cachedAudio(
        cacheScope: SpeechCacheScopeToken?,
        request: SpeechRequest,
    ): ByteArray? = if (cacheScope == null) null else try {
        cache.get(cacheScope, request, nowMillis())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    private suspend fun playCacheAndWipe(
        cacheScope: SpeechCacheScopeToken?,
        request: SpeechRequest,
        audio: ByteArray,
    ): SpeechEngineResult = try {
        val result = onlineEngine.playEncoded(audio)
        if (result == SpeechEngineResult.Completed) {
            try {
                if (cacheScope != null) cache.put(cacheScope, request, audio, nowMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A private cache failure must not turn successful speech into a failure.
            }
        } else if (result is SpeechEngineResult.Failed) {
            removeCached(cacheScope, request)
        }
        result
    } finally {
        audio.fill(0)
    }

    private suspend fun removeCached(cacheScope: SpeechCacheScopeToken?, request: SpeechRequest) {
        if (cacheScope == null) return
        try {
            cache.remove(cacheScope, request)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A failed eviction is non-fatal; the next playback will retry eviction.
        }
    }

    private suspend fun playAndWipe(audio: ByteArray): SpeechEngineResult = try {
        onlineEngine.playEncoded(audio)
    } finally {
        audio.fill(0)
    }
}

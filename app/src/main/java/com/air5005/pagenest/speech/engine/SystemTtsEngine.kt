package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.model.SpeechError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

class SystemTtsEngine internal constructor(
    factory: PlatformTextToSpeechFactory,
    scope: CoroutineScope,
) : SpeechEngine {
    override val id: String = "system"

    private val closed = AtomicBoolean(false)
    private val platformResource = AtomicReference<PlatformTextToSpeech?>()
    private val platform = scope.async {
        factory.create()?.also { created ->
            if (closed.get()) {
                created.shutdown()
            } else {
                platformResource.set(created)
                if (closed.get() && platformResource.compareAndSet(created, null)) {
                    created.shutdown()
                }
            }
        }
    }
    private val listenerInstalled = AtomicBoolean(false)
    private val active = AtomicReference<ActiveUtterance?>()

    override suspend fun voices(localeTag: String): List<SpeechVoice> {
        val requestedTag = java.util.Locale.forLanguageTag(localeTag).toLanguageTag()
        return platform.await()
            ?.voices()
            .orEmpty()
            .filter { it.localeTag.equals(requestedTag, ignoreCase = true) }
            .map { SpeechVoice(it.id, it.displayName, it.localeTag) }
    }

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
        if (closed.get()) return SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable)
        val tts = platform.await()
            ?: return SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable)
        installListener(tts)
        when (tts.setLanguage(java.util.Locale.forLanguageTag(request.localeTag))) {
            android.speech.tts.TextToSpeech.LANG_MISSING_DATA ->
                return SpeechEngineResult.Failed(SpeechError.MissingLanguageData)
            android.speech.tts.TextToSpeech.LANG_NOT_SUPPORTED ->
                return SpeechEngineResult.Failed(SpeechError.UnsupportedLocale)
        }
        request.voiceId?.let(tts::selectVoice)
        tts.setRate(request.rate.coerceIn(MIN_RATE_OR_PITCH, MAX_RATE_OR_PITCH))
        tts.setPitch(request.pitch.coerceIn(MIN_RATE_OR_PITCH, MAX_RATE_OR_PITCH))

        return suspendCancellableCoroutine { continuation ->
            val utterance = ActiveUtterance(
                id = "${request.generationId}:${request.segment.id}",
                continuation = continuation,
            )
            active.getAndSet(utterance)?.complete(SpeechEngineResult.Cancelled)
            continuation.invokeOnCancellation {
                if (active.compareAndSet(utterance, null) && utterance.markCompleted()) {
                    tts.stop()
                }
            }

            if (
                tts.speak(
                    request.segment.text,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    utterance.id,
                ) == android.speech.tts.TextToSpeech.ERROR
            ) {
                finish(
                    utterance.id,
                    SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable),
                )
            }
        }
    }

    override suspend fun stop() {
        active.getAndSet(null)?.complete(SpeechEngineResult.Cancelled)
        platform.await()?.stop()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.getAndSet(null)?.complete(SpeechEngineResult.Cancelled)
        platformResource.getAndSet(null)?.let { tts ->
            tts.stop()
            tts.shutdown()
        }
        platform.cancel()
    }

    private fun installListener(tts: PlatformTextToSpeech) {
        if (!listenerInstalled.compareAndSet(false, true)) return
        tts.setProgressListener(object : PlatformUtteranceProgressListener {
            override fun onDone(utteranceId: String) {
                finish(utteranceId, SpeechEngineResult.Completed)
            }

            override fun onError(utteranceId: String) {
                finish(
                    utteranceId,
                    SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable),
                )
            }

            override fun onStop(utteranceId: String) {
                finish(utteranceId, SpeechEngineResult.Cancelled)
            }
        })
    }

    private fun finish(utteranceId: String, result: SpeechEngineResult) {
        val utterance = active.get() ?: return
        if (utterance.id != utteranceId || !active.compareAndSet(utterance, null)) return
        utterance.complete(result)
    }

    private class ActiveUtterance(
        val id: String,
        private val continuation: CancellableContinuation<SpeechEngineResult>,
    ) {
        private val completed = AtomicBoolean(false)

        fun markCompleted(): Boolean = completed.compareAndSet(false, true)

        fun complete(result: SpeechEngineResult) {
            if (markCompleted()) continuation.resume(result)
        }
    }

    private companion object {
        const val MIN_RATE_OR_PITCH = 0.25f
        const val MAX_RATE_OR_PITCH = 2f
    }
}

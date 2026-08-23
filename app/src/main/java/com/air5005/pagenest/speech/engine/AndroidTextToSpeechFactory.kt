package com.air5005.pagenest.speech.engine

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

internal fun interface PlatformTextToSpeechFactory {
    suspend fun create(): PlatformTextToSpeech?
}

internal interface PlatformTextToSpeech {
    fun setProgressListener(listener: PlatformUtteranceProgressListener)
    fun languageStatus(locale: Locale): Int
    fun voices(): List<PlatformSpeechVoice>
    fun selectVoice(id: String): Boolean
    fun setLanguage(locale: Locale): Int
    fun setRate(rate: Float): Int
    fun setPitch(pitch: Float): Int
    fun speak(text: String, queueMode: Int, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

internal data class PlatformSpeechVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
    val networkConnectionRequired: Boolean,
)

internal interface PlatformUtteranceProgressListener {
    fun onDone(utteranceId: String)
    fun onError(utteranceId: String)
    fun onStop(utteranceId: String)
}

internal class AndroidTextToSpeechFactory(
    context: Context,
) : PlatformTextToSpeechFactory {
    private val applicationContext = context.applicationContext

    override suspend fun create(): PlatformTextToSpeech? =
        withContext(Dispatchers.Main.immediate) {
            createOnMainThread()
        }

    private suspend fun createOnMainThread(): PlatformTextToSpeech? =
        suspendCancellableCoroutine { continuation ->
            val textToSpeech = AtomicReference<TextToSpeech?>()
            val initializationStatus = AtomicReference<Int?>()
            val settled = AtomicBoolean(false)
            val released = AtomicBoolean(false)

            fun shutdownIfCreated() {
                val tts = textToSpeech.get() ?: return
                if (released.compareAndSet(false, true)) tts.shutdown()
            }

            fun finishWhenReady() {
                val tts = textToSpeech.get() ?: return
                val status = initializationStatus.get() ?: return
                if (!settled.compareAndSet(false, true)) return
                if (status == TextToSpeech.SUCCESS) {
                    continuation.resume(AndroidPlatformTextToSpeech(tts)) { _, resource, _ ->
                        resource.shutdown()
                    }
                } else {
                    shutdownIfCreated()
                    continuation.resume(null)
                }
            }

            continuation.invokeOnCancellation {
                if (settled.compareAndSet(false, true)) {
                    shutdownIfCreated()
                }
            }

            try {
                val created = TextToSpeech(applicationContext) { status ->
                    initializationStatus.set(status)
                    finishWhenReady()
                }
                textToSpeech.set(created)
                if (settled.get()) {
                    shutdownIfCreated()
                } else {
                    finishWhenReady()
                }
            } catch (_: RuntimeException) {
                if (settled.compareAndSet(false, true)) continuation.resume(null)
            }
        }
}

private class AndroidPlatformTextToSpeech(
    private val textToSpeech: TextToSpeech,
) : PlatformTextToSpeech {
    override fun setProgressListener(listener: PlatformUtteranceProgressListener) {
        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) {
                utteranceId?.let(listener::onDone)
            }

            @Deprecated("Deprecated by Android")
            override fun onError(utteranceId: String?) {
                utteranceId?.let(listener::onError)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let(listener::onError)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceId?.let(listener::onStop)
            }
        })
    }

    override fun languageStatus(locale: Locale): Int =
        textToSpeech.isLanguageAvailable(locale)

    override fun voices(): List<PlatformSpeechVoice> =
        textToSpeech.voices.orEmpty().map { voice ->
            PlatformSpeechVoice(
                id = voice.name,
                displayName = voice.name,
                localeTag = voice.locale.toLanguageTag(),
                networkConnectionRequired = voice.isNetworkConnectionRequired,
            )
        }

    override fun selectVoice(id: String): Boolean {
        val voice = textToSpeech.voices.orEmpty().firstOrNull { it.name == id } ?: return false
        return textToSpeech.setVoice(voice) == TextToSpeech.SUCCESS
    }

    override fun setLanguage(locale: Locale): Int = textToSpeech.setLanguage(locale)

    override fun setRate(rate: Float): Int = textToSpeech.setSpeechRate(rate)

    override fun setPitch(pitch: Float): Int = textToSpeech.setPitch(pitch)

    override fun speak(text: String, queueMode: Int, utteranceId: String): Int =
        textToSpeech.speak(text, queueMode, null, utteranceId)

    override fun stop(): Int = textToSpeech.stop()

    override fun shutdown() = textToSpeech.shutdown()
}

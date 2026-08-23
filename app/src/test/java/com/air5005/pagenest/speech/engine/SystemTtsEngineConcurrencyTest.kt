package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTtsEngineConcurrencyTest {
    @Test
    fun `stop during initialization cancels pending speech without a later start`() = runTest {
        val factory = GatedPlatformFactory()
        val platform = RecordingPlatformTts()
        val engine = SystemTtsEngine(factory, backgroundScope)
        val speech = async { engine.speak(request("待初始化")) }
        runCurrent()

        val stopping = async { engine.stop() }
        runCurrent()
        factory.result.complete(platform)
        runCurrent()

        assertEquals(SpeechEngineResult.Cancelled, speech.await())
        assertTrue(stopping.isCompleted)
        assertEquals(emptyList<RecordingPlatformTts.Spoken>(), platform.spoken)
    }

    @Test
    fun `close during initialization returns engine cancellation instead of internal job cancellation`() = runTest {
        val factory = GatedPlatformFactory()
        val engine = SystemTtsEngine(factory, backgroundScope)
        val speech = async { engine.speak(request("关闭初始化")) }
        runCurrent()

        engine.close()
        runCurrent()

        assertEquals(SpeechEngineResult.Cancelled, speech.await())
    }

    @Test
    fun `caller cancellation before platform start never starts and all platform calls stay on owner`() {
        val ownerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "system-tts-owner")
        }
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        try {
            val platform = RecordingPlatformTts(blockFirstLanguageCall = true)
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            val ownerThreadName = runBlocking(ownerDispatcher) {
                Thread.currentThread().name.substringBefore(" @coroutine#")
            }
            val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val speech = callerScope.async { engine.speak(request("取消门")) }
            assertTrue(platform.languageEntered.await(5, TimeUnit.SECONDS))

            speech.cancel()
            platform.allowLanguage.countDown()
            runBlocking { speech.join() }
            runBlocking(ownerDispatcher) { Unit }

            assertTrue(speech.isCancelled)
            assertEquals(emptyList<RecordingPlatformTts.Spoken>(), platform.spoken)
            engine.close()
            runBlocking(ownerDispatcher) { Unit }
            assertTrue(platform.callThreadNames.isNotEmpty())
            assertEquals(setOf(ownerThreadName), platform.callThreadNames.toSet())
            callerScope.cancel()
        } finally {
            ownerScope.cancel()
            ownerDispatcher.close()
            ownerExecutor.shutdownNow()
        }
    }

    @Test
    fun `overlapping starts cannot let older request flush the newer request`() {
        val ownerExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "system-tts-overlap-owner")
        }
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val platform = RecordingPlatformTts(blockFirstLanguageCall = true)
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            val first = callerScope.async { engine.speak(request("A")) }
            assertTrue(platform.languageEntered.await(5, TimeUnit.SECONDS))
            val second = callerScope.async { engine.speak(request("B")) }

            platform.allowLanguage.countDown()
            assertTrue(platform.twoStarts.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("A", "B"), platform.spoken.map { it.text })
            assertEquals(SpeechEngineResult.Cancelled, runBlocking { first.await() })
            platform.complete(platform.spoken.last().utteranceId)
            assertEquals(SpeechEngineResult.Completed, runBlocking { second.await() })
            engine.close()
        } finally {
            callerScope.cancel()
            ownerScope.cancel()
            ownerDispatcher.close()
            ownerExecutor.shutdownNow()
        }
    }

    private fun request(text: String) = SpeechRequest(
        generationId = 41,
        segment = SpeechSegment(
            id = "segment-$text",
            position = SpeechPosition(9, 2, null, 3, 0),
            partIndex = 0,
            text = text,
            locator = Locator(text = text, progression = 0.5),
        ),
        localeTag = "zh-CN",
        voiceId = null,
        rate = 1f,
        pitch = 1f,
    )
}

private class GatedPlatformFactory : PlatformTextToSpeechFactory {
    val result = CompletableDeferred<PlatformTextToSpeech?>()

    override suspend fun create(): PlatformTextToSpeech? = result.await()
}

private class RecordingPlatformTts(
    private val blockFirstLanguageCall: Boolean = false,
) : PlatformTextToSpeech {
    data class Spoken(val text: String, val queueMode: Int, val utteranceId: String)

    @Volatile
    private var listener: PlatformUtteranceProgressListener? = null
    private val languageCalls = java.util.concurrent.atomic.AtomicInteger(0)
    val languageEntered = CountDownLatch(1)
    val allowLanguage = CountDownLatch(if (blockFirstLanguageCall) 1 else 0)
    val twoStarts = CountDownLatch(2)
    val callThreadNames = CopyOnWriteArrayList<String>()
    val spoken = CopyOnWriteArrayList<Spoken>()

    override fun setProgressListener(listener: PlatformUtteranceProgressListener) {
        recordThread()
        this.listener = listener
    }

    override fun languageStatus(locale: Locale): Int {
        recordThread()
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun voices(): List<PlatformSpeechVoice> {
        recordThread()
        return listOf(PlatformSpeechVoice("zh-offline", "Chinese offline", "zh-CN", false))
    }

    override fun selectVoice(id: String): Boolean {
        recordThread()
        return id == "zh-offline"
    }

    override fun setLanguage(locale: Locale): Int {
        recordThread()
        if (blockFirstLanguageCall && languageCalls.getAndIncrement() == 0) {
            languageEntered.countDown()
            check(allowLanguage.await(5, TimeUnit.SECONDS))
        }
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun setRate(rate: Float): Int {
        recordThread()
        return TextToSpeech.SUCCESS
    }

    override fun setPitch(pitch: Float): Int {
        recordThread()
        return TextToSpeech.SUCCESS
    }

    override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
        recordThread()
        spoken += Spoken(text, queueMode, utteranceId)
        twoStarts.countDown()
        return TextToSpeech.SUCCESS
    }

    override fun stop(): Int {
        recordThread()
        return TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        recordThread()
    }

    fun complete(utteranceId: String) {
        listener?.onDone(utteranceId)
    }

    private fun recordThread() {
        callThreadNames += Thread.currentThread().name.substringBefore(" @coroutine#")
    }
}

package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

class SystemTtsEngineExceptionTest {
    @Test
    fun `start configuration and speak exceptions fail only that call and actor remains usable`() {
        listOf(
            ThrowPoint.SET_LANGUAGE,
            ThrowPoint.VOICES,
            ThrowPoint.SELECT_VOICE,
            ThrowPoint.SET_RATE,
            ThrowPoint.SET_PITCH,
            ThrowPoint.SPEAK,
        ).forEach { point ->
            withEngine(point) { engine, platform, callers ->
                assertEquals(
                    SpeechEngineResult.Failed(SpeechError.SystemTtsStartFailed),
                    callers.submit<SpeechEngineResult> {
                        runBlocking { engine.speak(request("first-$point")) }
                    }.get(2, TimeUnit.SECONDS),
                )
                assertEquals(
                    SpeechEngineResult.Completed,
                    callers.submit<SpeechEngineResult> {
                        runBlocking { engine.speak(request("second-$point")) }
                    }.get(2, TimeUnit.SECONDS),
                )
                callers.submit { runBlocking { engine.stop() } }.get(2, TimeUnit.SECONDS)
                callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
                assertEquals(1, platform.shutdownCalls.get())
            }
        }
    }

    @Test
    fun `listener exception maps pending speech to initialization failure and close still releases`() {
        val admissionLock = ReentrantLock(true)
        val factory = GatedThrowingFactory()
        withOwner("listener-throw") { ownerScope, callers ->
            val platform = ThrowingPlatformTts(ThrowPoint.LISTENER)
            val engine = SystemTtsEngine(factory, ownerScope, admissionLock)
            assertTrue(factory.entered.await(2, TimeUnit.SECONDS))

            admissionLock.lock()
            val speech = callers.submit<SpeechEngineResult> {
                runBlocking { engine.speak(request("pending-listener")) }
            }
            awaitQueueLength(admissionLock, 1)
            admissionLock.unlock()
            admissionLock.lock()
            admissionLock.unlock()
            factory.result.complete(platform)

            assertEquals(
                SpeechEngineResult.Failed(SpeechError.SystemTtsInitializationFailed),
                speech.get(2, TimeUnit.SECONDS),
            )
            callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
            assertEquals(1, platform.stopCalls.get())
            assertEquals(1, platform.shutdownCalls.get())
        }
    }

    @Test
    fun `factory exception maps to initialization failure without stranding close`() {
        withOwner("factory-throw") { ownerScope, callers ->
            val engine = SystemTtsEngine(
                PlatformTextToSpeechFactory { error("factory") },
                ownerScope,
            )
            assertEquals(
                SpeechEngineResult.Failed(SpeechError.SystemTtsInitializationFailed),
                callers.submit<SpeechEngineResult> {
                    runBlocking { engine.speak(request("factory")) }
                }.get(2, TimeUnit.SECONDS),
            )
            callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `voices exception returns empty and later speech still completes`() {
        withEngine(ThrowPoint.VOICES) { engine, platform, callers ->
            assertEquals(
                emptyList<SpeechVoice>(),
                callers.submit<List<SpeechVoice>> {
                    runBlocking { engine.voices("zh-CN") }
                }.get(2, TimeUnit.SECONDS),
            )
            assertEquals(
                SpeechEngineResult.Completed,
                callers.submit<SpeechEngineResult> {
                    runBlocking { engine.speak(request("after-voices")) }
                }.get(2, TimeUnit.SECONDS),
            )
            callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
            assertEquals(1, platform.shutdownCalls.get())
        }
    }

    @Test
    fun `stop exception terminalizes caller and actor accepts later speech and close`() {
        withEngine(ThrowPoint.STOP, autoComplete = false) { engine, platform, callers ->
            val speech = callers.submit<SpeechEngineResult> {
                runBlocking { engine.speak(request("playing")) }
            }
            assertTrue(platform.spoken.await(2, TimeUnit.SECONDS))
            callers.submit { runBlocking { engine.stop() } }.get(2, TimeUnit.SECONDS)
            assertEquals(SpeechEngineResult.Cancelled, speech.get(2, TimeUnit.SECONDS))

            platform.autoComplete.set(true)
            assertEquals(
                SpeechEngineResult.Completed,
                callers.submit<SpeechEngineResult> {
                    runBlocking { engine.speak(request("after-stop")) }
                }.get(2, TimeUnit.SECONDS),
            )
            callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
            assertEquals(1, platform.shutdownCalls.get())
        }
    }

    @Test
    fun `release exceptions do not escape close and both release calls remain exact once`() {
        listOf(ThrowPoint.STOP, ThrowPoint.SHUTDOWN).forEach { point ->
            withEngine(point) { engine, platform, callers ->
                callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
                callers.submit { engine.close() }.get(2, TimeUnit.SECONDS)
                assertEquals(1, platform.stopCalls.get())
                assertEquals(1, platform.shutdownCalls.get())
            }
        }
    }

    private fun withEngine(
        throwPoint: ThrowPoint,
        autoComplete: Boolean = true,
        block: (SystemTtsEngine, ThrowingPlatformTts, java.util.concurrent.ExecutorService) -> Unit,
    ) {
        withOwner("throw-$throwPoint") { ownerScope, callers ->
            val platform = ThrowingPlatformTts(throwPoint, autoComplete)
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            assertTrue(platform.listenerInstalled.await(2, TimeUnit.SECONDS))
            block(engine, platform, callers)
        }
    }

    private fun withOwner(
        threadName: String,
        block: (CoroutineScope, java.util.concurrent.ExecutorService) -> Unit,
    ) {
        val ownerExecutor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        val ownerDispatcher = ownerExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val callers = Executors.newFixedThreadPool(2)
        try {
            block(ownerScope, callers)
        } finally {
            ownerScope.cancel()
            callers.shutdownNow()
            ownerDispatcher.close()
            ownerExecutor.shutdownNow()
        }
    }

    private fun awaitQueueLength(lock: ReentrantLock, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (lock.queueLength < expected && System.nanoTime() < deadline) Thread.yield()
        assertTrue("expected $expected queued lock waiters", lock.queueLength >= expected)
    }

    private fun request(text: String) = SpeechRequest(
        generationId = 61,
        segment = SpeechSegment(
            id = "segment-$text",
            position = SpeechPosition(1, 1, null, 1, 0),
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

private enum class ThrowPoint {
    LISTENER,
    VOICES,
    SET_LANGUAGE,
    SELECT_VOICE,
    SET_RATE,
    SET_PITCH,
    SPEAK,
    STOP,
    SHUTDOWN,
}

private class GatedThrowingFactory : PlatformTextToSpeechFactory {
    val entered = CountDownLatch(1)
    val result = CompletableDeferred<PlatformTextToSpeech?>()

    override suspend fun create(): PlatformTextToSpeech? {
        entered.countDown()
        return result.await()
    }
}

private class ThrowingPlatformTts(
    throwPoint: ThrowPoint,
    autoComplete: Boolean = true,
) : PlatformTextToSpeech {
    private val pendingThrow = AtomicReference(throwPoint)
    private var listener: PlatformUtteranceProgressListener? = null
    val autoComplete = AtomicBoolean(autoComplete)
    val listenerInstalled = CountDownLatch(1)
    val spoken = CountDownLatch(1)
    val stopCalls = AtomicInteger(0)
    val shutdownCalls = AtomicInteger(0)

    override fun setProgressListener(listener: PlatformUtteranceProgressListener) {
        throwIf(ThrowPoint.LISTENER)
        this.listener = listener
        listenerInstalled.countDown()
    }

    override fun languageStatus(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

    override fun voices(): List<PlatformSpeechVoice> {
        throwIf(ThrowPoint.VOICES)
        return listOf(PlatformSpeechVoice("offline", "Offline", "zh-CN", false))
    }

    override fun selectVoice(id: String): Boolean {
        throwIf(ThrowPoint.SELECT_VOICE)
        return true
    }

    override fun setLanguage(locale: Locale): Int {
        throwIf(ThrowPoint.SET_LANGUAGE)
        return TextToSpeech.LANG_AVAILABLE
    }

    override fun setRate(rate: Float): Int {
        throwIf(ThrowPoint.SET_RATE)
        return TextToSpeech.SUCCESS
    }

    override fun setPitch(pitch: Float): Int {
        throwIf(ThrowPoint.SET_PITCH)
        return TextToSpeech.SUCCESS
    }

    override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
        throwIf(ThrowPoint.SPEAK)
        spoken.countDown()
        if (autoComplete.get()) listener?.onDone(utteranceId)
        return TextToSpeech.SUCCESS
    }

    override fun stop(): Int {
        stopCalls.incrementAndGet()
        throwIf(ThrowPoint.STOP)
        return TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        shutdownCalls.incrementAndGet()
        throwIf(ThrowPoint.SHUTDOWN)
    }

    private fun throwIf(point: ThrowPoint) {
        if (pendingThrow.compareAndSet(point, null)) error("throw at $point")
    }
}

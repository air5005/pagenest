package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.EmptyCoroutineContext

class SystemTtsEngineCloseAdmissionTest {
    @Test
    fun `pre-start fallback publishes close before inline resumed caller reenters close`() {
        val queuedExecutor = QueuedExecutor()
        val ownerDispatcher = queuedExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val factoryCalls = AtomicInteger(0)
        val engine = SystemTtsEngine(
            PlatformTextToSpeechFactory {
                factoryCalls.incrementAndGet()
                CloseRecordingPlatform()
            },
            ownerScope,
        )
        val reentrantCloseReturned = CountDownLatch(1)
        val admittedSpeech = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                engine.speak(request("inline-reentry"))
            } finally {
                engine.close()
                reentrantCloseReturned.countDown()
            }
        }
        val driver = Executors.newSingleThreadExecutor()
        try {
            assertEquals(2, queuedExecutor.size)
            assertEquals(0, factoryCalls.get())
            assertFalse(admittedSpeech.isCompleted)

            driver.submit { ownerScope.cancel() }.get(5, TimeUnit.SECONDS)

            assertTrue(reentrantCloseReturned.await(0, TimeUnit.SECONDS))
            assertEquals(SpeechEngineResult.Cancelled, runBlocking { admittedSpeech.await() })
            assertEquals(0, factoryCalls.get())
            engine.close()
        } finally {
            callerScope.cancel()
            ownerScope.cancel()
            driver.shutdownNow()
            driver.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `pre-start owner cancellation terminalizes admitted calls and close returns`() {
        val queuedExecutor = QueuedExecutor()
        val ownerDispatcher = queuedExecutor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + ownerDispatcher)
        val callerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val factoryCalls = AtomicInteger(0)
        val engine = SystemTtsEngine(
            PlatformTextToSpeechFactory {
                factoryCalls.incrementAndGet()
                CloseRecordingPlatform()
            },
            ownerScope,
        )
        val admittedSpeech = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.speak(request("queued"))
        }
        val admittedVoices = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.voices("zh-CN")
        }
        val admittedStop = callerScope.async(start = CoroutineStart.UNDISPATCHED) {
            engine.stop()
        }
        val closer = Executors.newSingleThreadExecutor()
        try {
            assertTrue(queuedExecutor.size >= 2)
            assertFalse(admittedSpeech.isCompleted)
            assertFalse(admittedVoices.isCompleted)
            assertFalse(admittedStop.isCompleted)

            ownerScope.cancel()
            closer.submit { engine.close() }.get(5, TimeUnit.SECONDS)

            assertEquals(0, factoryCalls.get())
            assertTrue(admittedSpeech.isCompleted)
            assertTrue(admittedVoices.isCompleted)
            assertTrue(admittedStop.isCompleted)
            assertEquals(SpeechEngineResult.Cancelled, runBlocking { admittedSpeech.await() })
            assertEquals(emptyList<SpeechVoice>(), runBlocking { admittedVoices.await() })
            runBlocking { admittedStop.await() }
            assertEquals(
                SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable),
                runBlocking { engine.speak(request("rejected")) },
            )
            assertEquals(emptyList<SpeechVoice>(), runBlocking { engine.voices("zh-CN") })
            runBlocking { engine.stop() }
        } finally {
            callerScope.cancel()
            ownerScope.cancel()
            closer.shutdownNow()
        }
    }

    @Test
    fun `off owner close waits for cancellation resistant late initialization release`() {
        withOwner("late-init-owner") { ownerScope, platform ->
            val factory = CancellationIgnoringFactory(platform)
            val engine = SystemTtsEngine(factory, ownerScope)
            assertTrue(factory.entered.await(5, TimeUnit.SECONDS))
            val closer = Executors.newSingleThreadExecutor()
            val closeReturned = CountDownLatch(1)
            try {
                val closeFuture = closer.submit {
                    engine.close()
                    closeReturned.countDown()
                }
                assertTrue(factory.cancellationObserved.await(5, TimeUnit.SECONDS))
                assertFalse(closeReturned.await(200, TimeUnit.MILLISECONDS))
                assertEquals(0, platform.stopCalls.get())
                assertEquals(0, platform.shutdownCalls.get())

                factory.allowReturn.complete(Unit)
                closeFuture.get(5, TimeUnit.SECONDS)

                assertTrue(closeReturned.await(0, TimeUnit.SECONDS))
                assertEquals(1, platform.stopCalls.get())
                assertEquals(1, platform.shutdownCalls.get())
                assertEquals(setOf("late-init-owner"), platform.callThreadNames.toSet())
            } finally {
                factory.allowReturn.complete(Unit)
                closer.shutdownNow()
            }
        }
    }

    @Test
    fun `owner reentrant close return observes exact once release`() {
        withOwner("owner-reentrant") { ownerScope, platform ->
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            awaitLatch(platform.listenerInstalled, "TTS listener installation")
            val releaseObservedAtReentrantReturn = CopyOnWriteArrayList<Pair<Int, Int>>()
            val continuation = Proxy.newProxyInstance(
                CancellableContinuation::class.java.classLoader,
                arrayOf(CancellableContinuation::class.java),
            ) { _, method, _ ->
                when (method.name) {
                    "getContext" -> EmptyCoroutineContext
                    "isActive" -> true
                    "isCompleted", "isCancelled" -> false
                    "resume", "resumeWith" -> {
                        engine.close()
                        releaseObservedAtReentrantReturn +=
                            platform.stopCalls.get() to platform.shutdownCalls.get()
                        Unit
                    }
                    else -> defaultValue(method.returnType)
                }
            }
            runBlocking(ownerScope.coroutineContext) {
                installActiveSpeech(engine, continuation)
                assertEquals(0, platform.stopCalls.get())
                assertEquals(0, platform.shutdownCalls.get())
                engine.close()
            }

            assertEquals(listOf(1 to 1), releaseObservedAtReentrantReturn)
            assertEquals(1, platform.stopCalls.get())
            assertEquals(1, platform.shutdownCalls.get())
        }
    }

    private fun installActiveSpeech(
        engine: SystemTtsEngine,
        continuation: Any,
    ) {
        val callClass = SystemTtsEngine::class.java.declaredClasses.single {
            it.simpleName == "SpeechCall"
        }
        val constructor = callClass.declaredConstructors.single().apply { isAccessible = true }
        val call = constructor.newInstance(request("reentrant"), "reentrant-id", continuation)
        val stateField = SystemTtsEngine::class.java.getDeclaredField("ownerState").apply {
            isAccessible = true
        }
        val state = stateField.get(engine)
        state.javaClass.getDeclaredField("activeSpeech").apply {
            isAccessible = true
            set(state, call)
        }
    }

    private fun defaultValue(returnType: Class<*>): Any? = when (returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }

    @Test
    fun `close races terminate admitted speak voices and stop callers`() {
        listOf("speak", "voices", "stop").forEach { operation ->
            withOwner("admission-$operation") { ownerScope, platform ->
                val admissionLock = ReentrantLock(true)
                val engine = SystemTtsEngine(
                    PlatformTextToSpeechFactory { platform },
                    ownerScope,
                    admissionLock,
                )
                awaitLatch(platform.listenerInstalled, "TTS listener installation")
                val callers = Executors.newFixedThreadPool(2)
                try {
                    admissionLock.lock()
                    val operationStarted = CountDownLatch(1)
                    val operationFuture = callers.submit<Any?> {
                        operationStarted.countDown()
                        runBlocking {
                            when (operation) {
                                "speak" -> engine.speak(request(operation))
                                "voices" -> engine.voices("zh-CN")
                                else -> {
                                    engine.stop()
                                    Unit
                                }
                            }
                        }
                    }
                    assertTrue(operationStarted.await(5, TimeUnit.SECONDS))
                    awaitQueueLength(admissionLock, 1)

                    val closeReturned = CountDownLatch(1)
                    val closeFuture = callers.submit {
                        engine.close()
                        closeReturned.countDown()
                    }
                    awaitQueueLength(admissionLock, 2)
                    admissionLock.unlock()

                    operationFuture.get(5, TimeUnit.SECONDS)
                    closeFuture.get(5, TimeUnit.SECONDS)
                    assertTrue(closeReturned.await(0, TimeUnit.SECONDS))
                    assertEquals(if (operation == "stop") 2 else 1, platform.stopCalls.get())
                    assertEquals(1, platform.shutdownCalls.get())
                } finally {
                    if (admissionLock.isHeldByCurrentThread) admissionLock.unlock()
                    callers.shutdownNow()
                }
            }
        }
    }

    @Test
    fun `off owner concurrent close waits for exact once release before returning`() {
        withOwner("off-owner-close") { ownerScope, platform ->
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            awaitLatch(platform.listenerInstalled, "TTS listener installation")
            val ownerBlocked = CountDownLatch(1)
            val releaseOwner = CountDownLatch(1)
            ownerScope.launch {
                ownerBlocked.countDown()
                check(releaseOwner.await(5, TimeUnit.SECONDS))
            }
            assertTrue(ownerBlocked.await(5, TimeUnit.SECONDS))

            val closers = Executors.newFixedThreadPool(3)
            val closeReturned = CountDownLatch(3)
            try {
                repeat(3) {
                    closers.submit {
                        engine.close()
                        closeReturned.countDown()
                    }
                }
                assertFalse(closeReturned.await(200, TimeUnit.MILLISECONDS))
                assertEquals(0, platform.shutdownCalls.get())

                releaseOwner.countDown()
                assertTrue(closeReturned.await(5, TimeUnit.SECONDS))
                assertEquals(1, platform.stopCalls.get())
                assertEquals(1, platform.shutdownCalls.get())
                assertEquals(setOf("off-owner-close"), platform.callThreadNames.toSet())
            } finally {
                releaseOwner.countDown()
                closers.shutdownNow()
            }
        }
    }

    @Test
    fun `owner thread close releases synchronously and remains idempotent`() {
        withOwner("owner-close") { ownerScope, platform ->
            val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, ownerScope)
            awaitLatch(platform.listenerInstalled, "TTS listener installation")

            val releasedAtReturn = Executors.newSingleThreadExecutor().useExecutor { observer ->
                observer.submit<Pair<Int, Int>> {
                    runBlocking(ownerScope.coroutineContext) {
                        engine.close()
                        engine.close()
                        platform.stopCalls.get() to platform.shutdownCalls.get()
                    }
                }.get(5, TimeUnit.SECONDS)
            }

            assertEquals(1 to 1, releasedAtReturn)
            assertEquals(setOf("owner-close"), platform.callThreadNames.toSet())
            assertEquals(
                SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable),
                runBlocking { engine.speak(request("after-close")) },
            )
            assertEquals(emptyList<SpeechVoice>(), runBlocking { engine.voices("zh-CN") })
            runBlocking { engine.stop() }
        }
    }

    private fun withOwner(
        threadName: String,
        block: (CoroutineScope, CloseRecordingPlatform) -> Unit,
    ) {
        val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, threadName) }
        val dispatcher = executor.asCoroutineDispatcher()
        val ownerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            block(ownerScope, CloseRecordingPlatform())
        } finally {
            ownerScope.cancel()
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    private fun awaitQueueLength(lock: ReentrantLock, expected: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (lock.queueLength < expected && System.nanoTime() < deadline) Thread.yield()
        assertTrue("expected $expected queued lock waiters", lock.queueLength >= expected)
    }

    private fun awaitLatch(latch: CountDownLatch, operation: String) {
        assertTrue("timed out waiting for $operation", latch.await(30, TimeUnit.SECONDS))
    }

    private fun request(text: String) = SpeechRequest(
        generationId = 51,
        segment = SpeechSegment(
            id = "segment-$text",
            position = SpeechPosition(1, 1, null, 1, 0),
            partIndex = 0,
            text = text,
            locator = Locator(text = text, progression = 0.25),
        ),
        localeTag = "zh-CN",
        voiceId = null,
        rate = 1f,
        pitch = 1f,
    )
}

private class QueuedExecutor : Executor {
    private val tasks = ConcurrentLinkedQueue<Runnable>()
    val size: Int get() = tasks.size

    override fun execute(command: Runnable) {
        tasks += command
    }
}

private inline fun <T> java.util.concurrent.ExecutorService.useExecutor(
    block: (java.util.concurrent.ExecutorService) -> T,
): T = try {
    block(this)
} finally {
    shutdownNow()
}

private class CloseRecordingPlatform : PlatformTextToSpeech {
    val listenerInstalled = CountDownLatch(1)
    val stopCalls = AtomicInteger(0)
    val shutdownCalls = AtomicInteger(0)
    val callThreadNames = CopyOnWriteArrayList<String>()

    override fun setProgressListener(listener: PlatformUtteranceProgressListener) {
        recordThread()
        listenerInstalled.countDown()
    }

    override fun languageStatus(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

    override fun voices(): List<PlatformSpeechVoice> {
        recordThread()
        return listOf(PlatformSpeechVoice("offline", "Offline", "zh-CN", false))
    }

    override fun selectVoice(id: String): Boolean {
        recordThread()
        return true
    }

    override fun setLanguage(locale: Locale): Int {
        recordThread()
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
        return TextToSpeech.SUCCESS
    }

    override fun stop(): Int {
        recordThread()
        stopCalls.incrementAndGet()
        return TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        recordThread()
        shutdownCalls.incrementAndGet()
    }

    private fun recordThread() {
        callThreadNames += Thread.currentThread().name.substringBefore(" @coroutine#")
    }
}

private class CancellationIgnoringFactory(
    private val platform: PlatformTextToSpeech,
) : PlatformTextToSpeechFactory {
    val entered = CountDownLatch(1)
    val cancellationObserved = CountDownLatch(1)
    val allowReturn = CompletableDeferred<Unit>()
    private val never = CompletableDeferred<Unit>()

    override suspend fun create(): PlatformTextToSpeech? {
        entered.countDown()
        try {
            never.await()
        } catch (_: CancellationException) {
            cancellationObserved.countDown()
            withContext(NonCancellable) { allowReturn.await() }
        }
        return platform
    }
}

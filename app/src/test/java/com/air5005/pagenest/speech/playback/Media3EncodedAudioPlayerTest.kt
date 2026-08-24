package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.engine.SpeechEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

private const val HANDSHAKE_TIMEOUT_MILLIS = 1_000L
private const val THREAD_TIMEOUT_MILLIS = 2_000L

@OptIn(ExperimentalCoroutinesApi::class)
class Media3EncodedAudioPlayerTest {
    @Test
    fun `completed encoded mp3 reports completion and releases backend`() = runTest {
        val backend = FakeBackend(SpeechEngineResult.Completed)
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)

        assertEquals(SpeechEngineResult.Completed, player.playMp3(byteArrayOf(1, 2, 3)))
        player.close()

        assertEquals(listOf(byteArrayOf(1, 2, 3).toList()), backend.played.map(ByteArray::toList))
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `decode failure is returned and oversized audio is rejected before backend playback`() = runTest {
        val backend = FakeBackend(SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.AudioDecodeFailure))
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 2, backend = backend)

        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.AudioDecodeFailure),
            player.playMp3(byteArrayOf(1, 2)),
        )
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.AudioDecodeFailure),
            player.playMp3(byteArrayOf(1, 2, 3)),
        )
        assertEquals(1, backend.played.size)
        player.close()
    }

    @Test
    fun `cancelling playback stops without release and next playback succeeds`() = runTest {
        val backend = FirstPlaybackWaitsBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val playback = async { player.playMp3(byteArrayOf(1)) }
        runCurrent()
        backend.firstStarted.await()

        playback.cancel(CancellationException("user stopped"))
        runCurrent()

        assertTrue(playback.isCancelled)
        assertEquals(1, backend.stopCalls)
        assertEquals(0, backend.releaseCalls)
        assertEquals(SpeechEngineResult.Completed, player.playMp3(byteArrayOf(2)))
        player.close()
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `public stop cancels active playback without release and remains reusable`() = runTest {
        val backend = FirstPlaybackWaitsBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val playback = async { player.playMp3(byteArrayOf(1)) }
        runCurrent()
        backend.firstStarted.await()

        player.stop()

        assertEquals(SpeechEngineResult.Cancelled, withTimeout(1) { playback.await() })
        assertEquals(1, backend.stopCalls)
        assertEquals(0, backend.releaseCalls)
        assertEquals(SpeechEngineResult.Completed, player.playMp3(byteArrayOf(2)))
        player.close()
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `backend stop failure still cancels active playback and permits the next playback`() = runTest {
        val backend = StopFailureThenSuccessBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val playback = async { player.playMp3(byteArrayOf(1)) }
        runCurrent()
        backend.firstStarted.await()

        player.stop()

        assertEquals(SpeechEngineResult.Cancelled, withTimeout(1) { playback.await() })
        assertEquals(SpeechEngineResult.Completed, player.playMp3(byteArrayOf(2)))
        assertEquals(0, backend.releaseCalls)
        player.close()
        player.close()
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `close is terminal and releases the backend exactly once`() = runTest {
        val backend = FakeBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)

        player.close()
        player.close()

        assertEquals(1, backend.releaseCalls)
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.AudioDecodeFailure),
            player.playMp3(byteArrayOf(1)),
        )
        assertFalse(backend.played.isNotEmpty())
    }

    @Test
    fun `terminal close cancels owned scope even when backend release fails`() = runTest {
        val backend = ReleaseFailureBackend()
        val scopeJob = SupervisorJob()
        val player = Media3EncodedAudioPlayer(
            maxAudioBytes = 16,
            backend = backend,
            playbackScope = CoroutineScope(scopeJob + Dispatchers.Unconfined),
        )

        assertThrows(IllegalStateException::class.java) { player.close() }
        player.close()

        assertFalse(scopeJob.isActive)
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `stop between active publication and worker assignment never starts released backend`() = runTest {
        val backend = FakeBackend()
        lateinit var player: Media3EncodedAudioPlayer
        val dispatcher = StopOnFirstDispatch { player.stop() }
        player = Media3EncodedAudioPlayer(
            maxAudioBytes = 16,
            backend = backend,
            playbackScope = CoroutineScope(dispatcher),
        )

        val playback = async { player.playMp3(byteArrayOf(7)) }
        runCurrent()
        dispatcher.runQueued()
        runCurrent()

        assertEquals(SpeechEngineResult.Cancelled, playback.await())
        assertEquals(0, backend.played.size)
        assertEquals(0, backend.releaseCalls)
        val nextPlayback = async { player.playMp3(byteArrayOf(8)) }
        runCurrent()
        dispatcher.runQueued()
        runCurrent()
        assertEquals(SpeechEngineResult.Completed, nextPlayback.await())
        player.close()
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `replacement playback cannot start until active backend stop finishes`() = runTest {
        val backend = OverlappingStopBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val first = async { player.playMp3(byteArrayOf(1)) }
        runCurrent()
        backend.firstStarted.await()
        val replacement = CompletableFuture<SpeechEngineResult>()
        backend.startOverlap = {
            thread(name = "replacement-playback") {
                backend.markOverlapAttempted()
                runCatching { runBlocking { player.playMp3(byteArrayOf(2)) } }
                    .onSuccess(replacement::complete)
                    .onFailure(replacement::completeExceptionally)
            }
        }

        try {
            player.stop()

            assertEquals(SpeechEngineResult.Cancelled, first.await())
            assertEquals(
                SpeechEngineResult.Completed,
                replacement.get(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )
        } finally {
            backend.forceUnblock()
            try {
                player.close()
            } finally {
                backend.joinOverlap()
            }
        }
    }

    @Test
    fun `idle stop cannot stop a playback admitted concurrently`() = runTest {
        val backend = OverlappingStopBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val replacement = CompletableFuture<SpeechEngineResult>()
        backend.startOverlap = {
            thread(name = "idle-stop-replacement") {
                backend.markOverlapAttempted()
                runCatching { runBlocking { player.playMp3(byteArrayOf(2)) } }
                    .onSuccess(replacement::complete)
                    .onFailure(replacement::completeExceptionally)
            }
        }

        try {
            player.stop()

            assertEquals(
                SpeechEngineResult.Completed,
                replacement.get(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS),
            )
        } finally {
            backend.forceUnblock()
            try {
                player.close()
            } finally {
                backend.joinOverlap()
            }
        }
    }

    @Test
    fun `stop after terminal close never touches released backend`() = runTest {
        val backend = FakeBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)

        player.close()
        player.stop()

        assertEquals(1, backend.stopCalls)
        assertEquals(1, backend.releaseCalls)
    }

    @Test
    fun `terminal close cannot release backend while reusable stop is in progress`() = runTest {
        val backend = StopCloseOverlapBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val closeFinished = CompletableFuture<Unit>()
        backend.startClose = {
            thread(name = "terminal-close") {
                backend.markCloseAttempted()
                runCatching { player.close() }
                    .onSuccess { closeFinished.complete(Unit) }
                    .onFailure(closeFinished::completeExceptionally)
            }
        }

        try {
            player.stop()
            closeFinished.get(THREAD_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)

            assertFalse(backend.releasedDuringFirstStop.get())
            assertEquals(1, backend.releaseCalls)
        } finally {
            try {
                player.close()
            } finally {
                backend.joinClose()
            }
        }
    }

    private class FirstPlaybackWaitsBackend : EncodedAudioBackend {
        var playCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun play(bytes: ByteArray): SpeechEngineResult {
            playCalls++
            if (playCalls == 1) {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            return SpeechEngineResult.Completed
        }

        override fun stop() { stopCalls++ }
        override fun release() { releaseCalls++ }
    }

    private class StopFailureThenSuccessBackend : EncodedAudioBackend {
        var playCalls = 0
        var stopCalls = 0
        var releaseCalls = 0
        val firstStarted = kotlinx.coroutines.CompletableDeferred<Unit>()

        override suspend fun play(bytes: ByteArray): SpeechEngineResult {
            playCalls++
            if (playCalls == 1) {
                firstStarted.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }
            return SpeechEngineResult.Completed
        }

        override fun stop() {
            stopCalls++
            if (stopCalls == 1) throw IllegalStateException("backend stop failed")
        }

        override fun release() { releaseCalls++ }
    }

    private class FakeBackend(
        private val result: SpeechEngineResult = SpeechEngineResult.Completed,
        private val waitForStop: Boolean = false,
    ) : EncodedAudioBackend {
        val played = mutableListOf<ByteArray>()
        var stopCalls = 0
        var releaseCalls = 0

        override suspend fun play(bytes: ByteArray): SpeechEngineResult {
            played += bytes.copyOf()
            if (waitForStop) kotlinx.coroutines.awaitCancellation()
            return result
        }

        override fun stop() { stopCalls++ }
        override fun release() { releaseCalls++ }
    }

    private class ReleaseFailureBackend : EncodedAudioBackend {
        var releaseCalls = 0

        override suspend fun play(bytes: ByteArray): SpeechEngineResult = SpeechEngineResult.Completed
        override fun stop() = Unit
        override fun release() {
            releaseCalls++
            throw IllegalStateException("backend release failed")
        }
    }

    private class OverlappingStopBackend : EncodedAudioBackend {
        val firstStarted = CompletableDeferred<Unit>()
        private val overlapAttempted = CountDownLatch(1)
        lateinit var startOverlap: () -> Thread
        private val current = AtomicReference<CompletableDeferred<SpeechEngineResult>?>()
        private val stopFinished = AtomicBoolean(false)
        @Volatile
        private var overlapThread: Thread? = null
        private var playCalls = 0
        private var stopCalls = 0

        override suspend fun play(bytes: ByteArray): SpeechEngineResult {
            playCalls++
            val completion = CompletableDeferred<SpeechEngineResult>()
            current.set(completion)
            if (playCalls == 1) firstStarted.complete(Unit)
            if (stopFinished.get()) return SpeechEngineResult.Completed
            return completion.await()
        }

        override fun stop() {
            stopCalls++
            if (stopCalls != 1) return
            overlapThread = startOverlap()
            check(overlapAttempted.await(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                "overlap playback did not start"
            }
            current.getAndSet(null)?.complete(SpeechEngineResult.Cancelled)
            stopFinished.set(true)
        }

        override fun release() = Unit

        fun markOverlapAttempted() {
            overlapAttempted.countDown()
        }

        fun forceUnblock() {
            stopFinished.set(true)
            current.getAndSet(null)?.complete(SpeechEngineResult.Cancelled)
        }

        fun joinOverlap() {
            overlapThread?.joinOrFail("overlap playback")
        }
    }

    private class StopCloseOverlapBackend : EncodedAudioBackend {
        lateinit var startClose: () -> Thread
        val releasedDuringFirstStop = AtomicBoolean(false)
        @Volatile
        var releaseCalls = 0
        private val firstStopInProgress = AtomicBoolean(false)
        private val closeAttempted = CountDownLatch(1)
        @Volatile
        private var closeThread: Thread? = null
        private var stopCalls = 0

        override suspend fun play(bytes: ByteArray): SpeechEngineResult = SpeechEngineResult.Completed

        override fun stop() {
            stopCalls++
            if (stopCalls != 1) return
            firstStopInProgress.set(true)
            try {
                closeThread = startClose()
                check(closeAttempted.await(HANDSHAKE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
                    "terminal close did not start"
                }
            } finally {
                firstStopInProgress.set(false)
            }
        }

        override fun release() {
            if (firstStopInProgress.get()) releasedDuringFirstStop.set(true)
            releaseCalls++
        }

        fun markCloseAttempted() {
            closeAttempted.countDown()
        }

        fun joinClose() {
            closeThread?.joinOrFail("terminal close")
        }
    }

    private class StopOnFirstDispatch(
        private val stopPlayer: suspend () -> Unit,
    ) : CoroutineDispatcher() {
        private val queued = mutableListOf<Runnable>()
        private var closed = false

        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
            queued += block
            if (!closed) {
                closed = true
                runBlocking { stopPlayer() }
            }
        }

        fun runQueued() {
            queued.toList().forEach(Runnable::run)
            queued.clear()
        }
    }
}

private fun Thread.joinOrFail(operation: String) {
    join(THREAD_TIMEOUT_MILLIS)
    if (isAlive) {
        interrupt()
        join(THREAD_TIMEOUT_MILLIS)
    }
    check(!isAlive) { "$operation thread did not terminate" }
}

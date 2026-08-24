package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.engine.SpeechEngineResult
import kotlinx.coroutines.CancellationException
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

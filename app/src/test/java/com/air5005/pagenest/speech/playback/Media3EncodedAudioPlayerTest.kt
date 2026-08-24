package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.engine.SpeechEngineResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
    fun `cancelling playback stops and releases backend`() = runTest {
        val backend = FakeBackend(waitForStop = true)
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val playback = async { player.playMp3(byteArrayOf(1)) }
        runCurrent()

        playback.cancel(CancellationException("user stopped"))
        runCurrent()

        assertTrue(playback.isCancelled)
        assertEquals(1, backend.stopCalls)
        assertEquals(1, backend.releaseCalls)
        player.close()
        assertEquals(1, backend.releaseCalls)
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
}

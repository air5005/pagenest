package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechEngineRouterTest {
    @Test
    fun `offline speaks unchanged segment without touching Azure or cache`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine()
        val cache = RecordingCache()
        val router = router(system, azure, cache)
        val request = request("offline-segment")

        val routed = router.speak(request, SpeechMode.OFFLINE)

        assertEquals(SpeechEngineResult.Completed, routed.result)
        assertEquals("system", routed.engineId)
        assertSame(request.segment, system.requests.single().segment)
        assertTrue(azure.synthesisRequests.isEmpty())
        assertEquals(0, cache.getCalls)
    }

    @Test
    fun `online cache hit plays cached bytes without synthesizing and never falls back`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(playResults = ArrayDeque(listOf(SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure))))
        val cache = RecordingCache(hit = byteArrayOf(7, 8))
        val router = router(system, azure, cache)

        val routed = router.speak(request("cached"), SpeechMode.ONLINE)

        assertEquals(SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure), routed.result)
        assertEquals("azure", routed.engineId)
        assertTrue(azure.synthesisRequests.isEmpty())
        assertTrue(system.requests.isEmpty())
        assertEquals(listOf(byteArrayOf(7, 8).toList()), azure.playedAudio.map(ByteArray::toList))
    }

    @Test
    fun `online failure is returned without retry or offline fallback`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(synthesisResults = ArrayDeque(listOf(OnlineSynthesisResult.Failed(SpeechError.ServiceUnavailable))))
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val routed = router.speak(request("online"), SpeechMode.ONLINE)

        assertEquals(SpeechEngineResult.Failed(SpeechError.ServiceUnavailable), routed.result)
        assertEquals(1, azure.synthesisRequests.size)
        assertTrue(system.requests.isEmpty())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `auto retries transient failures then falls back on the same segment`() = runTest {
        val segment = request("segment-a")
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(
            synthesisResults = ArrayDeque(
                listOf(
                    OnlineSynthesisResult.Failed(SpeechError.NetworkTimeout),
                    OnlineSynthesisResult.Failed(SpeechError.ServiceUnavailable),
                    OnlineSynthesisResult.Failed(SpeechError.RateLimited),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val routed = router.speak(segment, SpeechMode.AUTO)

        assertEquals(listOf(500L, 1500L), delays)
        assertEquals(3, azure.synthesisRequests.size)
        assertSame(segment.segment, system.requests.single().segment)
        assertTrue(routed.fellBack)
        assertEquals("system", routed.engineId)
    }

    @Test
    fun `auto authentication failure falls back immediately without delay`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(synthesisResults = ArrayDeque(listOf(OnlineSynthesisResult.Failed(SpeechError.InvalidCredentials))))
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val routed = router.speak(request("auth"), SpeechMode.AUTO)

        assertEquals(1, azure.synthesisRequests.size)
        assertTrue(delays.isEmpty())
        assertEquals(1, system.requests.size)
        assertTrue(routed.fellBack)
    }

    @Test
    fun `auto successful synthesis caches and plays audio without fallback`() = runTest {
        val system = RecordingSpeechEngine("system")
        val audio = byteArrayOf(3, 4, 5)
        val azure = RecordingOnlineEngine(synthesisResults = ArrayDeque(listOf(OnlineSynthesisResult.Audio(audio))))
        val cache = RecordingCache()
        val router = router(system, azure, cache)
        val request = request("online-success")

        val routed = router.speak(request, SpeechMode.AUTO)

        assertEquals(SpeechEngineResult.Completed, routed.result)
        assertEquals("azure", routed.engineId)
        assertFalse(routed.fellBack)
        assertEquals(listOf(byteArrayOf(3, 4, 5).toList()), cache.putAudio.map(ByteArray::toList))
        assertTrue(system.requests.isEmpty())
        assertTrue(audio.all { it == 0.toByte() })
    }

    @Test
    fun `cancelled result does not retry or fall back`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(synthesisResults = ArrayDeque(listOf(OnlineSynthesisResult.Cancelled)))
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val routed = router.speak(request("cancelled"), SpeechMode.AUTO)

        assertEquals(SpeechEngineResult.Cancelled, routed.result)
        assertEquals(1, azure.synthesisRequests.size)
        assertTrue(system.requests.isEmpty())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `cancellation exception propagates without retry or fallback`() = runTest {
        val marker = CancellationException("navigation")
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(synthesisFailure = marker)
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val thrown = runCatching { router.speak(request("cancelled"), SpeechMode.AUTO) }.exceptionOrNull()

        assertSame(marker, thrown)
        assertTrue(system.requests.isEmpty())
        assertTrue(delays.isEmpty())
    }

    @Test
    fun `auto success after transient retries never touches offline engine`() = runTest {
        val system = RecordingSpeechEngine("system")
        val azure = RecordingOnlineEngine(
            synthesisResults = ArrayDeque(
                listOf(
                    OnlineSynthesisResult.Failed(SpeechError.NetworkTimeout),
                    OnlineSynthesisResult.Failed(SpeechError.ServiceUnavailable),
                    OnlineSynthesisResult.Audio(byteArrayOf(9)),
                ),
            ),
        )
        val delays = mutableListOf<Long>()
        val router = router(system, azure, RecordingCache(), delays)

        val routed = router.speak(request("eventual-success"), SpeechMode.AUTO)
        advanceUntilIdle()

        assertEquals(listOf(500L, 1500L), delays)
        assertEquals(SpeechEngineResult.Completed, routed.result)
        assertTrue(system.requests.isEmpty())
    }

    private fun router(
        system: RecordingSpeechEngine,
        azure: RecordingOnlineEngine,
        cache: RecordingCache,
        delays: MutableList<Long> = mutableListOf(),
    ) = SpeechEngineRouter(
        systemEngine = system,
        onlineEngine = azure,
        cache = cache,
        retryPolicy = RetryPolicy(),
        nowMillis = { 42L },
        delayMillis = { delays += it },
    )

    private fun request(id: String) = SpeechRequest(
        generationId = 7,
        segment = SpeechSegment(
            id = id,
            position = SpeechPosition(11, 2, null, 3, 0),
            partIndex = 0,
            text = "private-$id",
            locator = Locator(text = "", progression = 0.25),
        ),
        localeTag = "zh-CN",
        voiceId = "zh-CN-XiaoxiaoNeural",
        rate = 1f,
        pitch = 1f,
    )
}

private class RecordingSpeechEngine(override val id: String) : SpeechEngine {
    val requests = mutableListOf<SpeechRequest>()
    override suspend fun voices(localeTag: String) = emptyList<SpeechVoice>()
    override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
        requests += request
        return SpeechEngineResult.Completed
    }
    override suspend fun stop() = Unit
    override fun close() = Unit
}

private class RecordingOnlineEngine(
    private val synthesisResults: ArrayDeque<OnlineSynthesisResult> = ArrayDeque(),
    private val playResults: ArrayDeque<SpeechEngineResult> = ArrayDeque(),
    private val synthesisFailure: CancellationException? = null,
) : OnlineSpeechEngine {
    override val id = "azure"
    val synthesisRequests = mutableListOf<SpeechRequest>()
    val playedAudio = mutableListOf<ByteArray>()

    override suspend fun voices(localeTag: String) = emptyList<SpeechVoice>()
    override suspend fun synthesize(request: SpeechRequest): OnlineSynthesisResult {
        synthesisRequests += request
        synthesisFailure?.let { throw it }
        return synthesisResults.removeFirstOrNull()
            ?: OnlineSynthesisResult.Failed(SpeechError.ServiceUnavailable)
    }
    override suspend fun playEncoded(audio: ByteArray): SpeechEngineResult {
        playedAudio += audio.copyOf()
        return playResults.removeFirstOrNull() ?: SpeechEngineResult.Completed
    }
    override suspend fun speak(request: SpeechRequest): SpeechEngineResult = error("router must use split online API")
    override suspend fun stop() = Unit
    override fun close() = Unit
}

private class RecordingCache(
    private val hit: ByteArray? = null,
) : SpeechAudioCache {
    var getCalls = 0
    val putAudio = mutableListOf<ByteArray>()

    override suspend fun get(request: SpeechRequest, nowMillis: Long): ByteArray? {
        getCalls++
        return hit?.copyOf()
    }

    override suspend fun put(request: SpeechRequest, audio: ByteArray, nowMillis: Long) {
        putAudio += audio.copyOf()
    }

    override suspend fun clear() = Unit
}

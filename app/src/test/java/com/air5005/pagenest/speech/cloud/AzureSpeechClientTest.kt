package com.air5005.pagenest.speech.cloud

import com.air5005.pagenest.speech.engine.AzureSpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.playback.EncodedAudioPlayer
import com.air5005.pagenest.speech.playback.EncodedAudioBackend
import com.air5005.pagenest.speech.playback.Media3EncodedAudioPlayer
import com.air5005.pagenest.speech.security.AzureCredentials
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import com.wxn.base.bean.Locator
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.request.HttpRequestData
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AzureSpeechClientTest {
    @Test
    fun `synthesis uses only the regional official host and required request contract`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            captured += request
            respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
        }
        val client = azureClient(engine)

        val result = client.synthesize(credentials(), request("甲<&乙"))

        assertArrayEquals(byteArrayOf(1, 2, 3), result.value)
        val sent = captured.single()
        assertEquals(
            "https://eastasia.tts.speech.microsoft.com/cognitiveservices/v1",
            sent.url.toString(),
        )
        assertEquals("credential-value", sent.headers["Ocp-Apim-Subscription-Key"])
        assertEquals("audio-24khz-48kbitrate-mono-mp3", sent.headers["X-Microsoft-OutputFormat"])
        assertEquals("YiNest", sent.headers[HttpHeaders.UserAgent])
        assertEquals("application/ssml+xml", sent.body.contentType?.withoutParameters().toString())
        val body = (sent.body as TextContent).text
        assertTrue(body.contains("甲&lt;&amp;乙"))
        assertFalse(body.contains("甲<&乙"))
    }

    @Test
    fun `voice list uses the official regional endpoint and parses documented fields`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val body = """[{"ShortName":"zh-CN-XiaoxiaoNeural","LocalName":"晓晓","Locale":"zh-CN"}]"""
        val engine = MockEngine { request ->
            captured += request
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = azureClient(engine)

        val result = client.voices(credentials())

        assertEquals(
            listOf(SpeechVoice("zh-CN-XiaoxiaoNeural", "晓晓", "zh-CN")),
            result.value,
        )
        assertEquals(
            "https://eastasia.tts.speech.microsoft.com/cognitiveservices/voices/list",
            captured.single().url.toString(),
        )
    }

    @Test
    fun `invalid region labels are rejected before a request can choose a host`() = runTest {
        val requests = AtomicInteger(0)
        val client = azureClient(MockEngine {
            requests.incrementAndGet()
            respondError(HttpStatusCode.InternalServerError)
        })
        val invalidRegions = listOf(
            "EASTASIA",
            "a",
            "eastasia.example.com",
            "eastasia/path",
            "east_asia",
            "a".repeat(33),
        )

        invalidRegions.forEach { region ->
            assertEquals(
                SpeechError.InvalidRegion,
                client.synthesize(AzureCredentials("credential-value", region), request("text")).error,
            )
        }
        assertEquals(0, requests.get())
    }

    @Test
    fun `authentication statuses are classified without consuming response content`() = runTest {
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
            val client = azureClient(MockEngine {
                respond("response-content", status, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            })

            val result = client.synthesize(credentials(), request("private-body"))

            assertEquals(SpeechError.InvalidCredentials, result.error)
            assertNull(result.value)
        }
    }

    @Test
    fun `quota status is classified without consuming response content`() = runTest {
        val client = azureClient(MockEngine { respond("quota-response", HttpStatusCode.PaymentRequired) })

        val result = client.synthesize(credentials(), request("text"))

        assertEquals(SpeechError.QuotaExceeded, result.error)
    }

    @Test
    fun `redirect is not followed and credential is sent only to the official host`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val client = azureClient(MockEngine { request ->
            requests += request
            if (requests.size == 1) {
                respond(
                    "redirect-body",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://collector.invalid/capture"),
                )
            } else {
                respond(byteArrayOf(9), HttpStatusCode.OK)
            }
        })

        val result = client.voices(credentials())

        assertEquals(SpeechError.ServiceUnavailable, result.error)
        assertEquals(1, requests.size)
        assertEquals("eastasia.tts.speech.microsoft.com", requests.single().url.host)
        assertEquals("credential-value", requests.single().headers["Ocp-Apim-Subscription-Key"])
    }

    @Test
    fun `requests carry ten second connect and thirty second request timeouts`() = runTest {
        var timeout: io.ktor.client.plugins.HttpTimeoutConfig? = null
        val client = azureClient(MockEngine { request ->
            timeout = request.getCapabilityOrNull(HttpTimeoutCapability)
            respond(byteArrayOf(1), HttpStatusCode.OK)
        })

        client.synthesize(credentials(), request("text"))

        assertEquals(10_000L, timeout?.connectTimeoutMillis)
        assertEquals(30_000L, timeout?.requestTimeoutMillis)
    }

    @Test
    fun `rate limit retry after is capped`() = runTest {
        val client = azureClient(MockEngine {
            respond(
                "",
                HttpStatusCode.TooManyRequests,
                headersOf(HttpHeaders.RetryAfter, "3600"),
            )
        })

        val result = client.synthesize(credentials(), request("text"))

        assertEquals(SpeechError.RateLimited, result.error)
        assertEquals(30_000L, result.retryAfterMillis)
    }

    @Test
    fun `server failures are classified without consuming their body`() = runTest {
        listOf(HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable).forEach { status ->
            val client = azureClient(MockEngine { respond("server-body", status) })

            val result = client.synthesize(credentials(), request("text"))

            assertEquals(SpeechError.ServiceUnavailable, result.error)
        }
    }

    @Test
    fun `non-success responses never enter the bounded body reader`() = runTest {
        listOf(
            HttpStatusCode.Found,
            HttpStatusCode.PaymentRequired,
            HttpStatusCode.Unauthorized,
            HttpStatusCode.TooManyRequests,
            HttpStatusCode.ServiceUnavailable,
        ).forEach { status ->
            val reader = CountingResponseReader()
            val client = AzureSpeechClient(
                engine = MockEngine { respond("private-error-content", status) },
                maxAudioBytes = AzureSpeechClient.DEFAULT_MAX_AUDIO_BYTES,
                maxVoiceListBytes = AzureSpeechClient.DEFAULT_MAX_VOICE_LIST_BYTES,
                responseReader = reader,
            )

            client.synthesize(credentials(), request("private-request-content"))

            assertEquals(0, reader.calls)
        }
    }

    @Test
    fun `read timeout and connection failure are classified as network timeout`() = runTest {
        listOf(
            MockEngine { throw SocketTimeoutException("read timed out") },
            MockEngine { throw IOException("connection refused") },
        ).forEach { engine ->
            val result = azureClient(engine).synthesize(credentials(), request("text"))
            assertEquals(SpeechError.NetworkTimeout, result.error)
        }
    }

    @Test
    fun `cancellation propagates as cancellation instead of a speech error`() = runTest {
        val marker = CancellationException("caller stopped")
        val client = azureClient(MockEngine { throw marker })

        val thrown = runCatching { client.synthesize(credentials(), request("text")) }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertEquals(marker.message, (thrown as CancellationException).message)
    }

    @Test
    fun `cancelling an in flight request stops it without a second request`() = runTest {
        val started = CompletableDeferred<Unit>()
        val requests = AtomicInteger(0)
        val client = azureClient(MockEngine {
            requests.incrementAndGet()
            started.complete(Unit)
            awaitCancellation()
        })
        val pending = async { client.synthesize(credentials(), request("text")) }
        started.await()

        pending.cancel(CancellationException("navigation changed"))
        val thrown = runCatching { pending.await() }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(1, requests.get())
    }

    @Test
    fun `oversized text is rejected before any network request`() = runTest {
        val requests = AtomicInteger(0)
        val client = azureClient(MockEngine {
            requests.incrementAndGet()
            respond(byteArrayOf(1), HttpStatusCode.OK)
        })

        val result = client.synthesize(credentials(), request("文".repeat(501)))

        assertEquals(SpeechError.NoExtractableText, result.error)
        assertEquals(0, requests.get())
    }

    @Test
    fun `audio response exceeding the private player bound is rejected`() = runTest {
        val client = AzureSpeechClient(
            MockEngine { respond(ByteArray(9), HttpStatusCode.OK) },
            maxAudioBytes = 8,
        )

        val result = client.synthesize(credentials(), request("text"))

        assertEquals(SpeechError.AudioDecodeFailure, result.error)
        assertNull(result.value)
    }

    @Test
    fun `response larger than eight KiB wipes every temporary byte buffer`() = runTest {
        val allocations = mutableListOf<ByteArray>()
        val allocator = ByteArrayAllocator { size ->
            ByteArray(size) { 0x5a }.also(allocations::add)
        }
        val payload = ByteArray(9_001) { index -> (index % 251 + 1).toByte() }
        val client = AzureSpeechClient(
            engine = MockEngine { respond(payload, HttpStatusCode.OK) },
            maxAudioBytes = 10_000,
            maxVoiceListBytes = AzureSpeechClient.DEFAULT_MAX_VOICE_LIST_BYTES,
            responseReader = BoundedAzureResponseReader(allocator),
        )

        val result = client.synthesize(credentials(), request("text"))

        assertArrayEquals(payload, result.value)
        assertEquals(listOf(10_000, 8_192), allocations.map(ByteArray::size))
        assertTrue(allocations.all { bytes -> bytes.all { it == 0.toByte() } })
    }

    @Test
    fun `voice response exceeding its bound is rejected`() = runTest {
        val client = AzureSpeechClient(
            MockEngine { respond(ByteArray(9), HttpStatusCode.OK) },
            maxVoiceListBytes = 8,
        )

        val result = client.voices(credentials())

        assertEquals(SpeechError.ServiceUnavailable, result.error)
        assertNull(result.value)
    }

    @Test
    fun `transport exception becomes a fixed typed error without escaping`() = runTest {
        val client = azureClient(MockEngine {
            throw IOException("credential-value private-body")
        })

        val result = client.synthesize(credentials(), request("private-body"))

        assertEquals(SpeechError.NetworkTimeout, result.error)
    }

    @Test
    fun `engine cancellation stops encoded playback and rethrows the original cancellation`() = runTest {
        val marker = CancellationException("new navigation")
        val player = SuspendingPlayer(marker)
        val service = object : AzureSpeechService {
            override suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>> =
                AzureResult(value = emptyList())

            override suspend fun synthesize(
                credentials: AzureCredentials,
                request: SpeechRequest,
            ): AzureResult<ByteArray> = AzureResult(value = byteArrayOf(1, 2, 3))
        }
        val engine = AzureSpeechEngine(FixedCredentialStore(credentials()), service, player)
        val thrown = runCatching { engine.speak(request("text")) }.exceptionOrNull()

        assertSame(marker, thrown)
        assertTrue(player.stopped)
    }

    @Test
    fun `engine preserves cancellation when stopping the player fails`() = runTest {
        val marker = CancellationException("new navigation")
        val player = SuspendingPlayer(marker, stopFailure = IllegalStateException("stop failed"))
        val engine = AzureSpeechEngine(
            FixedCredentialStore(credentials()),
            FixedAzureService(byteArrayOf(1)),
            player,
        )

        val thrown = runCatching { engine.speak(request("text")) }.exceptionOrNull()

        assertSame(marker, thrown)
        assertTrue(player.stopped)
    }

    @Test
    fun `engine stop cancels current audio and the next synthesis can play`() = runTest {
        val backend = FirstAudioWaitsBackend()
        val player = Media3EncodedAudioPlayer(maxAudioBytes = 16, backend = backend)
        val service = FixedAzureService(byteArrayOf(1, 2, 3))
        val engine = AzureSpeechEngine(
            FixedCredentialStore(credentials()),
            service,
            player,
        )
        val first = async { engine.speak(request("first")) }
        runCurrent()
        backend.firstStarted.await()

        engine.stop()

        assertEquals(SpeechEngineResult.Cancelled, first.await())
        assertEquals(SpeechEngineResult.Completed, engine.speak(request("second")))
        assertEquals(0, backend.releaseCalls)
        engine.close()
        engine.close()
        assertEquals(1, backend.releaseCalls)
        assertEquals(1, service.closeCalls)
        assertTrue(service.lastReturnedAudio!!.all { it == 0.toByte() })
    }

    @Test
    fun `engine terminal close still closes service when player close fails`() {
        val player = CloseFailurePlayer()
        val service = FixedAzureService(byteArrayOf(1))
        val engine = AzureSpeechEngine(FixedCredentialStore(credentials()), service, player)

        assertThrows(IllegalStateException::class.java) { engine.close() }
        engine.close()

        assertEquals(1, player.closeCalls)
        assertEquals(1, service.closeCalls)
    }

    @Test
    fun `engine close preserves player failure and suppresses service failure`() {
        val playerFailure = IllegalStateException("player close failed")
        val serviceFailure = IllegalStateException("service close failed")
        val player = CloseFailurePlayer(playerFailure)
        val service = FixedAzureService(byteArrayOf(1), closeFailure = serviceFailure)
        val engine = AzureSpeechEngine(FixedCredentialStore(credentials()), service, player)

        val thrown = assertThrows(IllegalStateException::class.java) { engine.close() }

        assertSame(playerFailure, thrown)
        assertEquals(listOf(serviceFailure), thrown.suppressed.toList())
        assertEquals(1, player.closeCalls)
        assertEquals(1, service.closeCalls)
    }

    @Test
    fun `engine close preserves a failure shared by player and service`() {
        val sharedFailure = IllegalStateException("shared close failed")
        val player = CloseFailurePlayer(sharedFailure)
        val service = FixedAzureService(byteArrayOf(1), closeFailure = sharedFailure)
        val engine = AzureSpeechEngine(FixedCredentialStore(credentials()), service, player)

        val thrown = assertThrows(IllegalStateException::class.java) { engine.close() }

        assertSame(sharedFailure, thrown)
        assertTrue(thrown.suppressed.isEmpty())
        assertEquals(1, player.closeCalls)
        assertEquals(1, service.closeCalls)
    }

    private fun azureClient(engine: MockEngine): AzureSpeechClient =
        AzureSpeechClient(engine)

    private fun credentials() = AzureCredentials("credential-value", "eastasia")

    private fun request(text: String) = SpeechRequest(
        generationId = 7,
        segment = SpeechSegment(
            id = "segment",
            position = SpeechPosition(11, 2, null, 3, 0),
            partIndex = 0,
            text = text,
            locator = Locator(text = "", progression = 0.25),
        ),
        localeTag = "zh-CN",
        voiceId = "zh-CN-XiaoxiaoNeural",
        rate = 1f,
        pitch = 1f,
    )
}

private class FixedCredentialStore(
    private val credentials: AzureCredentials?,
) : SpeechCredentialStore {
    override suspend fun saveAzure(key: String, region: String) = Unit
    override suspend fun loadAzure(): AzureCredentials? = credentials
    override suspend fun clearAzure() = Unit
}

private class SuspendingPlayer(
    private val cancellation: CancellationException,
    private val stopFailure: RuntimeException? = null,
) : EncodedAudioPlayer {
    var stopped = false

    override suspend fun playMp3(bytes: ByteArray): SpeechEngineResult {
        throw cancellation
    }

    override suspend fun stop() {
        stopped = true
        stopFailure?.let { throw it }
    }

    override fun close() = Unit
}

private class FixedAzureService(
    private val audio: ByteArray,
    private val closeFailure: RuntimeException? = null,
) : AzureSpeechService {
    var closeCalls = 0
    var lastReturnedAudio: ByteArray? = null

    override suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>> =
        AzureResult(value = emptyList())

    override suspend fun synthesize(
        credentials: AzureCredentials,
        request: SpeechRequest,
    ): AzureResult<ByteArray> {
        val returned = audio.copyOf()
        lastReturnedAudio = returned
        return AzureResult(value = returned)
    }

    override fun close() {
        closeCalls++
        closeFailure?.let { throw it }
    }
}

private class FirstAudioWaitsBackend : EncodedAudioBackend {
    var playCalls = 0
    var releaseCalls = 0
    val firstStarted = CompletableDeferred<Unit>()

    override suspend fun play(bytes: ByteArray): SpeechEngineResult {
        playCalls++
        if (playCalls == 1) {
            firstStarted.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        return SpeechEngineResult.Completed
    }

    override fun stop() = Unit
    override fun release() { releaseCalls++ }
}

private class CountingResponseReader : AzureResponseReader {
    var calls = 0

    override suspend fun read(response: HttpResponse, limit: Int): ByteArray? {
        calls++
        return byteArrayOf()
    }
}

private class CloseFailurePlayer(
    private val closeFailure: RuntimeException = IllegalStateException("player close failed"),
) : EncodedAudioPlayer {
    var closeCalls = 0

    override suspend fun playMp3(bytes: ByteArray): SpeechEngineResult = SpeechEngineResult.Completed
    override suspend fun stop() = Unit
    override fun close() {
        closeCalls++
        throw closeFailure
    }
}

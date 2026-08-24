package com.air5005.pagenest.speech.cloud

import com.air5005.pagenest.speech.engine.AzureSpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.playback.EncodedAudioPlayer
import com.air5005.pagenest.speech.security.AzureCredentials
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import com.wxn.base.bean.Locator
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

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
        assertEquals("PageNest", sent.headers[HttpHeaders.UserAgent])
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
    fun `authentication statuses are classified without exposing response content`() = runTest {
        listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden).forEach { status ->
            val client = azureClient(MockEngine {
                respond("response-content", status, headersOf(HttpHeaders.ContentType, ContentType.Text.Plain.toString()))
            })

            val result = client.synthesize(credentials(), request("private-body"))

            assertEquals(SpeechError.InvalidCredentials, result.error)
            assertNull(result.value)
            assertFalse(result.toString().contains("response-content"))
            assertFalse(result.toString().contains("private-body"))
            assertFalse(result.toString().contains("credential-value"))
        }
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
    fun `server failures are classified without reading their body`() = runTest {
        listOf(HttpStatusCode.BadGateway, HttpStatusCode.ServiceUnavailable).forEach { status ->
            val client = azureClient(MockEngine { respond("server-body", status) })

            val result = client.synthesize(credentials(), request("text"))

            assertEquals(SpeechError.ServiceUnavailable, result.error)
            assertFalse(result.toString().contains("server-body"))
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
            HttpClient(MockEngine { respond(ByteArray(9), HttpStatusCode.OK) }),
            maxAudioBytes = 8,
        )

        val result = client.synthesize(credentials(), request("text"))

        assertEquals(SpeechError.AudioDecodeFailure, result.error)
        assertNull(result.value)
    }

    @Test
    fun `transport exception text cannot leak credentials or request body through the result`() = runTest {
        val client = azureClient(MockEngine {
            throw IOException("credential-value private-body")
        })

        val result = client.synthesize(credentials(), request("private-body"))

        assertEquals(SpeechError.NetworkTimeout, result.error)
        assertFalse(result.toString().contains("credential-value"))
        assertFalse(result.toString().contains("private-body"))
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

    private fun azureClient(engine: MockEngine): AzureSpeechClient =
        AzureSpeechClient(HttpClient(engine))

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
) : EncodedAudioPlayer {
    var stopped = false

    override suspend fun playMp3(bytes: ByteArray): SpeechEngineResult {
        throw cancellation
    }

    override suspend fun stop() {
        stopped = true
    }

    override fun close() = Unit
}

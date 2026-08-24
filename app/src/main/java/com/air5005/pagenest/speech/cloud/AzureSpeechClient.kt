package com.air5005.pagenest.speech.cloud

import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.security.AzureCredentials
import com.air5005.pagenest.speech.security.AzureRegionValidator
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class AzureResult<T>(
    val value: T? = null,
    val error: SpeechError? = null,
    val retryAfterMillis: Long? = null,
)

interface AzureSpeechService : AutoCloseable {
    suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>>
    suspend fun synthesize(credentials: AzureCredentials, request: SpeechRequest): AzureResult<ByteArray>
    override fun close() = Unit
}

internal fun interface AzureResponseReader {
    suspend fun read(response: HttpResponse, limit: Int): ByteArray?
}

private object BoundedAzureResponseReader : AzureResponseReader {
    override suspend fun read(response: HttpResponse, limit: Int): ByteArray? {
        val channel = response.bodyAsChannel()
        val output = WipeableByteArrayOutputStream(minOf(limit, READ_BUFFER_BYTES))
        val buffer = ByteArray(READ_BUFFER_BYTES)
        try {
            while (true) {
                val read = channel.readAvailable(buffer)
                if (read < 0) break
                if (read == 0) {
                    yield()
                    continue
                }
                if (output.size() > limit - read) {
                    channel.cancel(null)
                    return null
                }
                output.write(buffer, 0, read)
            }
            return output.toByteArray()
        } finally {
            buffer.fill(0)
            output.wipe()
        }
    }

    private const val READ_BUFFER_BYTES = 8 * 1024
}

private class WipeableByteArrayOutputStream(initialSize: Int) : ByteArrayOutputStream(initialSize) {
    fun wipe() {
        buf.fill(0)
        reset()
    }
}

class AzureSpeechClient private constructor(
    private val httpClient: HttpClient,
    private val maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
    private val maxVoiceListBytes: Int = DEFAULT_MAX_VOICE_LIST_BYTES,
    private val responseReader: AzureResponseReader = BoundedAzureResponseReader,
) : AzureSpeechService {
    constructor(
        engine: HttpClientEngine,
        maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES,
        maxVoiceListBytes: Int = DEFAULT_MAX_VOICE_LIST_BYTES,
    ) : this(
        httpClient = HttpClient(engine) { followRedirects = false },
        maxAudioBytes = maxAudioBytes,
        maxVoiceListBytes = maxVoiceListBytes,
    )

    internal constructor(
        engine: HttpClientEngine,
        maxAudioBytes: Int,
        maxVoiceListBytes: Int,
        responseReader: AzureResponseReader,
    ) : this(
        httpClient = HttpClient(engine) { followRedirects = false },
        maxAudioBytes = maxAudioBytes,
        maxVoiceListBytes = maxVoiceListBytes,
        responseReader = responseReader,
    )
    override suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>> {
        val url = endpoint(credentials.region, VOICES_PATH)
            ?: return AzureResult(error = SpeechError.InvalidRegion)
        return execute {
            val response = httpClient.get(url) {
                applyCommonHeaders(credentials.key)
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
            if (response.status != HttpStatusCode.OK) return@execute classify(response)
            val bytes = responseReader.read(response, maxVoiceListBytes)
                ?: return@execute AzureResult(error = SpeechError.ServiceUnavailable)
            parseVoices(bytes)
        }
    }

    override suspend fun synthesize(
        credentials: AzureCredentials,
        request: SpeechRequest,
    ): AzureResult<ByteArray> {
        val url = endpoint(credentials.region, SYNTHESIS_PATH)
            ?: return AzureResult(error = SpeechError.InvalidRegion)
        val ssml = try {
            AzureSsmlBuilder.build(
                text = request.segment.text,
                localeTag = request.localeTag,
                voiceId = request.voiceId,
                rate = request.rate,
                pitch = request.pitch,
            )
        } catch (_: IllegalArgumentException) {
            return AzureResult(error = SpeechError.NoExtractableText)
        }
        return execute {
            val response = httpClient.post(url) {
                applyCommonHeaders(credentials.key)
                contentType(ContentType.parse(SSML_CONTENT_TYPE))
                header(OUTPUT_FORMAT_HEADER, OUTPUT_FORMAT)
                header(HttpHeaders.Accept, AUDIO_CONTENT_TYPE)
                setBody(ssml)
                timeout {
                    connectTimeoutMillis = CONNECT_TIMEOUT_MILLIS
                    requestTimeoutMillis = REQUEST_TIMEOUT_MILLIS
                }
            }
            if (response.status != HttpStatusCode.OK) return@execute classify(response)
            val audio = responseReader.read(response, maxAudioBytes)
                ?: return@execute AzureResult(error = SpeechError.AudioDecodeFailure)
            if (audio.isEmpty()) AzureResult(error = SpeechError.AudioDecodeFailure)
            else AzureResult(value = audio)
        }
    }

    override fun close() {
        httpClient.close()
    }

    private suspend fun <T> execute(block: suspend () -> AzureResult<T>): AzureResult<T> = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: HttpRequestTimeoutException) {
        AzureResult(error = SpeechError.NetworkTimeout)
    } catch (_: SocketTimeoutException) {
        AzureResult(error = SpeechError.NetworkTimeout)
    } catch (_: IOException) {
        AzureResult(error = SpeechError.NetworkTimeout)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.applyCommonHeaders(key: String) {
        header(SUBSCRIPTION_KEY_HEADER, key)
        header(HttpHeaders.UserAgent, USER_AGENT)
    }

    private suspend fun <T> classify(response: HttpResponse): AzureResult<T> {
        response.bodyAsChannel().cancel(null)
        return when (response.status.value) {
            401, 403 -> AzureResult(error = SpeechError.InvalidCredentials)
            402 -> AzureResult(error = SpeechError.QuotaExceeded)
            429 -> AzureResult(
                error = SpeechError.RateLimited,
                retryAfterMillis = parseRetryAfter(response.headers[HttpHeaders.RetryAfter]),
            )
            in 500..599 -> AzureResult(error = SpeechError.ServiceUnavailable)
            else -> AzureResult(error = SpeechError.ServiceUnavailable)
        }
    }

    private fun parseVoices(bytes: ByteArray): AzureResult<List<SpeechVoice>> = try {
        val root = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)) as? JsonArray
            ?: return AzureResult(error = SpeechError.ServiceUnavailable)
        val voices = root.mapNotNull { element ->
            val item = element.jsonObject
            val id = item["ShortName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val name = item["LocalName"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val locale = item["Locale"]?.jsonPrimitive?.content ?: return@mapNotNull null
            SpeechVoice(id, name, locale)
        }
        AzureResult(value = voices)
    } catch (_: Exception) {
        AzureResult(error = SpeechError.ServiceUnavailable)
    } finally {
        bytes.fill(0)
    }

    private fun endpoint(region: String, path: String): String? {
        if (!AzureRegionValidator.isValid(region)) return null
        return "https://$region.tts.speech.microsoft.com$path"
    }

    private fun parseRetryAfter(value: String?): Long? {
        val seconds = value?.toLongOrNull()?.coerceAtLeast(0) ?: return null
        return seconds.coerceAtMost(MAX_RETRY_AFTER_SECONDS) * 1_000L
    }

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 5 * 1024 * 1024
        const val DEFAULT_MAX_VOICE_LIST_BYTES = 1024 * 1024
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val REQUEST_TIMEOUT_MILLIS = 30_000L
        const val MAX_RETRY_AFTER_SECONDS = 30L

        private const val SYNTHESIS_PATH = "/cognitiveservices/v1"
        private const val VOICES_PATH = "/cognitiveservices/voices/list"
        private const val SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key"
        private const val OUTPUT_FORMAT_HEADER = "X-Microsoft-OutputFormat"
        private const val OUTPUT_FORMAT = "audio-24khz-48kbitrate-mono-mp3"
        private const val SSML_CONTENT_TYPE = "application/ssml+xml"
        private const val AUDIO_CONTENT_TYPE = "audio/mpeg"
        private const val USER_AGENT = "PageNest"
        fun production(): AzureSpeechClient = AzureSpeechClient(
            HttpClient(OkHttp) { followRedirects = false },
        )
    }
}

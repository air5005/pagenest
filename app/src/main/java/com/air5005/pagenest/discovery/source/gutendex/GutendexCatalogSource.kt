package com.air5005.pagenest.discovery.source.gutendex

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.CatalogSourceException
import com.air5005.pagenest.discovery.model.CatalogSourceFailure
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.encodedPath
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class GutendexCatalogSource(
    private val client: HttpClient,
    baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = DEFAULT_JSON,
) : OnlineCatalogSource {
    override val id: String = SOURCE_ID

    private val trustedBase = trustedBaseUrl(baseUrl)

    override suspend fun browse(request: CatalogRequest): CatalogPage {
        val requestUrl = request.pageToken?.let(::trustedPageUrl) ?: browseUrl(request)
        val bytes = fetch(requestUrl)
        val dto = decode<GutendexPageDto>(bytes)
        val next = dto.next?.let(::trustedPageUrl)?.toString()
        return CatalogPage(
            books = dto.results.mapIndexed(::mapBook),
            nextPageToken = next,
        )
    }

    override suspend fun details(reference: SourceReference): SourceBookDetails? {
        if (reference.sourceId != id || !reference.sourceBookId.all(Char::isDigit)) return null
        val url = URLBuilder(trustedBase).apply {
            encodedPath = "/books/${reference.sourceBookId}"
            parameters.clear()
        }.build()
        val book = decode<GutendexBookDto>(fetch(url))
        return SourceBookDetails(mapBook(0, book))
    }

    private fun browseUrl(request: CatalogRequest): Url = URLBuilder(trustedBase).apply {
        encodedPath = "/books"
        parameters.clear()
        when (request.language) {
            CatalogLanguage.ALL -> Unit
            CatalogLanguage.ZH -> parameters.append("languages", "zh")
            CatalogLanguage.EN -> parameters.append("languages", "en")
        }
        when (request.kind) {
            CatalogKind.RECOMMENDED,
            CatalogKind.POPULAR,
            -> parameters.append("sort", "popular")
            CatalogKind.LATEST -> parameters.append("sort", "descending")
            CatalogKind.SUBJECT -> request.subject
                ?.takeIf(String::isNotBlank)
                ?.let { parameters.append("topic", it) }
            CatalogKind.SEARCH -> request.query
                ?.takeIf(String::isNotBlank)
                ?.let { parameters.append("search", it) }
        }
        parameters.append("page", "1")
    }.build()

    private suspend fun fetch(url: Url): ByteArray = try {
        val response = client.get(url)
        if (response.status != HttpStatusCode.OK) {
            response.bodyAsChannel().cancel(null)
            throw CatalogSourceException(CatalogSourceFailure.HTTP)
        }
        readBounded(response) ?: throw CatalogSourceException(CatalogSourceFailure.RESPONSE_TOO_LARGE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: CatalogSourceException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw CatalogSourceException(CatalogSourceFailure.TIMEOUT)
    } catch (_: SocketTimeoutException) {
        throw CatalogSourceException(CatalogSourceFailure.TIMEOUT)
    } catch (_: IOException) {
        throw CatalogSourceException(CatalogSourceFailure.NETWORK)
    }

    private suspend inline fun <reified T> decode(bytes: ByteArray): T = try {
        json.decodeFromString<T>(bytes.toString(Charsets.UTF_8))
    } catch (_: SerializationException) {
        throw CatalogSourceException(CatalogSourceFailure.MALFORMED)
    }

    private suspend fun readBounded(response: HttpResponse): ByteArray? {
        val channel = response.bodyAsChannel()
        val accumulated = ByteArray(MAX_RESPONSE_BYTES)
        val buffer = ByteArray(READ_BUFFER_BYTES)
        var size = 0
        while (true) {
            val read = channel.readAvailable(buffer)
            if (read < 0) break
            if (read == 0) {
                yield()
                continue
            }
            if (size > MAX_RESPONSE_BYTES - read) {
                channel.cancel(null)
                return null
            }
            buffer.copyInto(accumulated, destinationOffset = size, endIndex = read)
            size += read
        }
        return accumulated.copyOf(size)
    }

    private fun trustedPageUrl(value: String): Url {
        val candidate = try {
            Url(value)
        } catch (_: IllegalArgumentException) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        if (
            candidate.protocol != URLProtocol.HTTPS ||
            candidate.host != trustedBase.host ||
            candidate.port != trustedBase.port
        ) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        return candidate
    }

    private fun mapBook(index: Int, dto: GutendexBookDto): OnlineBook {
        val isPublicDomain = dto.copyright == false
        val acquisitions = if (isPublicDomain) mapAcquisitions(dto.formats) else emptyList()
        return OnlineBook(
            stableKey = "gutenberg:${dto.id}",
            title = dto.title,
            authors = dto.authors.map { it.name },
            summary = dto.summaries.firstOrNull(),
            languages = dto.languages,
            subjects = dto.subjects,
            coverUrl = dto.formats.entries
                .firstOrNull { (type, url) -> type.startsWith("image/") && url.isSecureUrl() }
                ?.value,
            sourceRank = index + 1,
            popularity = dto.downloadCount.toDouble(),
            catalogUpdatedAtEpochMillis = null,
            rightsStatus = if (isPublicDomain) RightsStatus.PUBLIC_DOMAIN else RightsStatus.UNKNOWN,
            sourceReferences = listOf(SourceReference(id, dto.id.toString())),
            acquisitions = acquisitions,
        )
    }

    private fun mapAcquisitions(formats: Map<String, String>): List<OnlineAcquisition> = formats
        .mapNotNull { (mediaType, url) ->
            if (!url.isSecureUrl()) return@mapNotNull null
            val (format, access, priority) = when {
                mediaType.startsWith("application/epub+zip") ->
                    Triple(OnlineBookFormat.EPUB, AcquisitionAccess.FREE_FULL, EPUB_PRIORITY)
                mediaType.startsWith("text/plain") ->
                    Triple(OnlineBookFormat.TXT, AcquisitionAccess.FREE_FULL, TXT_PRIORITY)
                mediaType.startsWith("text/html") ->
                    Triple(OnlineBookFormat.HTML, AcquisitionAccess.EXTERNAL, HTML_PRIORITY)
                else -> return@mapNotNull null
            }
            OnlineAcquisition(id, format, url, access, priority)
        }

    private fun String.isSecureUrl(): Boolean = try {
        Url(this).protocol == URLProtocol.HTTPS
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun trustedBaseUrl(value: String): Url {
        val url = try {
            Url(value)
        } catch (_: IllegalArgumentException) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        if (url.protocol != URLProtocol.HTTPS || url.host.isBlank()) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        return url
    }

    companion object {
        const val MAX_RESPONSE_BYTES: Int = 2 * 1024 * 1024

        private const val SOURCE_ID = "gutendex"
        private const val DEFAULT_BASE_URL = "https://gutendex.com"
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val EPUB_PRIORITY = 20
        private const val TXT_PRIORITY = 30
        private const val HTML_PRIORITY = 90

        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
    }
}

package com.air5005.pagenest.discovery.openlibrary

import com.air5005.pagenest.discovery.aggregate.OnlineBookFingerprint
import com.air5005.pagenest.discovery.model.OnlineBook
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
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

fun interface OnlineBookEnricher {
    suspend fun enrich(book: OnlineBook): OpenLibraryMetadata?
}

enum class OpenLibraryFailure {
    HTTP,
    NETWORK,
    TIMEOUT,
    RESPONSE_TOO_LARGE,
    MALFORMED,
    UNTRUSTED_URL,
}

class OpenLibraryException(
    val failure: OpenLibraryFailure,
) : RuntimeException(failure.name)

class OpenLibraryEnricher(
    private val client: HttpClient,
    private val cache: OpenLibraryMetadataCache,
    private val limiter: OpenLibraryRateLimiter,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    baseUrl: String = DEFAULT_BASE_URL,
    private val json: Json = DEFAULT_JSON,
) : OnlineBookEnricher {
    private val trustedBase = validateBase(baseUrl)

    override suspend fun enrich(book: OnlineBook): OpenLibraryMetadata? {
        val author = book.authors.firstOrNull()?.takeIf(String::isNotBlank) ?: return null
        if (book.title.isBlank()) return null
        val key = cacheKey(book)
        val cached = safeCacheGet(key)
        if (cached != null && isFresh(cached)) return cached.metadata

        val metadata = limiter.run { search(book.title, author) }
        safeCachePut(key, CachedOpenLibraryMetadata(nowEpochMillis(), metadata))
        return metadata
    }

    private suspend fun search(title: String, author: String): OpenLibraryMetadata? {
        val url = URLBuilder(trustedBase).apply {
            encodedPath = "/search.json"
            parameters.clear()
            parameters.append("title", title)
            parameters.append("author", author)
            parameters.append("fields", REQUEST_FIELDS)
            parameters.append("limit", SEARCH_LIMIT.toString())
        }.build()
        val response = fetch(url)
        val dto = try {
            json.decodeFromString<OpenLibrarySearchDto>(response.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw OpenLibraryException(OpenLibraryFailure.MALFORMED)
        }
        val normalizedTitle = OnlineBookFingerprint.normalize(title)
        val normalizedAuthor = OnlineBookFingerprint.normalize(author)
        val match = dto.docs.firstOrNull { work ->
            OnlineBookFingerprint.normalize(work.title) == normalizedTitle &&
                work.authorNames.any { OnlineBookFingerprint.normalize(it) == normalizedAuthor }
        } ?: return null
        val workId = WORK_ID.matchEntire(match.key)?.groupValues?.get(1) ?: return null
        return OpenLibraryMetadata(
            workId = workId,
            coverUrl = match.coverId?.takeIf { it > 0 }?.let {
                "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false"
            },
            firstPublishYear = match.firstPublishYear,
            editionCount = match.editionCount,
            publicScan = match.publicScan,
            ebookAccess = ebookAccess(match.ebookAccess),
            sourcePageUrl = "https://openlibrary.org/works/$workId",
        )
    }

    private suspend fun fetch(url: Url): ByteArray = try {
        val response = client.get(url) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, USER_AGENT)
        }
        if (response.status != HttpStatusCode.OK) {
            response.bodyAsChannel().cancel(null)
            throw OpenLibraryException(OpenLibraryFailure.HTTP)
        }
        readBounded(response) ?: throw OpenLibraryException(OpenLibraryFailure.RESPONSE_TOO_LARGE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (known: OpenLibraryException) {
        throw known
    } catch (_: HttpRequestTimeoutException) {
        throw OpenLibraryException(OpenLibraryFailure.TIMEOUT)
    } catch (_: SocketTimeoutException) {
        throw OpenLibraryException(OpenLibraryFailure.TIMEOUT)
    } catch (_: IOException) {
        throw OpenLibraryException(OpenLibraryFailure.NETWORK)
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

    private suspend fun safeCacheGet(key: String): CachedOpenLibraryMetadata? = try {
        cache.get(key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun safeCachePut(key: String, value: CachedOpenLibraryMetadata) {
        try {
            cache.put(key, value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Enrichment remains usable if its optional cache is unavailable.
        }
    }

    private fun isFresh(value: CachedOpenLibraryMetadata): Boolean {
        val age = nowEpochMillis() - value.cachedAtEpochMillis
        return age >= 0 && age <= METADATA_TTL_MILLIS
    }

    private fun ebookAccess(value: String?): OpenLibraryEbookAccess = when (value?.lowercase()) {
        "public" -> OpenLibraryEbookAccess.PUBLIC
        "borrowable" -> OpenLibraryEbookAccess.BORROWABLE
        "preview" -> OpenLibraryEbookAccess.PREVIEW
        "no_ebook", "none" -> OpenLibraryEbookAccess.NONE
        else -> OpenLibraryEbookAccess.UNKNOWN
    }

    private fun validateBase(value: String): Url {
        val url = try {
            Url(value)
        } catch (_: IllegalArgumentException) {
            throw OpenLibraryException(OpenLibraryFailure.UNTRUSTED_URL)
        }
        if (url.protocol != URLProtocol.HTTPS || url.host != OPEN_LIBRARY_HOST) {
            throw OpenLibraryException(OpenLibraryFailure.UNTRUSTED_URL)
        }
        return url
    }

    companion object {
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val METADATA_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        const val REQUEST_FIELDS =
            "key,title,author_name,cover_i,first_publish_year,edition_count,language,has_fulltext,public_scan_b,ebook_access"

        private const val DEFAULT_BASE_URL = "https://openlibrary.org/search.json"
        private const val OPEN_LIBRARY_HOST = "openlibrary.org"
        private const val SEARCH_LIMIT = 5
        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val USER_AGENT = "YiNest/1.10 (+https://github.com/air5005/pagenest)"
        private val WORK_ID = Regex("/works/(OL\\d+W)")
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        fun cacheKey(book: OnlineBook): String = buildString {
            append(OnlineBookFingerprint.normalize(book.title))
            append('|')
            append(book.authors.firstOrNull()?.let(OnlineBookFingerprint::normalize).orEmpty())
        }
    }
}

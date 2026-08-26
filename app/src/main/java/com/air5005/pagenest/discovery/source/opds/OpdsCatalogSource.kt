package com.air5005.pagenest.discovery.source.opds

import com.air5005.pagenest.discovery.model.AcquisitionAccess
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
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.utils.io.readAvailable
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield

data class OpdsSourceConfig(
    val id: String,
    val baseUrl: String,
    val allowedHosts: Set<String>,
    val rightsStatus: RightsStatus,
    val epubPriority: Int,
    val requestPathMapper: (CatalogRequest) -> String,
    val sourceBookId: (ParsedOpdsEntry) -> String?,
    val stableKey: (ParsedOpdsEntry, String) -> String,
    val derivedAcquisitions: (ParsedOpdsEntry, String) -> List<OnlineAcquisition> = { _, _ -> emptyList() },
)

class OpdsCatalogSource(
    private val client: HttpClient,
    private val config: OpdsSourceConfig,
    private val parser: OpdsFeedParser = OpdsFeedParser(),
) : OnlineCatalogSource {
    override val id: String = config.id

    private val trustedHosts = config.allowedHosts.map(String::lowercase).toSet()
    private val trustedBase = trustedUrl(config.baseUrl)

    init {
        require(trustedBase.host.lowercase() in trustedHosts) { "OPDS base host is not allowed" }
    }

    override suspend fun browse(request: CatalogRequest): CatalogPage {
        val url = request.pageToken?.let(::trustedUrl)
            ?: trustedUrl(config.requestPathMapper(request))
        val feed = try {
            parser.parse(fetch(url).toString(Charsets.UTF_8))
        } catch (_: OpdsParseException) {
            throw CatalogSourceException(CatalogSourceFailure.MALFORMED)
        }
        val next = feed.nextUrl?.let(::trustedUrl)?.toString()
        val books = feed.entries
            .asSequence()
            .filter { entry -> request.language.accepts(entry.languages) }
            .mapIndexedNotNull(::mapBook)
            .take(request.pageSize.coerceAtLeast(0))
            .toList()
        return CatalogPage(books, next)
    }

    override suspend fun details(reference: SourceReference): SourceBookDetails? = null

    private suspend fun fetch(url: Url): ByteArray = try {
        val response = client.get(url) {
            header(HttpHeaders.Accept, OPDS_ACCEPT)
        }
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

    private fun mapBook(index: Int, entry: ParsedOpdsEntry): OnlineBook? {
        val sourceBookId = config.sourceBookId(entry) ?: return null
        return OnlineBook(
            stableKey = config.stableKey(entry, sourceBookId),
            title = entry.title,
            authors = entry.authors,
            summary = entry.summary,
            languages = entry.languages,
            subjects = entry.subjects,
            coverUrl = entry.coverUrl?.takeIf(::isTrustedHttps),
            sourceRank = index + 1,
            popularity = null,
            catalogUpdatedAtEpochMillis = entry.updated?.let(::instantEpochOrNull),
            rightsStatus = config.rightsStatus,
            sourceReferences = listOf(SourceReference(id, sourceBookId)),
            acquisitions = (entry.acquisitions.mapNotNull(::mapAcquisition) +
                config.derivedAcquisitions(entry, sourceBookId))
                .distinctBy { it.url },
        )
    }

    private fun mapAcquisition(link: ParsedOpdsLink): OnlineAcquisition? {
        if (!isTrustedHttps(link.href)) return null
        val (format, access, priority) = when {
            link.type.startsWith("application/epub+zip") ->
                Triple(OnlineBookFormat.EPUB, AcquisitionAccess.FREE_FULL, config.epubPriority)
            link.type.startsWith("text/plain") ->
                Triple(OnlineBookFormat.TXT, AcquisitionAccess.FREE_FULL, TXT_PRIORITY)
            link.type.startsWith("text/html") ->
                Triple(OnlineBookFormat.HTML, AcquisitionAccess.EXTERNAL, HTML_PRIORITY)
            else -> return null
        }
        return OnlineAcquisition(id, format, link.href, access, priority)
    }

    private fun trustedUrl(value: String): Url {
        val url = try {
            Url(value)
        } catch (_: IllegalArgumentException) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        if (url.protocol != URLProtocol.HTTPS || url.host.lowercase() !in trustedHosts) {
            throw CatalogSourceException(CatalogSourceFailure.UNTRUSTED_URL)
        }
        return url
    }

    private fun isTrustedHttps(value: String): Boolean = try {
        trustedUrl(value)
        true
    } catch (_: CatalogSourceException) {
        false
    }

    private fun CatalogLanguage.accepts(languages: List<String>): Boolean = when (this) {
        CatalogLanguage.ALL -> true
        CatalogLanguage.ZH -> languages.any { it.startsWith("zh", ignoreCase = true) }
        CatalogLanguage.EN -> languages.any { it.startsWith("en", ignoreCase = true) }
    }

    private fun instantEpochOrNull(value: String): Long? = try {
        Instant.parse(value).toEpochMilli()
    } catch (_: Exception) {
        null
    }

    companion object {
        const val MAX_RESPONSE_BYTES: Int = 2 * 1024 * 1024

        private const val READ_BUFFER_BYTES = 8 * 1024
        private const val TXT_PRIORITY = 30
        private const val HTML_PRIORITY = 90
        private const val OPDS_ACCEPT = "application/atom+xml;profile=opds-catalog, application/atom+xml"
    }
}

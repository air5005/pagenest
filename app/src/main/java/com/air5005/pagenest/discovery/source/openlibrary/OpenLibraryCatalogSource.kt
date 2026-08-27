package com.air5005.pagenest.discovery.source.openlibrary

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
import com.wxn.reader.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class OpenLibraryCatalogSource(
    private val client: HttpClient,
    private val json: Json = DEFAULT_JSON,
) : OnlineCatalogSource {
    override val id: String = SOURCE_ID

    override suspend fun browse(request: CatalogRequest): CatalogPage {
        val url = URLBuilder(BASE_URL).apply {
            encodedPath = "/search.json"
            parameters.clear()
            parameters.append("q", query(request))
            parameters.append("fields", FIELDS)
            parameters.append("limit", request.pageSize.coerceIn(1, MAX_PAGE_SIZE).toString())
            when (request.language) {
                CatalogLanguage.ALL -> Unit
                CatalogLanguage.ZH -> parameters.append("lang", "zh")
                CatalogLanguage.EN -> parameters.append("lang", "en")
            }
            when (request.kind) {
                CatalogKind.LATEST -> parameters.append("sort", "new")
                CatalogKind.RECOMMENDED,
                CatalogKind.POPULAR,
                -> parameters.append("sort", "rating")
                CatalogKind.SEARCH,
                CatalogKind.SUBJECT,
                -> Unit
            }
        }.build()
        val response = fetch(url.toString())
        val dto = try {
            json.decodeFromString<OpenLibraryCatalogDto>(response.toString(Charsets.UTF_8))
        } catch (_: SerializationException) {
            throw CatalogSourceException(CatalogSourceFailure.MALFORMED)
        }
        return CatalogPage(
            books = dto.docs.asSequence()
                .filter { it.publicScan && it.ebookAccess.equals("public", ignoreCase = true) }
                .filter { request.language.accepts(it.languages) }
                .mapIndexedNotNull(::mapBook)
                .take(request.pageSize.coerceAtLeast(0))
                .toList(),
            nextPageToken = null,
        )
    }

    override suspend fun details(reference: SourceReference): SourceBookDetails? = null

    private fun query(request: CatalogRequest): String = when (request.kind) {
        CatalogKind.SEARCH -> request.query.orEmpty().trim()
        CatalogKind.SUBJECT -> request.subject?.trim()?.takeIf(String::isNotEmpty)
            ?.let { "subject:\"$it\"" }.orEmpty()
        else -> "ebook_access:public"
    }.ifBlank { "ebook_access:public" }

    private fun mapBook(index: Int, dto: OpenLibraryCatalogWorkDto): OnlineBook? {
        val workId = WORK_ID.matchEntire(dto.key)?.groupValues?.get(1) ?: return null
        return OnlineBook(
            stableKey = "openlibrary:$workId",
            title = dto.title,
            authors = dto.authorNames,
            summary = dto.firstPublishYear?.let { "First published $it" },
            languages = dto.languages,
            subjects = emptyList(),
            coverUrl = dto.coverId?.takeIf { it > 0 }?.let {
                "https://covers.openlibrary.org/b/id/$it-L.jpg?default=false"
            },
            sourceRank = index + 1,
            popularity = null,
            catalogUpdatedAtEpochMillis = null,
            rightsStatus = RightsStatus.PUBLIC_DOMAIN,
            sourceReferences = listOf(SourceReference(id, workId)),
            acquisitions = listOf(
                OnlineAcquisition(
                    sourceId = id,
                    format = OnlineBookFormat.HTML,
                    url = "https://openlibrary.org/works/$workId",
                    access = AcquisitionAccess.EXTERNAL,
                    qualityPriority = 90,
                ),
            ),
        )
    }

    private suspend fun fetch(url: String): ByteArray = try {
        val response = client.get(url) {
            header(HttpHeaders.Accept, "application/json")
            header(HttpHeaders.UserAgent, userAgent())
        }
        if (response.status != HttpStatusCode.OK) {
            throw CatalogSourceException(CatalogSourceFailure.HTTP)
        }
        val bytes = response.bodyAsBytes()
        if (bytes.size > MAX_RESPONSE_BYTES) {
            throw CatalogSourceException(CatalogSourceFailure.RESPONSE_TOO_LARGE)
        }
        bytes
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

    private fun CatalogLanguage.accepts(languages: List<String>): Boolean = when (this) {
        CatalogLanguage.ALL -> true
        CatalogLanguage.ZH -> languages.any { it.startsWith("zh", ignoreCase = true) || it == "chi" }
        CatalogLanguage.EN -> languages.any { it.startsWith("en", ignoreCase = true) }
    }

    companion object {
        const val SOURCE_ID = "openlibrary"
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val BASE_URL = "https://openlibrary.org"
        private const val MAX_PAGE_SIZE = 40
        private const val FIELDS =
            "key,title,author_name,language,cover_i,first_publish_year,public_scan_b,ebook_access,ia"
        private val WORK_ID = Regex("/works/(OL[1-9][0-9]{0,9}W)")
        private val DEFAULT_JSON = Json { ignoreUnknownKeys = true; explicitNulls = false }

        private fun userAgent(): String =
            "YiNest/${BuildConfig.VERSION_NAME} (+https://github.com/air5005/pagenest)"
    }
}

@Serializable
private data class OpenLibraryCatalogDto(
    val docs: List<OpenLibraryCatalogWorkDto> = emptyList(),
)

@Serializable
private data class OpenLibraryCatalogWorkDto(
    val key: String,
    val title: String,
    @SerialName("author_name") val authorNames: List<String> = emptyList(),
    val language: List<String> = emptyList(),
    @SerialName("cover_i") val coverId: Long? = null,
    @SerialName("first_publish_year") val firstPublishYear: Int? = null,
    @SerialName("public_scan_b") val publicScan: Boolean = false,
    @SerialName("ebook_access") val ebookAccess: String? = null,
) {
    val languages: List<String> get() = language
}

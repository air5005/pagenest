package com.air5005.pagenest.discovery.source.gutendex

import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.CatalogSourceException
import com.air5005.pagenest.discovery.model.CatalogSourceFailure
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GutendexCatalogSourceTest {

    @Test
    fun `popular request maps metadata and only secure full acquisitions`() = runTest {
        lateinit var captured: HttpRequestData
        val source = sourceReturning(popularFixture()) { captured = it }

        val page = source.browse(CatalogRequest(CatalogKind.POPULAR, CatalogLanguage.ZH))
        val book = page.books.single()

        assertEquals("/books", captured.url.encodedPath)
        assertEquals("zh", captured.url.parameters["languages"])
        assertEquals("popular", captured.url.parameters["sort"])
        assertEquals("1", captured.url.parameters["page"])
        assertEquals("gutendex", book.sourceReferences.single().sourceId)
        assertEquals("123", book.sourceReferences.single().sourceBookId)
        assertEquals(listOf("zh"), book.languages)
        assertEquals(RightsStatus.PUBLIC_DOMAIN, book.rightsStatus)
        assertEquals(9876.0, book.popularity!!, 0.0)
        assertEquals(OnlineBookFormat.EPUB, book.bestReadableAcquisition()!!.format)
        assertTrue(book.acquisitions.all { it.url.startsWith("https://") })
        assertTrue(book.acquisitions.none { it.url.endsWith(".mobi") })
        assertEquals("https://gutendex.com/books/?page=2&sort=popular", page.nextPageToken)
    }

    @Test
    fun `latest requests descending catalog ids`() = runTest {
        lateinit var captured: HttpRequestData
        val source = sourceReturning(emptyPage()) { captured = it }

        source.browse(CatalogRequest(CatalogKind.LATEST))

        assertEquals("descending", captured.url.parameters["sort"])
    }

    @Test
    fun `search and subject values are encoded by the URL builder`() = runTest {
        val captured = mutableListOf<HttpRequestData>()
        val source = sourceReturning(emptyPage()) { captured += it }

        source.browse(CatalogRequest(CatalogKind.SEARCH, query = "红楼 梦 & poetry"))
        source.browse(CatalogRequest(CatalogKind.SUBJECT, subject = "science fiction & fantasy"))

        assertEquals("红楼 梦 & poetry", captured[0].url.parameters["search"])
        assertEquals("science fiction & fantasy", captured[1].url.parameters["topic"])
        assertFalse(captured[0].url.toString().contains("红楼 梦"))
    }

    @Test
    fun `malformed response is a source failure`() = runTest {
        val source = sourceReturning(resource("discovery/gutendex/malformed.json"))

        val error = catalogFailure {
            source.browse(CatalogRequest(CatalogKind.POPULAR))
        }

        assertEquals(CatalogSourceFailure.MALFORMED, error.failure)
    }

    @Test
    fun `pagination URL is rejected when its host changes`() = runTest {
        val source = sourceReturning(
            """{"count":0,"next":"https://evil.example/books?page=2","results":[]}""",
        )

        val error = catalogFailure {
            source.browse(CatalogRequest(CatalogKind.POPULAR))
        }

        assertEquals(CatalogSourceFailure.UNTRUSTED_URL, error.failure)
    }

    @Test
    fun `pagination request accepts only configured HTTPS host`() = runTest {
        lateinit var captured: HttpRequestData
        val source = sourceReturning(emptyPage()) { captured = it }

        source.browse(
            CatalogRequest(
                kind = CatalogKind.POPULAR,
                pageToken = "https://gutendex.com/books/?page=3&sort=popular",
            ),
        )

        assertEquals("3", captured.url.parameters["page"])
        val error = catalogFailure {
            source.browse(
                CatalogRequest(
                    kind = CatalogKind.POPULAR,
                    pageToken = "http://gutendex.com/books/?page=4",
                ),
            )
        }
        assertEquals(CatalogSourceFailure.UNTRUSTED_URL, error.failure)
    }

    @Test
    fun `non success status does not expose response body`() = runTest {
        val privateBody = "private response content"
        val source = sourceReturning(privateBody, HttpStatusCode.InternalServerError)

        val error = catalogFailure {
            source.browse(CatalogRequest(CatalogKind.POPULAR))
        }

        assertEquals(CatalogSourceFailure.HTTP, error.failure)
        assertFalse(error.message.orEmpty().contains(privateBody))
    }

    @Test
    fun `response larger than two mebibytes is rejected before parsing`() = runTest {
        val source = sourceReturning(ByteArray(GutendexCatalogSource.MAX_RESPONSE_BYTES + 1))

        val error = catalogFailure {
            source.browse(CatalogRequest(CatalogKind.POPULAR))
        }

        assertEquals(CatalogSourceFailure.RESPONSE_TOO_LARGE, error.failure)
    }

    @Test
    fun `copyrighted entry has no direct acquisition`() = runTest {
        val body = popularFixture().replace("\"copyright\": false", "\"copyright\": true")
        val source = sourceReturning(body)

        val book = source.browse(CatalogRequest(CatalogKind.POPULAR)).books.single()

        assertEquals(RightsStatus.UNKNOWN, book.rightsStatus)
        assertNull(book.bestReadableAcquisition())
    }

    @Test
    fun `details fetches source id path and maps one book`() = runTest {
        val singleBook = popularFixture()
            .substringAfter("\"results\": [")
            .substringBeforeLast("\n  ]")
            .trim()
        lateinit var captured: HttpRequestData
        val source = sourceReturning(singleBook) { captured = it }

        val details = source.details(SourceReference("gutendex", "123"))

        assertEquals("/books/123", captured.url.encodedPath)
        assertEquals("gutenberg:123", details!!.book.stableKey)
    }

    private fun sourceReturning(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ): GutendexCatalogSource = sourceReturning(body.toByteArray(), status, capture)

    private fun sourceReturning(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ): GutendexCatalogSource {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return GutendexCatalogSource(HttpClient(engine) { followRedirects = false })
    }

    private fun popularFixture(): String = resource("discovery/gutendex/popular.json")

    private fun emptyPage(): String = """{"count":0,"next":null,"previous":null,"results":[]}"""

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path))
        .readText()

    private suspend fun catalogFailure(block: suspend () -> Unit): CatalogSourceException = try {
        block()
        throw AssertionError("Expected CatalogSourceException")
    } catch (error: CatalogSourceException) {
        error
    }
}

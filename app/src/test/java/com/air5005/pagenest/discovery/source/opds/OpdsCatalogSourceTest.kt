package com.air5005.pagenest.discovery.source.opds

import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.CatalogSourceException
import com.air5005.pagenest.discovery.model.CatalogSourceFailure
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsCatalogSourceTest {

    @Test
    fun `gutenberg normalizes id and maps popular feed`() = runTest {
        lateinit var captured: HttpRequestData
        val client = clientReturning(gutenbergFixture()) { captured = it }
        val source = KnownOpdsSources.gutenberg(client)

        val book = source.browse(CatalogRequest(CatalogKind.POPULAR)).books.single()

        assertEquals("/ebooks/search.opds/", captured.url.encodedPath)
        assertEquals("downloads", captured.url.parameters["sort_order"])
        assertEquals("gutenberg:1342", book.stableKey)
        assertEquals("1342", book.sourceReferences.single().sourceBookId)
        assertEquals(OnlineBookFormat.EPUB, book.bestReadableAcquisition()!!.format)
    }

    @Test
    fun `standard ebooks has preferred epub and drops insecure link`() = runTest {
        val source = KnownOpdsSources.standardEbooks(clientReturning(standardFixture()))

        val book = source.browse(CatalogRequest(CatalogKind.RECOMMENDED)).books.single()

        assertEquals("standard-ebooks", book.sourceReferences.single().sourceId)
        assertEquals(10, book.bestReadableAcquisition()!!.qualityPriority)
        assertEquals(1, book.acquisitions.size)
        assertTrue(book.acquisitions.all { it.url.startsWith("https://") })
    }

    @Test
    fun `page size truncates entries`() = runTest {
        val twoEntries = gutenbergFixture().replace(
            "</feed>",
            gutenbergFixture().substringAfter("<entry>").substringBefore("</entry>")
                .let { "<entry>${it.replace("1342", "1343")}</entry></feed>" },
        )
        val source = KnownOpdsSources.gutenberg(clientReturning(twoEntries))

        val page = source.browse(CatalogRequest(CatalogKind.POPULAR, pageSize = 1))

        assertEquals(1, page.books.size)
    }

    @Test
    fun `untrusted next host is rejected`() = runTest {
        val body = standardFixture().replace(
            "https://standardebooks.org/ebooks/page-2",
            "https://evil.example/page-2",
        )
        val source = KnownOpdsSources.standardEbooks(clientReturning(body))

        val error = catalogFailure { source.browse(CatalogRequest(CatalogKind.POPULAR)) }

        assertEquals(CatalogSourceFailure.UNTRUSTED_URL, error.failure)
    }

    @Test
    fun `untrusted and malformed page tokens are rejected before request`() = runTest {
        var requested = false
        val source = KnownOpdsSources.gutenberg(clientReturning(gutenbergFixture()) { requested = true })

        val error = catalogFailure {
            source.browse(
                CatalogRequest(
                    CatalogKind.POPULAR,
                    pageToken = "http://www.gutenberg.org/ebooks/search.opds/?page=2",
                ),
            )
        }

        assertEquals(CatalogSourceFailure.UNTRUSTED_URL, error.failure)
        assertFalse(requested)
    }

    @Test
    fun `malformed XML and oversized body have safe failures`() = runTest {
        val privateXml = "<feed><private-title>Secret title"
        val malformed = KnownOpdsSources.gutenberg(clientReturning(privateXml))
        val malformedError = catalogFailure {
            malformed.browse(CatalogRequest(CatalogKind.POPULAR))
        }
        assertEquals(CatalogSourceFailure.MALFORMED, malformedError.failure)
        assertFalse(malformedError.message.orEmpty().contains("Secret title"))

        val oversized = KnownOpdsSources.gutenberg(
            clientReturning(ByteArray(OpdsCatalogSource.MAX_RESPONSE_BYTES + 1)),
        )
        val sizeError = catalogFailure {
            oversized.browse(CatalogRequest(CatalogKind.POPULAR))
        }
        assertEquals(CatalogSourceFailure.RESPONSE_TOO_LARGE, sizeError.failure)
    }

    @Test
    fun `non success response body is not exposed`() = runTest {
        val source = KnownOpdsSources.gutenberg(
            clientReturning("private server response", HttpStatusCode.Forbidden),
        )

        val error = catalogFailure { source.browse(CatalogRequest(CatalogKind.POPULAR)) }

        assertEquals(CatalogSourceFailure.HTTP, error.failure)
        assertFalse(error.message.orEmpty().contains("private server response"))
    }

    @Test
    fun `details are unavailable without a trusted entry endpoint`() = runTest {
        val source = KnownOpdsSources.gutenberg(clientReturning(gutenbergFixture()))

        assertNull(source.details(com.air5005.pagenest.discovery.model.SourceReference("gutenberg-opds", "1342")))
    }

    private fun clientReturning(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ): HttpClient = clientReturning(body.toByteArray(), status, capture)

    private fun clientReturning(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ): HttpClient = HttpClient(MockEngine { request ->
        capture(request)
        respond(body, status)
    }) { followRedirects = false }

    private suspend fun catalogFailure(block: suspend () -> Unit): CatalogSourceException = try {
        block()
        throw AssertionError("Expected CatalogSourceException")
    } catch (error: CatalogSourceException) {
        error
    }

    private fun gutenbergFixture(): String = resource("discovery/opds/gutenberg-popular.xml")

    private fun standardFixture(): String = resource("discovery/opds/standard-ebooks.xml")

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path))
        .readText()
}

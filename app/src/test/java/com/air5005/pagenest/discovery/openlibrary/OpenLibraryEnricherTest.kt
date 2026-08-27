package com.air5005.pagenest.discovery.openlibrary

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLibraryEnricherTest {
    private var now = 1_000_000L

    @Test
    fun `exact title and author match maps bounded metadata only`() = runTest {
        lateinit var captured: HttpRequestData
        val cache = MemoryMetadataCache()
        val enricher = enricherReturning(fixture("pride-search.json"), cache) { captured = it }

        val metadata = enricher.enrich(book())!!

        assertEquals("/search.json", captured.url.encodedPath)
        assertEquals("Pride and Prejudice", captured.url.parameters["title"])
        assertEquals("Jane Austen", captured.url.parameters["author"])
        assertEquals("5", captured.url.parameters["limit"])
        assertEquals(OpenLibraryEnricher.REQUEST_FIELDS, captured.url.parameters["fields"])
        assertTrue(captured.headers[HttpHeaders.UserAgent].orEmpty().contains("YiNest"))
        assertEquals("OL66554W", metadata.workId)
        assertEquals(OpenLibraryEbookAccess.PUBLIC, metadata.ebookAccess)
        assertEquals("https://covers.openlibrary.org/b/id/12345-L.jpg?default=false", metadata.coverUrl)
        assertEquals("https://openlibrary.org/works/OL66554W", metadata.sourcePageUrl)
        assertEquals(0, book().acquisitions.count { it.sourceId == "open-library" })
        assertEquals(metadata, cache.values.values.single().metadata)
    }

    @Test
    fun `fresh cached metadata avoids request`() = runTest {
        val cache = MemoryMetadataCache()
        cache.put(
            OpenLibraryEnricher.cacheKey(book()),
            CachedOpenLibraryMetadata(now - OpenLibraryEnricher.METADATA_TTL_MILLIS, metadata()),
        )
        var requested = false
        val enricher = enricherReturning(fixture("no-match.json"), cache) { requested = true }

        val result = enricher.enrich(book())

        assertEquals("OL66554W", result!!.workId)
        assertFalse(requested)
    }

    @Test
    fun `stale cached metadata refreshes and no match is cached`() = runTest {
        val cache = MemoryMetadataCache()
        cache.put(
            OpenLibraryEnricher.cacheKey(book()),
            CachedOpenLibraryMetadata(now - OpenLibraryEnricher.METADATA_TTL_MILLIS - 1, metadata()),
        )
        var requestCount = 0
        val enricher = enricherReturning(fixture("no-match.json"), cache) { requestCount += 1 }

        assertNull(enricher.enrich(book()))
        assertEquals(1, requestCount)
        assertNull(cache.values.values.single().metadata)
    }

    @Test
    fun `malformed oversized and http errors expose no response content`() = runTest {
        val malformedSecret = "<private-title>Secret title"
        val malformed = failureOf(enricherReturning(malformedSecret, MemoryMetadataCache()))
        assertEquals(OpenLibraryFailure.MALFORMED, malformed.failure)
        assertFalse(malformed.message.orEmpty().contains("Secret title"))

        val oversized = failureOf(
            enricherReturning(ByteArray(OpenLibraryEnricher.MAX_RESPONSE_BYTES + 1), MemoryMetadataCache()),
        )
        assertEquals(OpenLibraryFailure.RESPONSE_TOO_LARGE, oversized.failure)

        val http = failureOf(
            enricherReturning("private server response", MemoryMetadataCache(), HttpStatusCode.Forbidden),
        )
        assertEquals(OpenLibraryFailure.HTTP, http.failure)
        assertFalse(http.message.orEmpty().contains("private server response"))
    }

    @Test
    fun `insecure base URL is rejected before request`() {
        var requested = false
        val error = try {
            OpenLibraryEnricher(
                client = clientReturning(fixture("no-match.json")) { requested = true },
                cache = MemoryMetadataCache(),
                limiter = immediateLimiter(),
                nowEpochMillis = { now },
                baseUrl = "http://openlibrary.org/search.json",
            )
            throw AssertionError("Expected failure")
        } catch (failure: OpenLibraryException) {
            failure
        }

        assertEquals(OpenLibraryFailure.UNTRUSTED_URL, error.failure)
        assertFalse(requested)
    }

    private suspend fun failureOf(enricher: OpenLibraryEnricher): OpenLibraryException = try {
        enricher.enrich(book())
        throw AssertionError("Expected OpenLibraryException")
    } catch (failure: OpenLibraryException) {
        failure
    }

    private fun enricherReturning(
        body: String,
        cache: OpenLibraryMetadataCache,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ) = enricherReturning(body.toByteArray(), cache, status, capture)

    private fun enricherReturning(
        body: ByteArray,
        cache: OpenLibraryMetadataCache,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ) = OpenLibraryEnricher(
        client = clientReturning(body, status, capture),
        cache = cache,
        limiter = immediateLimiter(),
        nowEpochMillis = { now },
    )

    private fun clientReturning(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ) = clientReturning(body.toByteArray(), status, capture)

    private fun clientReturning(
        body: ByteArray,
        status: HttpStatusCode = HttpStatusCode.OK,
        capture: (HttpRequestData) -> Unit = {},
    ) = HttpClient(MockEngine { request ->
        capture(request)
        respond(body, status)
    }) { followRedirects = false }

    private fun immediateLimiter() = OpenLibraryRateLimiter(
        nowMillis = { now },
        delayMillis = { duration -> now += duration },
    )

    private fun book() = OnlineBook(
        stableKey = "gutenberg:1342",
        title = "Pride and Prejudice",
        authors = listOf("Jane Austen"),
        summary = null,
        languages = listOf("en"),
        subjects = emptyList(),
        coverUrl = null,
        sourceRank = 1,
        popularity = null,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = listOf(SourceReference("gutendex", "1342")),
        acquisitions = listOf(
            OnlineAcquisition(
                "gutendex",
                OnlineBookFormat.EPUB,
                "https://files.example/1342.epub",
                AcquisitionAccess.FREE_FULL,
                20,
            ),
        ),
    )

    private fun metadata() = OpenLibraryMetadata(
        workId = "OL66554W",
        coverUrl = null,
        firstPublishYear = 1813,
        editionCount = 752,
        publicScan = true,
        ebookAccess = OpenLibraryEbookAccess.PUBLIC,
        sourcePageUrl = "https://openlibrary.org/works/OL66554W",
    )

    private fun fixture(name: String): String = checkNotNull(
        javaClass.classLoader?.getResource("discovery/openlibrary/$name"),
    ).readText()

    private class MemoryMetadataCache : OpenLibraryMetadataCache {
        val values = mutableMapOf<String, CachedOpenLibraryMetadata>()
        override suspend fun get(key: String): CachedOpenLibraryMetadata? = values[key]
        override suspend fun put(key: String, value: CachedOpenLibraryMetadata) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}

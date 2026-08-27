package com.air5005.pagenest.discovery.source.openlibrary

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.RightsStatus
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
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenLibraryCatalogSourceTest {
    @Test
    fun `search identifies YiNest and keeps only public readable works`() = runTest {
        lateinit var captured: HttpRequestData
        val source = sourceReturning(fixture()) { captured = it }

        val page = source.browse(
            CatalogRequest(CatalogKind.SEARCH, CatalogLanguage.EN, query = "Alice"),
        )

        assertEquals("/search.json", captured.url.encodedPath)
        assertEquals("Alice", captured.url.parameters["q"])
        assertEquals("en", captured.url.parameters["lang"])
        assertTrue(captured.headers[HttpHeaders.UserAgent].orEmpty().startsWith("YiNest/"))
        assertEquals(listOf("Public Book"), page.books.map { it.title })
        val book = page.books.single()
        assertEquals(RightsStatus.PUBLIC_DOMAIN, book.rightsStatus)
        assertEquals("OL123W", book.sourceReferences.single().sourceBookId)
        assertEquals(AcquisitionAccess.EXTERNAL, book.acquisitions.single().access)
        assertFalse(book.acquisitions.single().canReadDirectly)
    }

    @Test
    fun `latest request asks for new results and bounded fields`() = runTest {
        lateinit var captured: HttpRequestData
        val source = sourceReturning("{\"docs\":[]}") { captured = it }

        source.browse(CatalogRequest(CatalogKind.LATEST))

        assertEquals("new", captured.url.parameters["sort"])
        assertEquals("20", captured.url.parameters["limit"])
        assertTrue(captured.url.parameters["fields"].orEmpty().contains("ebook_access"))
    }

    private fun sourceReturning(
        body: String,
        capture: (HttpRequestData) -> Unit = {},
    ): OpenLibraryCatalogSource {
        val engine = MockEngine { request ->
            capture(request)
            respond(
                content = body,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        return OpenLibraryCatalogSource(HttpClient(engine))
    }

    private fun fixture() = """
        {
          "docs": [
            {
              "key": "/works/OL123W",
              "title": "Public Book",
              "author_name": ["Public Author"],
              "language": ["eng"],
              "cover_i": 42,
              "first_publish_year": 1900,
              "public_scan_b": true,
              "ebook_access": "public",
              "ia": ["publicbook00auth"]
            },
            {
              "key": "/works/OL999W",
              "title": "Borrowed Book",
              "author_name": ["Borrow Author"],
              "language": ["eng"],
              "public_scan_b": true,
              "ebook_access": "borrowable",
              "ia": ["borrowedbook00auth"]
            }
          ]
        }
    """.trimIndent()
}

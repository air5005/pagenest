package com.air5005.pagenest.discovery.source.opds

import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GutenbergCatalogContractTest {
    @Test
    fun `navigation entry derives official direct epub acquisition`() = runTest {
        val engine = MockEngine {
            respond(
                content = fixture(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/atom+xml"),
            )
        }

        val book = KnownOpdsSources.gutenberg(HttpClient(engine))
            .browse(CatalogRequest(CatalogKind.POPULAR))
            .books.single()

        val acquisition = book.bestReadableAcquisition()!!
        assertEquals(OnlineBookFormat.EPUB, acquisition.format)
        assertEquals("https://www.gutenberg.org/ebooks/1342.epub3.images", acquisition.url)
        assertTrue(acquisition.canReadDirectly)
    }

    private fun fixture() = """
        <?xml version="1.0" encoding="utf-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
          <entry>
            <id>https://www.gutenberg.org/ebooks/1342.opds</id>
            <title>Pride and Prejudice</title>
            <content type="text">Jane Austen</content>
            <link type="application/atom+xml;profile=opds-catalog"
                  rel="subsection" href="/ebooks/1342.opds" />
          </entry>
        </feed>
    """.trimIndent()
}

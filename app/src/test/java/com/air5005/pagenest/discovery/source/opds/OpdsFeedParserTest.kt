package com.air5005.pagenest.discovery.source.opds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OpdsFeedParserTest {
    private val parser = OpdsFeedParser()

    @Test
    fun `parses acquisition cover author language and next page`() {
        val feed = parser.parse(resource("discovery/opds/standard-ebooks.xml"))
        val entry = feed.entries.single()

        assertEquals("https://standardebooks.org/ebooks/page-2", feed.nextUrl)
        assertEquals("Pride and Prejudice", entry.title)
        assertEquals(listOf("Jane Austen"), entry.authors)
        assertEquals(listOf("en"), entry.languages)
        assertEquals(listOf("Fiction"), entry.subjects)
        assertEquals("https://standardebooks.org/images/pride.jpg", entry.coverUrl)
        assertEquals(2, entry.acquisitions.size)
        assertEquals("application/epub+zip", entry.acquisitions.first().type)
    }

    @Test
    fun `doctype and external entities are rejected`() {
        val error = assertThrows(OpdsParseException::class.java) {
            parser.parse(resource("discovery/opds/xxe.xml"))
        }

        assertTrue(error.message.orEmpty().isNotBlank())
        assertTrue(!error.message.orEmpty().contains("passwd"))
    }

    @Test
    fun `partial entry metadata remains parseable`() {
        val feed = parser.parse(
            """<feed xmlns="http://www.w3.org/2005/Atom"><entry><id>book:1</id><title>Only a title</title></entry></feed>""",
        )

        assertEquals("Only a title", feed.entries.single().title)
        assertTrue(feed.entries.single().authors.isEmpty())
    }

    private fun resource(path: String): String = checkNotNull(javaClass.classLoader?.getResource(path))
        .readText()
}

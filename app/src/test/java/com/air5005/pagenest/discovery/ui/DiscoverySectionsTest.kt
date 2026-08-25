package com.air5005.pagenest.discovery.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverySectionsTest {
    @Test
    fun `curated is capped at eight and ranking has unique stable keys`() {
        val books = (1..12).map { discoveryBook("book-${(it - 1) % 10}", "Book $it") }

        val sections = DiscoverySections.from(books)

        assertEquals(8, sections.curated.size)
        assertEquals(10, sections.ranking.size)
        assertEquals(sections.ranking.size, sections.ranking.map { it.stableKey }.toSet().size)
    }
}

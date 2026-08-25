package com.air5005.pagenest.discovery.aggregate

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineBookMergerTest {
    private val merger = OnlineBookMerger()

    @Test
    fun `gutenberg json and opds references merge without losing fallback links`() {
        val gutendex = book(
            stableKey = "gutenberg:1342",
            sourceId = "gutendex",
            sourceBookId = "1342",
            format = OnlineBookFormat.EPUB,
            priority = 20,
        )
        val opds = book(
            stableKey = "gutenberg:1342",
            sourceId = "gutenberg-opds",
            sourceBookId = "1342",
            format = OnlineBookFormat.TXT,
            priority = 30,
        )

        val merged = merger.merge(listOf(page(gutendex), page(opds)))

        assertEquals(1, merged.size)
        assertEquals(
            setOf("gutendex", "gutenberg-opds"),
            merged.single().sourceReferences.map { it.sourceId }.toSet(),
        )
        assertEquals(2, merged.single().acquisitions.size)
    }

    @Test
    fun `standard ebooks epub wins but gutenberg txt remains fallback`() {
        val standard = book(
            stableKey = "standard-ebooks:jane-austen/pride-and-prejudice",
            sourceId = "standard-ebooks",
            sourceBookId = "jane-austen/pride-and-prejudice",
            author = "Jane Austen",
            format = OnlineBookFormat.EPUB,
            priority = 10,
        )
        val gutenberg = book(
            stableKey = "gutenberg:1342",
            sourceId = "gutenberg-opds",
            sourceBookId = "1342",
            author = "Jane Austen",
            format = OnlineBookFormat.TXT,
            priority = 30,
        )

        val merged = merger.merge(listOf(page(standard), page(gutenberg))).single()

        assertEquals("standard-ebooks", merged.bestReadableAcquisition()!!.sourceId)
        assertTrue(merged.acquisitions.any { it.format == OnlineBookFormat.TXT })
    }

    @Test
    fun `same title with different authors is not merged`() {
        val result = merger.merge(
            listOf(
                page(book(title = "Home", author = "Author A", sourceBookId = "1")),
                page(book(title = "Home", author = "Author B", sourceBookId = "2")),
            ),
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `unicode punctuation and whitespace normalize without transliteration`() {
        val first = book(title = "Ｐｒｉｄｅ—and  Prejudice", author = "Jane Austen", sourceBookId = "a")
        val second = book(title = "pride and prejudice", author = "Jane Austen", sourceBookId = "b", sourceId = "other")

        assertEquals(1, merger.merge(listOf(page(first), page(second))).size)
        assertTrue(OnlineBookFingerprint.normalize("红楼梦").contains("红楼梦"))
    }

    @Test
    fun `merge keeps strongest metadata and deduplicates acquisition URL`() {
        val sparse = book(sourceBookId = "7", summary = "Short", rights = RightsStatus.UNKNOWN)
        val rich = book(
            sourceId = "gutenberg-opds",
            sourceBookId = "7",
            summary = "A substantially longer summary",
            rights = RightsStatus.PUBLIC_DOMAIN,
        )

        val merged = merger.merge(listOf(page(sparse), page(rich))).single()

        assertEquals("A substantially longer summary", merged.summary)
        assertEquals(RightsStatus.PUBLIC_DOMAIN, merged.rightsStatus)
        assertEquals(1, merged.acquisitions.size)
    }

    private fun page(book: OnlineBook) = CatalogPage(listOf(book), null)

    private fun book(
        stableKey: String = "gutenberg:1",
        title: String = "Pride and Prejudice",
        author: String = "Austen, Jane",
        language: String = "en",
        sourceId: String = "gutendex",
        sourceBookId: String = "1",
        format: OnlineBookFormat = OnlineBookFormat.EPUB,
        priority: Int = 20,
        summary: String? = null,
        rights: RightsStatus = RightsStatus.PUBLIC_DOMAIN,
        url: String = "https://files.example/$sourceBookId.${format.name.lowercase()}",
    ) = OnlineBook(
        stableKey = stableKey,
        title = title,
        authors = listOf(author),
        summary = summary,
        languages = listOf(language),
        subjects = listOf("Fiction"),
        coverUrl = "https://covers.example/$sourceBookId.jpg",
        sourceRank = 1,
        popularity = 100.0,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = rights,
        sourceReferences = listOf(SourceReference(sourceId, sourceBookId)),
        acquisitions = listOf(
            OnlineAcquisition(
                sourceId = sourceId,
                format = format,
                url = url,
                access = AcquisitionAccess.FREE_FULL,
                qualityPriority = priority,
            ),
        ),
    )
}

package com.air5005.pagenest.discovery.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineCatalogModelsTest {

    @Test
    fun `direct reading requires explicit free full access and https`() {
        val valid = acquisition(
            url = "https://files.example/book.epub",
            access = AcquisitionAccess.FREE_FULL,
        )
        val preview = acquisition(
            url = "https://files.example/sample.epub",
            access = AcquisitionAccess.PREVIEW,
        )
        val insecure = acquisition(
            url = "http://files.example/book.epub",
            access = AcquisitionAccess.FREE_FULL,
        )

        assertTrue(valid.canReadDirectly)
        assertFalse(preview.canReadDirectly)
        assertFalse(insecure.canReadDirectly)
    }

    @Test
    fun `best acquisition prefers standard ebooks epub then other epub then txt`() {
        val book = onlineBook(
            acquisitions = listOf(
                acquisition(
                    sourceId = "gutenberg-opds",
                    format = OnlineBookFormat.TXT,
                    priority = 30,
                ),
                acquisition(
                    sourceId = "gutendex",
                    format = OnlineBookFormat.EPUB,
                    priority = 20,
                ),
                acquisition(
                    sourceId = "standard-ebooks",
                    format = OnlineBookFormat.EPUB,
                    priority = 10,
                ),
            ),
        )

        assertEquals("standard-ebooks", book.bestReadableAcquisition()!!.sourceId)
    }

    @Test
    fun `best acquisition ignores previews and insecure downloads`() {
        val book = onlineBook(
            acquisitions = listOf(
                acquisition(
                    sourceId = "preview",
                    access = AcquisitionAccess.PREVIEW,
                    priority = 1,
                ),
                acquisition(
                    sourceId = "insecure",
                    url = "http://files.example/book.epub",
                    priority = 2,
                ),
            ),
        )

        assertNull(book.bestReadableAcquisition())
    }

    private fun acquisition(
        sourceId: String = "source",
        format: OnlineBookFormat = OnlineBookFormat.EPUB,
        url: String = "https://files.example/book.epub",
        access: AcquisitionAccess = AcquisitionAccess.FREE_FULL,
        priority: Int = 20,
    ) = OnlineAcquisition(
        sourceId = sourceId,
        format = format,
        url = url,
        access = access,
        qualityPriority = priority,
    )

    private fun onlineBook(acquisitions: List<OnlineAcquisition>) = OnlineBook(
        stableKey = "source:1",
        title = "A Book",
        authors = listOf("An Author"),
        summary = null,
        languages = listOf("en"),
        subjects = emptyList(),
        coverUrl = null,
        sourceRank = 1,
        popularity = null,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = listOf(SourceReference("source", "1")),
        acquisitions = acquisitions,
    )
}

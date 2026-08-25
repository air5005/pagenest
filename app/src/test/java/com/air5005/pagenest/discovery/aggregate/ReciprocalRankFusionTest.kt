package com.air5005.pagenest.discovery.aggregate

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Test

class ReciprocalRankFusionTest {
    private val fusion = ReciprocalRankFusion()

    @Test
    fun `raw download counts are never compared`() {
        val lowDownloads = book("Alpha", "a", popularity = 1.0)
        val highDownloads = book("Beta", "b", popularity = 9_999_999.0)

        val ranked = fusion.rank(listOf(listOf(lowDownloads, highDownloads)))

        assertEquals(listOf("Alpha", "Beta"), ranked.map { it.title })
    }

    @Test
    fun `input source order does not change deterministic ties`() {
        val alpha = book("Alpha", "a")
        val beta = book("Beta", "b")
        val first = fusion.rank(listOf(listOf(alpha, beta), listOf(beta, alpha)))
        val second = fusion.rank(listOf(listOf(beta, alpha), listOf(alpha, beta)))

        assertEquals(listOf("Alpha", "Beta"), first.map { it.title })
        assertEquals(first.map { it.stableKey }, second.map { it.stableKey })
    }

    @Test
    fun `k sixty score is reciprocal of k plus one based rank`() {
        val score = ReciprocalRankFusion.scoreForRanks(listOf(1, 2), k = 60)

        assertEquals(1.0 / 61.0 + 1.0 / 62.0, score, 0.0000000001)
    }

    private fun book(title: String, id: String, popularity: Double = 0.0) = OnlineBook(
        stableKey = "source:$id",
        title = title,
        authors = listOf("Author $id"),
        summary = null,
        languages = listOf("en"),
        subjects = emptyList(),
        coverUrl = null,
        sourceRank = 1,
        popularity = popularity,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = listOf(SourceReference("source", id)),
        acquisitions = listOf(
            OnlineAcquisition(
                sourceId = "source",
                format = OnlineBookFormat.EPUB,
                url = "https://files.example/$id.epub",
                access = AcquisitionAccess.FREE_FULL,
                qualityPriority = 20,
            ),
        ),
    )
}

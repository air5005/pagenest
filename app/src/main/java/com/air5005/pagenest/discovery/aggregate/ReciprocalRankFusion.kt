package com.air5005.pagenest.discovery.aggregate

import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.OnlineBook

class ReciprocalRankFusion(
    private val merger: OnlineBookMerger = OnlineBookMerger(),
) {
    fun rank(sourceLists: List<List<OnlineBook>>, k: Int = DEFAULT_K): List<OnlineBook> {
        require(k >= 0) { "RRF k must not be negative" }
        val merged = merger.merge(sourceLists.map { CatalogPage(it, null) })
        return merged.map { book ->
            val ranks = sourceLists.mapNotNull { source ->
                source.indexOfFirst { candidate -> OnlineBookFingerprint.matches(book, candidate) }
                    .takeIf { it >= 0 }
                    ?.plus(1)
            }
            ScoredBook(book, scoreForRanks(ranks, k))
        }.sortedWith(
            compareByDescending<ScoredBook> { it.score }
                .thenBy { it.book.bestReadableAcquisition()?.qualityPriority ?: Int.MAX_VALUE }
                .thenBy { OnlineBookFingerprint.normalize(it.book.title) }
                .thenBy { it.book.stableKey },
        ).map { it.book }
    }

    private data class ScoredBook(val book: OnlineBook, val score: Double)

    companion object {
        const val DEFAULT_K = 60

        fun scoreForRanks(ranks: List<Int>, k: Int = DEFAULT_K): Double {
            require(k >= 0) { "RRF k must not be negative" }
            require(ranks.all { it > 0 }) { "RRF ranks are one-based" }
            return ranks.sumOf { rank -> 1.0 / (k + rank) }
        }
    }
}

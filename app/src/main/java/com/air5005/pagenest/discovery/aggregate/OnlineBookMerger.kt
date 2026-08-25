package com.air5005.pagenest.discovery.aggregate

import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.RightsStatus
import java.net.URI
import java.util.Locale

class OnlineBookMerger {
    fun merge(pages: List<CatalogPage>): List<OnlineBook> {
        val books = pages.flatMap { it.books }
        if (books.isEmpty()) return emptyList()
        val parent = IntArray(books.size) { it }

        fun find(index: Int): Int {
            var root = index
            while (parent[root] != root) root = parent[root]
            var current = index
            while (parent[current] != current) {
                val next = parent[current]
                parent[current] = root
                current = next
            }
            return root
        }

        fun union(first: Int, second: Int) {
            val firstRoot = find(first)
            val secondRoot = find(second)
            if (firstRoot != secondRoot) parent[maxOf(firstRoot, secondRoot)] = minOf(firstRoot, secondRoot)
        }

        for (first in books.indices) {
            for (second in first + 1 until books.size) {
                if (OnlineBookFingerprint.matches(books[first], books[second])) union(first, second)
            }
        }

        return books.indices
            .groupBy(::find)
            .values
            .map { indices -> mergeGroup(indices.map(books::get)) }
            .sortedWith(compareBy({ OnlineBookFingerprint.normalize(it.title) }, { it.stableKey }))
    }

    private fun mergeGroup(books: List<OnlineBook>): OnlineBook {
        val preferred = books.minWith(
            compareBy<OnlineBook> { it.bestReadableAcquisition()?.qualityPriority ?: Int.MAX_VALUE }
                .thenBy { it.stableKey },
        )
        val acquisitions = books.flatMap { it.acquisitions }
            .groupBy { normalizedUrl(it.url) }
            .values
            .map { duplicates ->
                duplicates.minWith(
                    compareBy<OnlineAcquisition> { it.qualityPriority }
                        .thenBy { it.sourceId }
                        .thenBy { it.url },
                )
            }
            .sortedWith(
                compareBy<OnlineAcquisition> { it.qualityPriority }
                    .thenBy { it.sourceId }
                    .thenBy { it.url },
            )
        return preferred.copy(
            stableKey = OnlineBookFingerprint.canonicalStableKey(books),
            title = preferred.title,
            authors = preferred.authors.ifEmpty { books.firstNotNullOfOrNull { it.authors.takeIf(List<String>::isNotEmpty) }.orEmpty() },
            summary = books.mapNotNull { it.summary?.takeIf(String::isNotBlank) }.maxByOrNull(String::length),
            languages = books.flatMap { it.languages }.distinctBy { it.lowercase(Locale.ROOT) }.sorted(),
            subjects = books.flatMap { it.subjects }.distinctBy { it.lowercase(Locale.ROOT) }.sorted(),
            coverUrl = books.sortedWith(
                compareBy<OnlineBook> { it.bestReadableAcquisition()?.qualityPriority ?: Int.MAX_VALUE }
                    .thenBy { it.stableKey },
            ).firstNotNullOfOrNull { it.coverUrl?.takeIf { url -> url.startsWith("https://") } },
            sourceRank = books.minOf { it.sourceRank },
            popularity = books.mapNotNull { it.popularity }.maxOrNull(),
            catalogUpdatedAtEpochMillis = books.mapNotNull { it.catalogUpdatedAtEpochMillis }.maxOrNull(),
            rightsStatus = books.maxBy(::rightsStrength).rightsStatus,
            sourceReferences = books.flatMap { it.sourceReferences }
                .distinctBy { it.sourceId to it.sourceBookId }
                .sortedWith(compareBy({ it.sourceId }, { it.sourceBookId })),
            acquisitions = acquisitions,
        )
    }

    private fun rightsStrength(book: OnlineBook): Int = when (book.rightsStatus) {
        RightsStatus.PUBLIC_DOMAIN -> 5
        RightsStatus.FREE_FULL -> 4
        RightsStatus.PREVIEW_ONLY -> 3
        RightsStatus.BORROW_ONLY -> 2
        RightsStatus.UNKNOWN -> 1
    }

    private fun normalizedUrl(value: String): String = try {
        val original = URI(value.trim()).normalize()
        URI(
            original.scheme?.lowercase(Locale.ROOT),
            original.userInfo,
            original.host?.lowercase(Locale.ROOT),
            original.port,
            original.path,
            original.query,
            original.fragment,
        ).toASCIIString()
    } catch (_: Exception) {
        value.trim()
    }
}

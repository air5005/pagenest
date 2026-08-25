package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.model.OnlineBook

data class DiscoverySections(
    val curated: List<OnlineBook>,
    val ranking: List<OnlineBook>,
) {
    companion object {
        val EMPTY = DiscoverySections(emptyList(), emptyList())

        fun from(books: List<OnlineBook>): DiscoverySections {
            val unique = books.distinctBy { it.stableKey }
            return DiscoverySections(
                curated = unique.filter { it.bestReadableAcquisition() != null }.take(CURATED_LIMIT),
                ranking = unique,
            )
        }

        private const val CURATED_LIMIT = 8
    }
}

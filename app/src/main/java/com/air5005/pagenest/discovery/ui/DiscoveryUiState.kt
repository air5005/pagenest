package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.config.DiscoverySourceStatus
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.openlibrary.OpenLibraryMetadata

enum class DiscoveryTab {
    RECOMMENDED,
    POPULAR,
    LATEST,
    SOURCES,
}

data class DiscoveryUiState(
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val hasLoadError: Boolean = false,
    val selectedTab: DiscoveryTab = DiscoveryTab.RECOMMENDED,
    val selectedLanguage: CatalogLanguage = CatalogLanguage.ALL,
    val searchQuery: String = "",
    val sections: DiscoverySections = DiscoverySections.EMPTY,
    val fromStaleCache: Boolean = false,
    val unavailableSourceIds: List<String> = emptyList(),
    val sourceStatuses: List<DiscoverySourceStatus> = emptyList(),
    val selectedBook: OnlineBook? = null,
    val isDetailLoading: Boolean = false,
    val detailMetadata: OpenLibraryMetadata? = null,
)

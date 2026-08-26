package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.model.SourceReference

object OnlineSourceLinkPolicy {
    private val gutenbergId = Regex("[1-9][0-9]{0,8}")
    private val standardEbooksSlug = Regex("[a-z0-9]+(?:-[a-z0-9]+)*(?:/[a-z0-9]+(?:-[a-z0-9]+)*)+")
    private val openLibraryWorkId = Regex("OL[1-9][0-9]{0,9}W")

    fun sourcePage(reference: SourceReference): String? = when (reference.sourceId) {
        DiscoverySourceRegistry.GUTENDEX_ID,
        DiscoverySourceRegistry.GUTENBERG_ID,
        -> reference.sourceBookId.takeIf(gutenbergId::matches)
            ?.let { "https://www.gutenberg.org/ebooks/$it" }

        DiscoverySourceRegistry.STANDARD_EBOOKS_ID ->
            reference.sourceBookId.takeIf(standardEbooksSlug::matches)
                ?.let { "https://standardebooks.org/ebooks/$it" }

        DiscoverySourceRegistry.OPEN_LIBRARY_ID ->
            openLibraryWorkPage(reference.sourceBookId)

        else -> null
    }

    fun openLibraryWorkPage(workId: String): String? = workId
        .takeIf(openLibraryWorkId::matches)
        ?.let { "https://openlibrary.org/works/$it" }
}

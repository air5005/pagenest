package com.air5005.pagenest.discovery.config

import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoverySourceRegistryTest {

    @Test
    fun `default registry has stable enabled source order and authorization status`() {
        var standardConstructed = false

        val registry = DiscoverySourceRegistry.create(
            gutendex = source("gutendex"),
            gutenberg = source("gutenberg-opds"),
            openLibrary = source("openlibrary"),
            standardEbooksFactory = {
                standardConstructed = true
                source("standard-ebooks")
            },
            standardEbooksAuthorized = false,
        )

        assertEquals(
            listOf("gutendex", "gutenberg-opds", "openlibrary"),
            registry.enabledSources.map { it.id },
        )
        assertEquals(
            listOf("gutendex", "gutenberg-opds", "openlibrary", "standard-ebooks"),
            registry.statuses.map { it.id },
        )
        assertEquals(
            SourceDisabledReason.AUTHORIZATION_REQUIRED,
            registry.statuses.single { it.id == "standard-ebooks" }.reason,
        )
        assertFalse(registry.statuses.single { it.id == "standard-ebooks" }.enabled)
        assertFalse(standardConstructed)
    }

    @Test
    fun `authorized standard ebooks is constructed once and appended`() {
        var constructionCount = 0

        val registry = DiscoverySourceRegistry.create(
            gutendex = source("gutendex"),
            gutenberg = source("gutenberg-opds"),
            openLibrary = source("openlibrary"),
            standardEbooksFactory = {
                constructionCount += 1
                source("standard-ebooks")
            },
            standardEbooksAuthorized = true,
        )

        assertEquals(
            listOf("gutendex", "gutenberg-opds", "openlibrary", "standard-ebooks"),
            registry.enabledSources.map { it.id },
        )
        assertTrue(registry.statuses.all { it.enabled })
        assertEquals(1, constructionCount)
    }

    private fun source(sourceId: String) = object : OnlineCatalogSource {
        override val id = sourceId
        override suspend fun browse(request: CatalogRequest) = CatalogPage(emptyList(), null)
        override suspend fun details(reference: SourceReference): SourceBookDetails? = null
    }
}

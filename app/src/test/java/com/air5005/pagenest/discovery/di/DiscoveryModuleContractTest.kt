package com.air5005.pagenest.discovery.di

import com.air5005.pagenest.discovery.cache.CachedCatalogPage
import com.air5005.pagenest.discovery.cache.CatalogCache
import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference
import com.air5005.pagenest.discovery.repository.DiscoveryCatalogRepository
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import dagger.Module
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryModuleContractTest {

    @Test
    fun `module is a hilt module and cache uses dedicated private directory`() {
        assertNotNull(DiscoveryModule::class.java.getAnnotation(Module::class.java))
        assertEquals(
            File("private-files", "discovery-cache"),
            DiscoveryModule.cacheDirectory(File("private-files")),
        )
    }

    @Test
    fun `repository provider exposes interface backed by registered sources`() {
        val registry = DiscoverySourceRegistry.create(
            gutendex = source("gutendex"),
            gutenberg = source("gutenberg-opds"),
            standardEbooksFactory = { source("standard-ebooks") },
            standardEbooksAuthorized = false,
        )

        val repository: DiscoveryCatalogRepository = DiscoveryModule.provideDiscoveryRepository(
            registry,
            memoryCache(),
        )

        assertTrue(repository.javaClass.simpleName.contains("OnlineDiscoveryRepository"))
    }

    private fun source(sourceId: String) = object : OnlineCatalogSource {
        override val id = sourceId
        override suspend fun browse(request: CatalogRequest) = CatalogPage(emptyList(), null)
        override suspend fun details(reference: SourceReference): SourceBookDetails? = null
    }

    private fun memoryCache() = object : CatalogCache {
        override suspend fun get(key: String): CachedCatalogPage? = null
        override suspend fun put(key: String, value: CachedCatalogPage) = Unit
        override suspend fun remove(key: String) = Unit
    }
}

package com.air5005.pagenest.discovery.repository

import com.air5005.pagenest.discovery.cache.CachedCatalogPage
import com.air5005.pagenest.discovery.cache.CatalogCache
import com.air5005.pagenest.discovery.cache.CatalogCacheKey
import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineDiscoveryRepositoryTest {
    private val now = 10_000_000L

    @Test
    fun `one failed source returns successful books and stable warning`() = runTest {
        val cache = MemoryCache()
        val repository = repository(
            sources = listOf(successSource("gutendex"), failingSource("standard-ebooks")),
            cache = cache,
        )

        val result = repository.discover(popular())

        assertEquals(1, result.page.books.size)
        assertEquals(listOf("standard-ebooks"), result.unavailableSourceIds)
        assertEquals(listOf("standard-ebooks"), result.page.sourceWarnings)
        assertFalse(result.fromStaleCache)
    }

    @Test
    fun `all failed sources return stale cache without deleting it`() = runTest {
        val cache = MemoryCache()
        val request = popular()
        val cached = CachedCatalogPage(now - 31 * 60_000L, page(book("cached", "Cached")))
        cache.put(CatalogCacheKey.from(request), cached)
        val repository = repository(
            listOf(failingSource("gutendex"), failingSource("standard-ebooks")),
            cache,
        )

        val result = repository.discover(request)

        assertTrue(result.fromStaleCache)
        assertEquals(cached.page.books, result.page.books)
        assertEquals(listOf("gutendex", "standard-ebooks"), result.unavailableSourceIds)
        assertEquals(cached, cache.get(CatalogCacheKey.from(request)))
    }

    @Test
    fun `fresh cache returns immediately without calling sources`() = runTest {
        val cache = MemoryCache()
        val request = CatalogRequest(CatalogKind.LATEST)
        cache.put(
            CatalogCacheKey.from(request),
            CachedCatalogPage(now - 59 * 60_000L, page(book("cached", "Cached"))),
        )
        val called = AtomicBoolean(false)
        val source = object : OnlineCatalogSource {
            override val id = "unused"
            override suspend fun browse(request: CatalogRequest): CatalogPage {
                called.set(true)
                return page(book("network", "Network"))
            }
            override suspend fun details(reference: SourceReference): SourceBookDetails? = null
        }

        val result = repository(listOf(source), cache).discover(request)

        assertEquals("Cached", result.page.books.single().title)
        assertFalse(called.get())
    }

    @Test
    fun `successful nonempty aggregate is cached`() = runTest {
        val cache = MemoryCache()
        val request = popular()

        val result = repository(listOf(successSource("gutendex")), cache).discover(request)

        assertEquals(result.page.books, cache.get(CatalogCacheKey.from(request))!!.page.books)
    }

    @Test
    fun `no source and no cache returns empty stable result`() = runTest {
        val result = repository(
            listOf(failingSource("b"), failingSource("a")),
            MemoryCache(),
        ).discover(popular())

        assertTrue(result.page.books.isEmpty())
        assertEquals(listOf("b", "a"), result.unavailableSourceIds)
        assertFalse(result.fromStaleCache)
    }

    @Test
    fun `source timeout is isolated`() = runTest {
        val slow = object : OnlineCatalogSource {
            override val id = "slow"
            override suspend fun browse(request: CatalogRequest): CatalogPage {
                delay(1_000)
                return page(book("slow", "Slow"))
            }
            override suspend fun details(reference: SourceReference): SourceBookDetails? = null
        }

        val result = repository(
            sources = listOf(slow, successSource("fast")),
            cache = MemoryCache(),
            timeoutMillis = 10,
        ).discover(popular())

        assertEquals(listOf("slow"), result.unavailableSourceIds)
        assertEquals("fast", result.page.books.single().stableKey)
    }

    @Test
    fun `default timeout tolerates a slow mobile catalog response`() = runTest {
        val slowButUsable = object : OnlineCatalogSource {
            override val id = "mobile-catalog"
            override suspend fun browse(request: CatalogRequest): CatalogPage {
                delay(12_000)
                return page(book("mobile", "Mobile network result"))
            }
            override suspend fun details(reference: SourceReference): SourceBookDetails? = null
        }
        val repository = OnlineDiscoveryRepository(
            sources = listOf(slowButUsable),
            cache = MemoryCache(),
            nowEpochMillis = { now },
        )

        val result = repository.discover(popular())

        assertEquals("mobile", result.page.books.single().stableKey)
        assertTrue(result.unavailableSourceIds.isEmpty())
    }

    @Test(expected = CancellationException::class)
    fun `caller cancellation propagates unchanged`() = runTest {
        val cancelled = object : OnlineCatalogSource {
            override val id = "cancelled"
            override suspend fun browse(request: CatalogRequest): CatalogPage = throw CancellationException("stop")
            override suspend fun details(reference: SourceReference): SourceBookDetails? = null
        }

        repository(listOf(cancelled), MemoryCache()).discover(popular())
    }

    private fun repository(
        sources: List<OnlineCatalogSource>,
        cache: CatalogCache,
        timeoutMillis: Long = 8_000L,
    ) = OnlineDiscoveryRepository(
        sources = sources,
        cache = cache,
        nowEpochMillis = { now },
        sourceTimeoutMillis = timeoutMillis,
    )

    private fun successSource(sourceId: String) = object : OnlineCatalogSource {
        override val id = sourceId
        override suspend fun browse(request: CatalogRequest) = page(book(sourceId, sourceId))
        override suspend fun details(reference: SourceReference): SourceBookDetails? = null
    }

    private fun failingSource(sourceId: String) = object : OnlineCatalogSource {
        override val id = sourceId
        override suspend fun browse(request: CatalogRequest): CatalogPage = error("private transport details")
        override suspend fun details(reference: SourceReference): SourceBookDetails? = null
    }

    private fun popular() = CatalogRequest(CatalogKind.POPULAR)

    private fun page(book: OnlineBook) = CatalogPage(listOf(book), null)

    private fun book(key: String, title: String) = OnlineBook(
        stableKey = key,
        title = title,
        authors = listOf("Author $key"),
        summary = null,
        languages = listOf("en"),
        subjects = emptyList(),
        coverUrl = null,
        sourceRank = 1,
        popularity = null,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = listOf(SourceReference(key, key)),
        acquisitions = listOf(
            OnlineAcquisition(
                key,
                OnlineBookFormat.EPUB,
                "https://files.example/$key.epub",
                AcquisitionAccess.FREE_FULL,
                20,
            ),
        ),
    )

    private class MemoryCache : CatalogCache {
        private val values = mutableMapOf<String, CachedCatalogPage>()
        override suspend fun get(key: String): CachedCatalogPage? = values[key]
        override suspend fun put(key: String, value: CachedCatalogPage) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}

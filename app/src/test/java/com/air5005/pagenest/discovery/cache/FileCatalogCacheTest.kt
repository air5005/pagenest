package com.air5005.pagenest.discovery.cache

import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FileCatalogCacheTest {
    private lateinit var directory: java.io.File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("pagenest-catalog-cache").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `round trips a serializable catalog page`() = runTest {
        val cache = FileCatalogCache(directory)
        val value = CachedCatalogPage(1_000L, page("One"))

        cache.put("popular-en", value)

        assertEquals(value, cache.get("popular-en"))
        assertFalse(directory.listFiles().orEmpty().any { it.extension == "tmp" })
    }

    @Test
    fun `filename is sha256 and never contains query text`() = runTest {
        val cache = FileCatalogCache(directory)
        val request = CatalogRequest(CatalogKind.SEARCH, query = "private title query")
        val key = CatalogCacheKey.from(request)

        cache.put(key, CachedCatalogPage(1_000L, page("Result")))

        val name = directory.listFiles().orEmpty().single().name
        assertTrue(name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertFalse(name.contains("private"))
        assertFalse(name.contains("title"))
    }

    @Test
    fun `corrupt cache is deleted and never returned`() = runTest {
        val cache = FileCatalogCache(directory)
        val key = "corrupt-key"
        val cacheFile = java.io.File(directory, FileCatalogCache.fileNameForKey(key))
        cacheFile.writeText("not-json")

        assertNull(cache.get(key))
        assertFalse(cacheFile.exists())
    }

    @Test
    fun `oldest entries are evicted to enforce total cap`() = runTest {
        val cache = FileCatalogCache(directory, maxTotalBytes = 1_500)
        cache.put("old", CachedCatalogPage(100L, page("Old", "x".repeat(500))))
        cache.put("new", CachedCatalogPage(200L, page("New", "y".repeat(500))))

        assertNull(cache.get("old"))
        assertEquals("New", cache.get("new")!!.page.books.single().title)
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 1_500L)
    }

    @Test
    fun `remove deletes only the hashed target`() = runTest {
        val cache = FileCatalogCache(directory)
        cache.put("first", CachedCatalogPage(1L, page("First")))
        cache.put("second", CachedCatalogPage(2L, page("Second")))

        cache.remove("first")

        assertNull(cache.get("first"))
        assertEquals("Second", cache.get("second")!!.page.books.single().title)
    }

    private fun page(title: String, summary: String? = null) = CatalogPage(
        books = listOf(
            OnlineBook(
                stableKey = "source:$title",
                title = title,
                authors = listOf("Author"),
                summary = summary,
                languages = listOf("en"),
                subjects = emptyList(),
                coverUrl = null,
                sourceRank = 1,
                popularity = null,
                catalogUpdatedAtEpochMillis = null,
                rightsStatus = RightsStatus.PUBLIC_DOMAIN,
                sourceReferences = listOf(SourceReference("source", title)),
                acquisitions = listOf(
                    OnlineAcquisition(
                        "source",
                        OnlineBookFormat.EPUB,
                        "https://files.example/$title.epub",
                        AcquisitionAccess.FREE_FULL,
                        20,
                    ),
                ),
            ),
        ),
        nextPageToken = null,
    )
}

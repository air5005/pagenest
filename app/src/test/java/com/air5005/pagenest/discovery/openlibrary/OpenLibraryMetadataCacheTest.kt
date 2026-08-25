package com.air5005.pagenest.discovery.openlibrary

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenLibraryMetadataCacheTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("pagenest-openlibrary-cache").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `metadata round trips through atomic hashed file`() = runTest {
        val cache = FileOpenLibraryMetadataCache(directory)
        val value = CachedOpenLibraryMetadata(100L, metadata())

        cache.put("Pride and Prejudice|Jane Austen", value)

        assertEquals(value, cache.get("Pride and Prejudice|Jane Austen"))
        val files = directory.listFiles().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().name.matches(Regex("[0-9a-f]{64}\\.json")))
        assertFalse(files.single().name.contains("Pride"))
        assertFalse(files.any { it.extension == "tmp" })
    }

    @Test
    fun `corrupt metadata is deleted`() = runTest {
        val cache = FileOpenLibraryMetadataCache(directory)
        val key = "corrupt"
        val file = File(directory, FileOpenLibraryMetadataCache.fileNameForKey(key))
        file.writeText("not-json")

        assertNull(cache.get(key))
        assertFalse(file.exists())
    }

    @Test
    fun `negative result can be cached`() = runTest {
        val cache = FileOpenLibraryMetadataCache(directory)
        val value = CachedOpenLibraryMetadata(200L, metadata = null)

        cache.put("missing", value)

        assertEquals(value, cache.get("missing"))
    }

    @Test
    fun `entry exceeding configured total cap is evicted`() = runTest {
        val cache = FileOpenLibraryMetadataCache(directory, maxTotalBytes = 1)

        cache.put("too-large", CachedOpenLibraryMetadata(300L, metadata()))

        assertNull(cache.get("too-large"))
        assertTrue(directory.listFiles().orEmpty().sumOf { it.length() } <= 1L)
    }

    private fun metadata() = OpenLibraryMetadata(
        workId = "OL66554W",
        coverUrl = "https://covers.openlibrary.org/b/id/12345-L.jpg?default=false",
        firstPublishYear = 1813,
        editionCount = 752,
        publicScan = true,
        ebookAccess = OpenLibraryEbookAccess.PUBLIC,
        sourcePageUrl = "https://openlibrary.org/works/OL66554W",
    )
}

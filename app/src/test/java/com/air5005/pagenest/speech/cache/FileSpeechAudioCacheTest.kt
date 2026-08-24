package com.air5005.pagenest.speech.cache

import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class FileSpeechAudioCacheTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()
    private var requestGeneration = 0L

    @Test
    fun `cache evicts least recently used entries by real disk bytes and expires after 24 hours`() = runTest {
        val maxBytes = 180L
        val cache = cache(maxBytes = maxBytes, expiryMillis = 86_400_000)
        val first = request(text = "first", segmentId = "segment-a")
        val second = request(text = "second", segmentId = "segment-b")
        cache.putCurrent(first, ByteArray(50) { 1 }, nowMillis = 0)
        cache.putCurrent(second, ByteArray(50) { 2 }, nowMillis = 1)

        assertNull(cache.getCurrent(first, nowMillis = 2))
        assertArrayEquals(ByteArray(50) { 2 }, cache.getCurrent(second, nowMillis = 2)!!)
        assertTrue(cacheDirectoryBytes() <= maxBytes)
        assertNull(cache.getCurrent(second, nowMillis = 86_400_002))
        assertTrue(cacheFiles().isEmpty())
    }

    @Test
    fun `reading an item refreshes its LRU position`() = runTest {
        val cache = cache(maxBytes = 224)
        val first = request(text = "first", segmentId = "segment-a")
        val second = request(text = "second", segmentId = "segment-b")
        val third = request(text = "third", segmentId = "segment-c")
        cache.putCurrent(first, ByteArray(60) { 1 }, nowMillis = 0)
        cache.putCurrent(second, ByteArray(60) { 2 }, nowMillis = 1)
        assertArrayEquals(ByteArray(60) { 1 }, cache.getCurrent(first, nowMillis = 2)!!)

        cache.putCurrent(third, ByteArray(60) { 3 }, nowMillis = 3)

        assertNull(cache.getCurrent(second, nowMillis = 4))
        assertArrayEquals(ByteArray(60) { 1 }, cache.getCurrent(first, nowMillis = 4)!!)
        assertArrayEquals(ByteArray(60) { 3 }, cache.getCurrent(third, nowMillis = 4)!!)
    }

    @Test
    fun `switching book or chapter removes the previous scope before publication`() = runTest {
        val cache = cache()
        val oldBook = request(bookId = 11, chapterIndex = 2, text = "old-book")
        val newBook = request(bookId = 12, chapterIndex = 2, text = "new-book")
        val newChapter = request(bookId = 12, chapterIndex = 3, text = "new-chapter")
        cache.putCurrent(oldBook, byteArrayOf(1), nowMillis = 0)

        cache.putCurrent(newBook, byteArrayOf(2), nowMillis = 1)
        assertEquals(listOf("12/chapter-2"), cachedScopePaths())

        cache.putCurrent(newChapter, byteArrayOf(3), nowMillis = 2)
        assertEquals(listOf("12/chapter-3"), cachedScopePaths())
        assertArrayEquals(byteArrayOf(3), cache.getCurrent(newChapter, nowMillis = 2)!!)
        assertEquals(listOf("12", "chapter-3"), cacheFiles().single().relativeTo(cacheRoot()).parentFile!!.path.split(File.separator))
    }

    @Test
    fun `retaining a scope without reading or writing removes every other scope`() = runTest {
        val cache = cache()
        val old = request(bookId = 11, chapterIndex = 2, text = "old")
        val next = request(bookId = 12, chapterIndex = 4, text = "next")
        cache.putCurrent(old, byteArrayOf(1), nowMillis = 0)

        cache.retainScope(nextGeneration(), next)

        assertTrue(cacheFiles().isEmpty())
        assertFalse(File(cacheRoot(), "11/chapter-2").exists())
    }

    @Test
    fun `late operations from an older scope token cannot replace the latest scope`() = runTest {
        val cache = cache()
        val old = request(bookId = 11, chapterIndex = 2, text = "old")
        val latest = request(bookId = 12, chapterIndex = 4, text = "latest")
        val oldToken = cache.retainScope(nextGeneration(), old)
        val latestToken = cache.retainScope(nextGeneration(), latest)
        cache.put(latestToken, latest, byteArrayOf(2), nowMillis = 1)

        cache.put(oldToken, old, byteArrayOf(1), nowMillis = 2)
        cache.remove(oldToken, old)

        assertNull(cache.get(oldToken, old, nowMillis = 3))
        assertArrayEquals(byteArrayOf(2), cache.get(latestToken, latest, nowMillis = 3)!!)
        assertEquals(listOf("12/chapter-4"), cachedScopePaths())
    }

    @Test
    fun `corrupt cache entry is deleted instead of returned`() = runTest {
        val cache = cache()
        val request = request(text = "private chapter text")
        cache.putCurrent(request, byteArrayOf(1, 2, 3), nowMillis = 10)
        val published = cacheFiles().single()
        published.writeBytes(byteArrayOf(9, 8))

        assertNull(cache.getCurrent(request, nowMillis = 11))
        assertFalse(published.exists())
    }

    @Test
    fun `failed atomic publication keeps the previous entry and deletes temporary data`() = runTest {
        val request = request(text = "private chapter text")
        val normal = cache()
        normal.putCurrent(request, byteArrayOf(1, 2, 3), nowMillis = 10)
        val failing = cache(publisher = CacheFilePublisher { _, _ -> throw IOException("disk full") })

        runCatching { failing.putCurrent(request, byteArrayOf(4, 5, 6), nowMillis = 11) }

        assertArrayEquals(byteArrayOf(1, 2, 3), normal.getCurrent(request, nowMillis = 12)!!)
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `same key replacement counts published and staged bytes throughout atomic publication`() = runTest {
        val maxBytes = 300L
        val request = request(text = "replacement")
        val observedTotals = mutableListOf<Long>()
        var publications = 0
        val cache = cache(
            maxBytes = maxBytes,
            publisher = CacheFilePublisher { temporary, destination ->
                assertFalse(temporary.toPath().startsWith(cacheRoot().toPath()))
                observedTotals += combinedCacheBytes()
                assertTrue(combinedCacheBytes() <= maxBytes)
                publications++
                if (publications == 2) assertTrue(destination.exists())
                AtomicCacheFilePublisher.publish(temporary, destination)
                observedTotals += combinedCacheBytes()
            },
        )
        cache.putCurrent(request, ByteArray(80) { 1 }, nowMillis = 1)

        cache.putCurrent(request, ByteArray(100) { 2 }, nowMillis = 2)

        assertTrue(observedTotals.all { it <= maxBytes })
        assertTrue(combinedCacheBytes() <= maxBytes)
        assertArrayEquals(ByteArray(100) { 2 }, cache.getCurrent(request, nowMillis = 3)!!)
    }

    @Test
    fun `replacement without combined staging headroom retains the old value`() = runTest {
        val maxBytes = 160L
        val request = request(text = "replacement-without-headroom")
        var publications = 0
        val cache = cache(
            maxBytes = maxBytes,
            publisher = CacheFilePublisher { temporary, destination ->
                publications++
                AtomicCacheFilePublisher.publish(temporary, destination)
            },
        )
        cache.putCurrent(request, ByteArray(80) { 1 }, nowMillis = 1)

        cache.putCurrent(request, ByteArray(100) { 2 }, nowMillis = 2)

        assertEquals(1, publications)
        assertTrue(combinedCacheBytes() <= maxBytes)
        assertArrayEquals(ByteArray(80) { 1 }, cache.getCurrent(request, nowMillis = 3)!!)
    }

    @Test
    fun `retaining a full scope removes orphan staging bytes before admitting work`() = runTest {
        val maxBytes = 160L
        val request = request(text = "orphan")
        val cache = cache(maxBytes = maxBytes)
        cache.putCurrent(request, ByteArray(100) { 1 }, nowMillis = 1)
        val orphan = File(stagingRoot(), ".abandoned.tmp")
        orphan.parentFile!!.mkdirs()
        orphan.writeBytes(ByteArray(50) { 9 })
        assertTrue(combinedCacheBytes() > maxBytes)

        cache.retainScope(nextGeneration(), request)

        assertFalse(orphan.exists())
        assertTrue(combinedCacheBytes() <= maxBytes)
    }

    @Test
    fun `cache names contain only numeric scope and sha256 digest`() = runTest {
        val privateText = "secret novel sentence"
        val privateVoice = "private-voice-name"
        val cache = cache()
        cache.putCurrent(request(text = privateText, voiceId = privateVoice), byteArrayOf(1), nowMillis = 0)

        val relative = cacheFiles().single().relativeTo(cacheRoot()).invariantSeparatorsPath

        assertTrue(relative.matches(Regex("11/chapter-2/[0-9a-f]{64}\\.cache")))
        assertFalse(relative.contains(privateText))
        assertFalse(relative.contains(privateVoice))
    }

    private fun cache(
        maxBytes: Long = 128L * 1024 * 1024,
        expiryMillis: Long = 86_400_000,
        publisher: CacheFilePublisher = AtomicCacheFilePublisher,
    ) = FileSpeechAudioCache(cacheRoot(), maxBytes, expiryMillis, publisher)

    private fun cacheRoot() = File(temporaryFolder.root, "speech-cache")

    private fun stagingRoot() = File(temporaryFolder.root, "speech-cache-staging")

    private fun cacheFiles() = cacheRoot().walkTopDown().filter { it.isFile && it.extension == "cache" }.toList()

    private fun cacheDirectoryBytes() = cacheRoot().walkTopDown().filter(File::isFile).sumOf(File::length)

    private fun combinedCacheBytes() = sequenceOf(cacheRoot(), stagingRoot())
        .flatMap { root -> root.walkTopDown().filter(File::isFile) }
        .sumOf(File::length)

    private fun cachedScopePaths() = cacheFiles().map {
        it.parentFile!!.relativeTo(cacheRoot()).invariantSeparatorsPath
    }.distinct()

    private fun request(
        bookId: Long = 11,
        chapterIndex: Int = 2,
        text: String = "text",
        segmentId: String = "segment",
        voiceId: String = "zh-CN-XiaoxiaoNeural",
    ) = SpeechRequest(
        generationId = 1,
        segment = SpeechSegment(
            id = segmentId,
            position = SpeechPosition(bookId, chapterIndex, null, 3, 0),
            partIndex = 0,
            text = text,
            locator = Locator(text = "", progression = 0.25),
        ),
        localeTag = "zh-CN",
        voiceId = voiceId,
        rate = 1f,
        pitch = 1f,
    )

    private suspend fun FileSpeechAudioCache.putCurrent(
        request: SpeechRequest,
        audio: ByteArray,
        nowMillis: Long,
    ) {
        val token = retainScope(nextGeneration(), request)
        put(token, request, audio, nowMillis)
    }

    private suspend fun FileSpeechAudioCache.getCurrent(
        request: SpeechRequest,
        nowMillis: Long,
    ): ByteArray? {
        val token = retainScope(nextGeneration(), request)
        return get(token, request, nowMillis)
    }

    private fun nextGeneration(): Long = ++requestGeneration
}

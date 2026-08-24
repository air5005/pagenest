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

    @Test
    fun `cache evicts least recently used audio beyond capacity and expires after 24 hours`() = runTest {
        val cache = cache(maxBytes = 128, expiryMillis = 86_400_000)
        val first = request(text = "first", segmentId = "segment-a")
        val second = request(text = "second", segmentId = "segment-b")
        cache.put(first, ByteArray(80) { 1 }, nowMillis = 0)
        cache.put(second, ByteArray(60) { 2 }, nowMillis = 1)

        assertNull(cache.get(first, nowMillis = 2))
        assertArrayEquals(ByteArray(60) { 2 }, cache.get(second, nowMillis = 2)!!)
        assertNull(cache.get(second, nowMillis = 86_400_002))
        assertTrue(cacheFiles().isEmpty())
    }

    @Test
    fun `reading an item refreshes its LRU position`() = runTest {
        val cache = cache(maxBytes = 130)
        val first = request(text = "first", segmentId = "segment-a")
        val second = request(text = "second", segmentId = "segment-b")
        val third = request(text = "third", segmentId = "segment-c")
        cache.put(first, ByteArray(60) { 1 }, nowMillis = 0)
        cache.put(second, ByteArray(60) { 2 }, nowMillis = 1)
        assertArrayEquals(ByteArray(60) { 1 }, cache.get(first, nowMillis = 2)!!)

        cache.put(third, ByteArray(60) { 3 }, nowMillis = 3)

        assertNull(cache.get(second, nowMillis = 4))
        assertArrayEquals(ByteArray(60) { 1 }, cache.get(first, nowMillis = 4)!!)
        assertArrayEquals(ByteArray(60) { 3 }, cache.get(third, nowMillis = 4)!!)
    }

    @Test
    fun `switching book or chapter removes the previous scope before publication`() = runTest {
        val cache = cache()
        val oldBook = request(bookId = 11, chapterIndex = 2, text = "old-book")
        val newBook = request(bookId = 12, chapterIndex = 2, text = "new-book")
        val newChapter = request(bookId = 12, chapterIndex = 3, text = "new-chapter")
        cache.put(oldBook, byteArrayOf(1), nowMillis = 0)

        cache.put(newBook, byteArrayOf(2), nowMillis = 1)
        assertEquals(listOf("12/chapter-2"), cachedScopePaths())

        cache.put(newChapter, byteArrayOf(3), nowMillis = 2)
        assertEquals(listOf("12/chapter-3"), cachedScopePaths())
        assertArrayEquals(byteArrayOf(3), cache.get(newChapter, nowMillis = 2)!!)
        assertEquals(listOf("12", "chapter-3"), cacheFiles().single().relativeTo(cacheRoot()).parentFile!!.path.split(File.separator))
    }

    @Test
    fun `corrupt cache entry is deleted instead of returned`() = runTest {
        val cache = cache()
        val request = request(text = "private chapter text")
        cache.put(request, byteArrayOf(1, 2, 3), nowMillis = 10)
        val published = cacheFiles().single()
        published.writeBytes(byteArrayOf(9, 8))

        assertNull(cache.get(request, nowMillis = 11))
        assertFalse(published.exists())
    }

    @Test
    fun `failed atomic publication keeps the previous entry and deletes temporary data`() = runTest {
        val request = request(text = "private chapter text")
        val normal = cache()
        normal.put(request, byteArrayOf(1, 2, 3), nowMillis = 10)
        val failing = cache(publisher = CacheFilePublisher { _, _ -> throw IOException("disk full") })

        runCatching { failing.put(request, byteArrayOf(4, 5, 6), nowMillis = 11) }

        assertArrayEquals(byteArrayOf(1, 2, 3), normal.get(request, nowMillis = 12)!!)
        assertTrue(cacheRoot().walkTopDown().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `cache names contain only numeric scope and sha256 digest`() = runTest {
        val privateText = "secret novel sentence"
        val privateVoice = "private-voice-name"
        val cache = cache()
        cache.put(request(text = privateText, voiceId = privateVoice), byteArrayOf(1), nowMillis = 0)

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

    private fun cacheFiles() = cacheRoot().walkTopDown().filter { it.isFile && it.extension == "cache" }.toList()

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
}

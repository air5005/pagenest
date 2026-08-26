package com.wxn.base.diagnostics

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RotatingDiagnosticLogStoreTest {
    private lateinit var directory: java.io.File

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("pagenest-diagnostics").toFile()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `read recent returns newest entries first and honors limit`() {
        val store = RotatingDiagnosticLogStore(directory, maxFileBytes = 8_192, maxFiles = 4)
        listOf(30L, 10L, 20L).forEach {
            store.append(entry(it, "message-$it"))
        }

        assertEquals(listOf(30L, 20L), store.readRecent(2).map { it.timestampEpochMillis })
    }

    @Test
    fun `append rotates before overflow and retains only configured files`() {
        val store = RotatingDiagnosticLogStore(directory, maxFileBytes = 180, maxFiles = 3)

        repeat(30) { store.append(entry(it.toLong(), "message-$it-${"x".repeat(30)}")) }

        val files = directory.listFiles().orEmpty().filter { it.isFile }
        assertTrue(files.isNotEmpty())
        assertTrue(files.size <= 3)
        assertTrue(files.all { it.name.matches(Regex("pagenest-[0-2]\\.log")) })
        assertTrue(files.all { it.length() <= 180L })
        assertTrue(store.totalBytes() <= 540L)
        assertEquals(29L, store.readRecent(500).first().timestampEpochMillis)
    }

    @Test
    fun `startup removes unexpected files and read skips corrupt records`() {
        java.io.File(directory, "unknown.log").writeText("private")
        java.io.File(directory, "pagenest-0.log").writeText(
            "broken\n${DiagnosticLogCodec.encode(entry(5L, "valid"))}\n",
        )

        val store = RotatingDiagnosticLogStore(directory)

        assertFalse(java.io.File(directory, "unknown.log").exists())
        assertEquals(listOf("valid"), store.readRecent(500).map { it.message })
    }

    @Test
    fun `clear removes all retained entries and resets total bytes`() {
        val store = RotatingDiagnosticLogStore(directory)
        store.append(entry(1L, "one"))
        store.append(entry(2L, "two"))

        store.clear()

        assertTrue(store.readRecent(500).isEmpty())
        assertEquals(0L, store.totalBytes())
    }

    @Test
    fun `concurrent appends do not lose or merge records`() {
        val store = RotatingDiagnosticLogStore(directory, maxFileBytes = 1_048_576, maxFiles = 4)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(100)
        repeat(100) { index ->
            pool.execute {
                start.await()
                store.append(entry(index.toLong(), "entry-$index"))
                done.countDown()
            }
        }

        start.countDown()
        done.await()
        pool.shutdownNow()

        assertEquals(100, store.readRecent(500).map { it.message }.toSet().size)
    }

    private fun entry(timestamp: Long, message: String) = DiagnosticLogEntry(
        timestampEpochMillis = timestamp,
        level = DiagnosticLevel.RUNNING,
        category = "TEST",
        message = message,
    )
}

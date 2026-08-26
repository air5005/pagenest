package com.wxn.base.diagnostics

import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogWriterTest {
    private val executor = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun `log sanitizes before duplicate throttling and reports suppression after window`() {
        val store = RecordingStore()
        var now = 1_000L
        val writer = DiagnosticLogWriter(
            store = store,
            throttle = DiagnosticLogThrottle(windowMillis = 10_000),
            executor = executor,
            clock = { now },
        )

        writer.log(DiagnosticLevel.WARNING, "AUTH", "token=first")
        writer.flush()
        writer.log(DiagnosticLevel.WARNING, "AUTH", "token=second")
        writer.flush()
        now = 11_001L
        writer.log(DiagnosticLevel.WARNING, "AUTH", "token=third")
        writer.flush()

        assertEquals(2, store.entries.size)
        assertTrue(store.entries.all { !it.message.contains("first") && !it.message.contains("second") && !it.message.contains("third") })
        assertTrue(store.entries.last().message.contains("suppressed 1 duplicate"))
    }

    @Test
    fun `read and total wait for queued writes and clear removes entries`() {
        val store = RecordingStore()
        val writer = DiagnosticLogWriter(store, executor = executor, clock = { 50L })

        writer.log(DiagnosticLevel.RUNNING, "APP", "started")

        assertEquals(listOf("started"), writer.readRecent(500).map { it.message })
        assertTrue(writer.totalBytes() > 0)
        writer.clear()
        assertTrue(writer.readRecent(500).isEmpty())
    }

    @Test
    fun `store failures never escape caller and later calls still execute`() {
        val store = RecordingStore(failAppends = true)
        val writer = DiagnosticLogWriter(store, executor = executor)

        writer.log(DiagnosticLevel.ERROR, "STORE", "first")
        writer.flush()
        store.failAppends = false
        writer.log(DiagnosticLevel.ERROR, "STORE", "second")
        writer.flush()

        assertEquals(listOf("second"), store.entries.map { it.message })
    }

    @Test
    fun `synchronous crash write appends and flushes before returning`() {
        val store = RecordingStore()
        val writer = DiagnosticLogWriter(store, executor = executor, clock = { 99L })

        writer.logSynchronously(
            DiagnosticLevel.ERROR,
            "CRASH",
            "uncaught",
            IllegalStateException("api_key=secret"),
        )

        assertEquals(1, store.entries.size)
        assertEquals(1, store.flushCalls)
        assertTrue(store.entries.single().message.contains("[REDACTED]"))
        assertTrue(!store.entries.single().message.contains("secret"))
    }

    private class RecordingStore(
        var failAppends: Boolean = false,
    ) : DiagnosticLogStore {
        val entries = mutableListOf<DiagnosticLogEntry>()
        var flushCalls = 0

        override fun append(entry: DiagnosticLogEntry) {
            if (failAppends) error("disk full")
            entries += entry
        }

        override fun readRecent(limit: Int) = entries.sortedWith(DiagnosticLogEntry.NEWEST_FIRST).take(limit)
        override fun clear() = entries.clear()
        override fun totalBytes() = entries.sumOf { it.message.length.toLong() }
        override fun flush() { flushCalls++ }
    }
}

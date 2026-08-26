package com.wxn.base.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticLogCodecTest {
    @Test
    fun `round trip preserves fields while keeping one physical line`() {
        val entry = DiagnosticLogEntry(
            timestampEpochMillis = 1_725_000_123_456L,
            level = DiagnosticLevel.WARNING,
            category = "BOOK\tIMPORT",
            message = "first line\nsecond line\r\nthird line",
        )

        val encoded = DiagnosticLogCodec.encode(entry)

        assertFalse(encoded.contains('\n'))
        assertFalse(encoded.contains('\r'))
        assertEquals(entry, DiagnosticLogCodec.decode(encoded))
    }

    @Test
    fun `newest first comparator orders higher timestamps before lower timestamps`() {
        val older = DiagnosticLogEntry(10L, DiagnosticLevel.RUNNING, "APP", "older")
        val newer = DiagnosticLogEntry(20L, DiagnosticLevel.ERROR, "APP", "newer")

        assertEquals(listOf(newer, older), listOf(older, newer).sortedWith(DiagnosticLogEntry.NEWEST_FIRST))
    }

    @Test
    fun `decode ignores malformed timestamp level field count and base64`() {
        assertNull(DiagnosticLogCodec.decode("not-a-record"))
        assertNull(DiagnosticLogCodec.decode("time\tRUNNING\tQQ==\tQg=="))
        assertNull(DiagnosticLogCodec.decode("1\tDEBUG\tQQ==\tQg=="))
        assertNull(DiagnosticLogCodec.decode("1\tRUNNING\t***\tQg=="))
    }
}

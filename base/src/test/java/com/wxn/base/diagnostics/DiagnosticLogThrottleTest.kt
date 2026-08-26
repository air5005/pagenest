package com.wxn.base.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogThrottleTest {
    @Test
    fun `first entry is accepted and duplicate inside window is suppressed`() {
        val throttle = DiagnosticLogThrottle(windowMillis = 10_000)
        val entry = entry(DiagnosticLevel.WARNING, "NETWORK", "offline")

        val first = throttle.accept(entry, nowMillis = 1_000)
        val duplicate = throttle.accept(entry, nowMillis = 5_000)

        assertTrue(first.accepted)
        assertEquals(0, first.suppressedDuplicates)
        assertFalse(duplicate.accepted)
    }

    @Test
    fun `entry after window reports number of suppressed duplicates`() {
        val throttle = DiagnosticLogThrottle(windowMillis = 10_000)
        val entry = entry(DiagnosticLevel.ERROR, "IMPORT", "failed")
        throttle.accept(entry, 0)
        throttle.accept(entry, 1_000)
        throttle.accept(entry, 2_000)

        val accepted = throttle.accept(entry, 10_001)

        assertTrue(accepted.accepted)
        assertEquals(2, accepted.suppressedDuplicates)
    }

    @Test
    fun `different level category or message is accepted independently`() {
        val throttle = DiagnosticLogThrottle(windowMillis = 10_000)
        throttle.accept(entry(DiagnosticLevel.WARNING, "IMPORT", "failed"), 0)

        assertTrue(throttle.accept(entry(DiagnosticLevel.ERROR, "IMPORT", "failed"), 1).accepted)
        assertTrue(throttle.accept(entry(DiagnosticLevel.WARNING, "NETWORK", "failed"), 1).accepted)
        assertTrue(throttle.accept(entry(DiagnosticLevel.WARNING, "IMPORT", "retry"), 1).accepted)
    }

    private fun entry(level: DiagnosticLevel, category: String, message: String) = DiagnosticLogEntry(
        timestampEpochMillis = 0,
        level = level,
        category = category,
        message = message,
    )
}

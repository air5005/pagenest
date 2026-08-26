package com.wxn.base.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class DiagnosticCrashHandlerTest {
    @Test
    fun `crash is recorded before delegation`() {
        val calls = mutableListOf<String>()
        val throwable = IllegalStateException("boom")
        val delegate = Thread.UncaughtExceptionHandler { _, delegated ->
            calls += "delegate"
            assertSame(throwable, delegated)
        }
        val handler = DiagnosticCrashHandler(
            recordCrash = {
                calls += "record"
                assertSame(throwable, it)
            },
            delegate = delegate,
        )

        handler.uncaughtException(Thread.currentThread(), throwable)

        assertEquals(listOf("record", "delegate"), calls)
    }

    @Test
    fun `recording failure does not swallow original crash delegation`() {
        var delegated: Throwable? = null
        val throwable = IllegalArgumentException("original")
        val handler = DiagnosticCrashHandler(
            recordCrash = { error("logging failed") },
            delegate = Thread.UncaughtExceptionHandler { _, error -> delegated = error },
        )

        handler.uncaughtException(Thread.currentThread(), throwable)

        assertSame(throwable, delegated)
    }
}

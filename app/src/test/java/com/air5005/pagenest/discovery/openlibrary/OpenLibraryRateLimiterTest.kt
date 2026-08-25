package com.air5005.pagenest.discovery.openlibrary

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class OpenLibraryRateLimiterTest {

    @Test
    fun `requests start at least one second apart`() = runTest {
        var now = 0L
        val starts = mutableListOf<Long>()
        val limiter = OpenLibraryRateLimiter(
            nowMillis = { now },
            delayMillis = { duration -> now += duration },
        )

        limiter.run { starts += now }
        limiter.run { starts += now }
        limiter.run { starts += now }

        assertEquals(listOf(0L, 1_000L, 2_000L), starts)
    }

    @Test
    fun `cancellation during rate limit delay propagates unchanged`() = runTest {
        var now = 0L
        val marker = CancellationException("cancelled")
        val limiter = OpenLibraryRateLimiter(
            nowMillis = { now },
            delayMillis = { throw marker },
        )
        limiter.run { now = 1L }

        val thrown = try {
            limiter.run { Unit }
            throw AssertionError("Expected cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(marker, thrown)
    }
}

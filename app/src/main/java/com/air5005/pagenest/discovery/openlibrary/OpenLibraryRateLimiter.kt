package com.air5005.pagenest.discovery.openlibrary

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenLibraryRateLimiter(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val minimumIntervalMillis: Long = DEFAULT_MINIMUM_INTERVAL_MILLIS,
) {
    private val mutex = Mutex()
    private var lastStartMillis: Long? = null

    init {
        require(minimumIntervalMillis >= 0) { "Rate limit interval must not be negative" }
    }

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val lastStart = lastStartMillis
        if (lastStart != null) {
            val remaining = lastStart + minimumIntervalMillis - nowMillis()
            if (remaining > 0) delayMillis(remaining)
        }
        lastStartMillis = nowMillis()
        block()
    }

    companion object {
        const val DEFAULT_MINIMUM_INTERVAL_MILLIS = 1_000L
    }
}

package com.air5005.pagenest.library.importing

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes the complete post-copy import transaction for one SHA-256 value.
 *
 * Task 7's production adapter must combine process-local coordination with an app-private
 * operating-system lock so separate PageNest processes use the same critical section. An
 * implementation may throw while acquiring the lock, but after [block] starts it must release
 * the lock without replacing a successful block result or a block failure with an unlock error.
 */
interface BookImportCoordinator {
    suspend fun <T> withHashLock(sha256: String, block: suspend () -> T): T
}

/** Process-local coordinator for single-process callers and tests. */
class InProcessBookImportCoordinator(stripeCount: Int = DEFAULT_STRIPE_COUNT) :
    BookImportCoordinator {
    private val locks: Array<Mutex>

    init {
        require(stripeCount > 0) { "stripeCount must be positive" }
        locks = Array(stripeCount) { Mutex() }
    }

    override suspend fun <T> withHashLock(
        sha256: String,
        block: suspend () -> T,
    ): T {
        val lock = locks[Math.floorMod(sha256.hashCode(), locks.size)]
        lock.lock()
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    private companion object {
        const val DEFAULT_STRIPE_COUNT = 64
    }
}

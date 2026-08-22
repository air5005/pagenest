package com.air5005.pagenest.library.importing

import kotlinx.coroutines.sync.Mutex

/**
 * Serializes the complete post-copy import transaction for one SHA-256 value.
 *
 * Task 7's production adapter must acquire one process-singleton mutex before opening a
 * persistent app-private per-SHA lock file. That lock file is never unlinked. OS-lock acquisition
 * must be cancellable, and both the process and OS locks stay held across the suspending [block].
 * Release and channel close run in a non-cancellable context; their failures are suppressed and
 * must never replace a committed [block] result or its primary failure. Acquisition may fail only
 * before [block] starts. The adapter requires real child-process exclusion, acquisition
 * cancellation/failure, and unlock/channel-close failure tests.
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

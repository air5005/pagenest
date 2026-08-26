package com.wxn.base.diagnostics

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class DiagnosticLogWriter(
    private val store: DiagnosticLogStore,
    private val throttle: DiagnosticLogThrottle = DiagnosticLogThrottle(),
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pagenest-diagnostics").apply { isDaemon = true }
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun log(
        level: DiagnosticLevel,
        category: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val entry = safeEntry(level, category, message, throwable)
        runCatching {
            executor.execute { appendThrottled(entry) }
        }
    }

    fun logSynchronously(
        level: DiagnosticLevel,
        category: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val entry = safeEntry(level, category, message, throwable)
        runCatching { store.append(entry) }
        runCatching { store.flush() }
    }

    fun readRecent(limit: Int): List<DiagnosticLogEntry> = runAfterQueued(emptyList()) {
        store.readRecent(limit)
    }

    fun clear() {
        runAfterQueued(Unit) { store.clear() }
    }

    fun totalBytes(): Long = runAfterQueued(0L) { store.totalBytes() }

    fun flush() {
        runAfterQueued(Unit) { store.flush() }
    }

    private fun appendThrottled(entry: DiagnosticLogEntry) {
        runCatching {
            val decision = throttle.accept(entry, entry.timestampEpochMillis)
            if (!decision.accepted) return
            val stored = if (decision.suppressedDuplicates > 0) {
                entry.copy(
                    message = "${entry.message} (suppressed ${decision.suppressedDuplicates} duplicate logs)",
                )
            } else {
                entry
            }
            store.append(stored)
        }
    }

    private fun safeEntry(
        level: DiagnosticLevel,
        category: String,
        message: String,
        throwable: Throwable?,
    ): DiagnosticLogEntry {
        val combined = buildString {
            append(message)
            if (throwable != null) {
                append('\n')
                append(DiagnosticSanitizer.sanitize(throwable))
            }
        }
        return DiagnosticLogEntry(
            timestampEpochMillis = clock(),
            level = level,
            category = DiagnosticSanitizer.sanitize(category).take(80),
            message = DiagnosticSanitizer.sanitize(combined),
        )
    }

    private fun <T> runAfterQueued(fallback: T, block: () -> T): T = runCatching {
        executor.submit<T> { runCatching(block).getOrDefault(fallback) }.get()
    }.getOrDefault(fallback)
}

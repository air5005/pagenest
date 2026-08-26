package com.wxn.base.diagnostics

class DiagnosticCrashHandler(
    private val recordCrash: (Throwable) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            recordCrash(throwable)
        } catch (_: Throwable) {
            // Diagnostics must never replace or swallow the original crash.
        } finally {
            delegate?.uncaughtException(thread, throwable)
        }
    }
}

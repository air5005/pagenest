package com.wxn.reader.presentation.mainReader

class PendingReaderProgress {
    private var request: Request? = null

    @Synchronized
    fun remember(bookId: Long, progress: Double) {
        request = Request(bookId, progress.coerceIn(0.0, 1.0))
    }

    @Synchronized
    fun consume(bookId: Long): Double? {
        val current = request?.takeIf { it.bookId == bookId } ?: return null
        request = null
        return current.progress
    }

    @Synchronized
    fun switchTo(bookId: Long) {
        if (request?.bookId != bookId) request = null
    }

    private data class Request(val bookId: Long, val progress: Double)
}

package com.wxn.reader.presentation.mainReader

internal object BookWordIndexPolicy {
    fun shouldStart(
        wordCount: Long,
        requestedBookId: Long,
        runningBookId: Long?,
        isRunning: Boolean,
    ): Boolean = wordCount <= 0L && !(isRunning && runningBookId == requestedBookId)
}

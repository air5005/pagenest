package com.wxn.reader.presentation.mainReader

class ChapterWordCalculationStatus {
    @Volatile
    var isRunning: Boolean = false
        private set

    suspend fun <T> track(block: suspend () -> T): T {
        isRunning = true
        return try {
            block()
        } finally {
            isRunning = false
        }
    }
}

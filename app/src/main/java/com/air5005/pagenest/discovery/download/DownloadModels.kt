package com.air5005.pagenest.discovery.download

import com.air5005.pagenest.discovery.model.OnlineBookFormat
import java.io.File

data class DownloadRequest(
    val sourceId: String,
    val url: String,
    val format: OnlineBookFormat,
)

data class DownloadProgress(
    val bytesRead: Long,
    val totalBytes: Long?,
    val stage: DownloadStage = DownloadStage.DOWNLOADING,
)

enum class DownloadStage {
    DOWNLOADING,
    VALIDATING,
}

data class DownloadedBook(
    val file: File,
    val format: OnlineBookFormat,
)

enum class DownloadFailure {
    UNSAFE_URL,
    REDIRECT_LIMIT,
    HTTP_UNAUTHORIZED,
    NOT_FOUND,
    RETRYABLE,
    HTTP_ERROR,
    RESPONSE_TOO_LARGE,
    FORMAT_MISMATCH,
    IO,
}

sealed interface DownloadResult {
    data class Success(val book: DownloadedBook) : DownloadResult
    data class Failure(val reason: DownloadFailure) : DownloadResult
}

package com.air5005.pagenest.discovery.importing

import com.air5005.pagenest.discovery.download.DownloadFailure
import com.air5005.pagenest.discovery.download.DownloadProgress

sealed interface OnlineImportResult {
    data class Added(
        val bookId: Long,
        val duplicate: Boolean,
    ) : OnlineImportResult

    data class Failed(val reason: OnlineImportFailure) : OnlineImportResult
}

enum class OnlineImportFailure {
    NO_ELIGIBLE_ACQUISITION,
    UNSAFE_URL,
    REDIRECT_LIMIT,
    UNAUTHORIZED,
    NOT_FOUND,
    NETWORK,
    HTTP,
    RESPONSE_TOO_LARGE,
    FORMAT_MISMATCH,
    UNSUPPORTED_FORMAT,
    PROTECTED,
    UNREADABLE,
    PARSE_FAILED,
    STORAGE_FAILED,
    IMPORT_FAILED;

    companion object {
        fun fromDownload(failure: DownloadFailure): OnlineImportFailure = when (failure) {
            DownloadFailure.UNSAFE_URL -> UNSAFE_URL
            DownloadFailure.REDIRECT_LIMIT -> REDIRECT_LIMIT
            DownloadFailure.HTTP_UNAUTHORIZED -> UNAUTHORIZED
            DownloadFailure.NOT_FOUND -> NOT_FOUND
            DownloadFailure.RETRYABLE,
            DownloadFailure.IO,
            -> NETWORK
            DownloadFailure.HTTP_ERROR -> HTTP
            DownloadFailure.RESPONSE_TOO_LARGE -> RESPONSE_TOO_LARGE
            DownloadFailure.FORMAT_MISMATCH -> FORMAT_MISMATCH
        }
    }
}

sealed interface OnlineImportProgress {
    data class Downloading(val progress: DownloadProgress) : OnlineImportProgress
    data object Validating : OnlineImportProgress
    data object Importing : OnlineImportProgress
}

package com.air5005.pagenest.discovery.importing

import com.air5005.pagenest.discovery.download.DownloadFailure
import com.air5005.pagenest.discovery.download.DownloadRequest
import com.air5005.pagenest.discovery.download.DownloadResult
import com.air5005.pagenest.discovery.download.DownloadStage
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.library.importing.ImportRejection
import com.air5005.pagenest.library.importing.ImportResult
import com.wxn.base.util.Logger
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface OnlineImportCoordinator {
    suspend fun import(
        book: OnlineBook,
        onProgress: (OnlineImportProgress) -> Unit = {},
    ): OnlineImportResult
}

class OnlineBookImportCoordinator(
    private val downloader: OnlineBookDownloader,
    private val importer: OnlineBookImporter,
    private val ledger: OnlineImportLedger,
    private val localBookLookup: LocalBookLookup,
) : OnlineImportCoordinator {
    private val keyedMutex = RefCountedKeyedMutex()

    override suspend fun import(
        book: OnlineBook,
        onProgress: (OnlineImportProgress) -> Unit,
    ): OnlineImportResult {
        Logger.running("ONLINE_IMPORT", "Online import started")
        val outcome = keyedMutex.withLock(book.stableKey) {
            findExisting(book.stableKey)?.let { return@withLock it }
            val candidates = eligibleCandidates(book)
            if (candidates.isEmpty()) {
                return@withLock OnlineImportResult.Failed(OnlineImportFailure.NO_ELIGIBLE_ACQUISITION)
            }

            var lastFailure = OnlineImportFailure.NETWORK
            for (candidate in candidates) {
                var retries = 0
                while (true) {
                    val result = downloader.download(
                        DownloadRequest(candidate.sourceId, candidate.url, candidate.format),
                    ) { progress ->
                        onProgress(
                            if (progress.stage == DownloadStage.VALIDATING) {
                                OnlineImportProgress.Validating
                            } else {
                                OnlineImportProgress.Downloading(progress)
                            },
                        )
                    }
                    when (result) {
                        is DownloadResult.Success -> return@withLock importDownloaded(
                            book = book,
                            downloaded = result.book.file,
                            format = result.book.format,
                            onProgress = onProgress,
                        )
                        is DownloadResult.Failure -> {
                            lastFailure = OnlineImportFailure.fromDownload(result.reason)
                            if (result.reason.isTerminal()) {
                                return@withLock OnlineImportResult.Failed(lastFailure)
                            }
                            if (result.reason.isRetryable() && retries < MAX_RETRIES_PER_CANDIDATE) {
                                retries++
                                continue
                            }
                        }
                    }
                    break
                }
            }
            OnlineImportResult.Failed(lastFailure)
        }
        when (outcome) {
            is OnlineImportResult.Added -> Logger.running(
                "ONLINE_IMPORT",
                "Online import completed duplicate=${outcome.duplicate}",
            )
            is OnlineImportResult.Failed -> Logger.warning(
                "ONLINE_IMPORT",
                "Online import failed reason=${outcome.reason.name}",
            )
        }
        return outcome
    }

    private suspend fun findExisting(stableKey: String): OnlineImportResult.Added? {
        val bookId = ledger.get(stableKey) ?: return null
        if (localBookLookup.exists(bookId)) {
            return OnlineImportResult.Added(bookId, duplicate = true)
        }
        ledger.remove(stableKey)
        return null
    }

    private suspend fun importDownloaded(
        book: OnlineBook,
        downloaded: File,
        format: OnlineBookFormat,
        onProgress: (OnlineImportProgress) -> Unit,
    ): OnlineImportResult = try {
        onProgress(OnlineImportProgress.Importing)
        when (val result = importer.import(downloaded, safeDisplayName(book.title, format))) {
            is ImportResult.Imported -> {
                ledger.put(book.stableKey, result.bookId)
                OnlineImportResult.Added(result.bookId, duplicate = false)
            }
            is ImportResult.Duplicate -> {
                ledger.put(book.stableKey, result.bookId)
                OnlineImportResult.Added(result.bookId, duplicate = true)
            }
            is ImportResult.Rejected -> OnlineImportResult.Failed(result.reason.toOnlineFailure())
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        OnlineImportResult.Failed(OnlineImportFailure.IMPORT_FAILED)
    } finally {
        try {
            Files.deleteIfExists(downloaded.toPath())
        } catch (_: Exception) {
            // Staging startup cleanup is the bounded fallback for an exceptional delete failure.
        }
    }

    private fun eligibleCandidates(book: OnlineBook): List<OnlineAcquisition> {
        if (book.rightsStatus !in DOWNLOADABLE_RIGHTS) return emptyList()
        return book.acquisitions
            .asSequence()
            .filter { it.canReadDirectly && it.format in SUPPORTED_FORMATS }
            .distinctBy { it.sourceId to it.url }
            .sortedWith(
                compareBy<OnlineAcquisition> { it.qualityPriority }
                    .thenBy { it.format.ordinal }
                    .thenBy { it.sourceId }
                    .thenBy { it.url },
            )
            .toList()
    }

    private fun safeDisplayName(title: String, format: OnlineBookFormat): String {
        val base = title
            .replace(INVALID_FILE_NAME_CHARS, "_")
            .trim(' ', '.')
            .take(MAX_DISPLAY_NAME_LENGTH)
            .ifBlank { "online-book" }
        return "$base.${format.name.lowercase()}"
    }

    private fun DownloadFailure.isRetryable(): Boolean =
        this == DownloadFailure.RETRYABLE || this == DownloadFailure.IO

    private fun DownloadFailure.isTerminal(): Boolean = when (this) {
        DownloadFailure.UNSAFE_URL,
        DownloadFailure.REDIRECT_LIMIT,
        DownloadFailure.HTTP_UNAUTHORIZED,
        DownloadFailure.RESPONSE_TOO_LARGE,
        DownloadFailure.FORMAT_MISMATCH,
        -> true
        else -> false
    }

    private fun ImportRejection.toOnlineFailure(): OnlineImportFailure = when (this) {
        ImportRejection.UNSUPPORTED_FORMAT -> OnlineImportFailure.UNSUPPORTED_FORMAT
        ImportRejection.PROTECTED -> OnlineImportFailure.PROTECTED
        ImportRejection.UNREADABLE -> OnlineImportFailure.UNREADABLE
        ImportRejection.PARSE_FAILED -> OnlineImportFailure.PARSE_FAILED
        ImportRejection.STORAGE_FAILED -> OnlineImportFailure.STORAGE_FAILED
    }

    companion object {
        private const val MAX_RETRIES_PER_CANDIDATE = 1
        private const val MAX_DISPLAY_NAME_LENGTH = 120
        private val INVALID_FILE_NAME_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]")
        private val SUPPORTED_FORMATS = setOf(
            OnlineBookFormat.EPUB,
            OnlineBookFormat.TXT,
            OnlineBookFormat.PDF,
        )
        private val DOWNLOADABLE_RIGHTS = setOf(
            RightsStatus.PUBLIC_DOMAIN,
            RightsStatus.FREE_FULL,
        )
    }
}

private class RefCountedKeyedMutex {
    private data class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val entries = ConcurrentHashMap<String, Entry>()

    suspend fun <T> withLock(key: String, block: suspend () -> T): T {
        val entry = entries.compute(key) { _, current ->
            (current ?: Entry()).also { it.users++ }
        }!!
        return try {
            entry.mutex.withLock { block() }
        } finally {
            entries.computeIfPresent(key) { _, current ->
                current.apply { users-- }.takeIf { it.users > 0 }
            }
        }
    }
}

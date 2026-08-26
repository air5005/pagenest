package com.air5005.pagenest.discovery.importing

import com.air5005.pagenest.discovery.download.DownloadFailure
import com.air5005.pagenest.discovery.download.DownloadProgress
import com.air5005.pagenest.discovery.download.DownloadRequest
import com.air5005.pagenest.discovery.download.DownloadResult
import com.air5005.pagenest.discovery.download.DownloadedBook
import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.library.importing.ImportRejection
import com.air5005.pagenest.library.importing.ImportResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OnlineBookImportCoordinatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `filters sorts retries recoverable failure once then falls back and imports`() = runBlocking {
        val staged = temporaryFolder.newFile("download.txt").apply { writeText("hello") }
        val downloader = FakeDownloader(
            DownloadResult.Failure(DownloadFailure.RETRYABLE),
            DownloadResult.Failure(DownloadFailure.RETRYABLE),
            DownloadResult.Success(DownloadedBook(staged, OnlineBookFormat.TXT)),
        )
        val ledger = MemoryLedger()
        val importer = FakeImporter(ImportResult.Imported(42L))
        val coordinator = coordinator(downloader, importer, ledger)
        val book = book(
            acquisitions = listOf(
                acquisition("https://www.gutenberg.org/preview", OnlineBookFormat.EPUB, 0, AcquisitionAccess.PREVIEW),
                acquisition("https://www.gutenberg.org/book.html", OnlineBookFormat.HTML, 1),
                acquisition("https://www.gutenberg.org/book.epub", OnlineBookFormat.EPUB, 10),
                acquisition("https://www.gutenberg.org/book.txt", OnlineBookFormat.TXT, 20),
            ),
        )

        val result = coordinator.import(book)

        assertEquals(OnlineImportResult.Added(42L, duplicate = false), result)
        assertEquals(listOf(OnlineBookFormat.EPUB, OnlineBookFormat.EPUB, OnlineBookFormat.TXT), downloader.requests.map { it.format })
        assertEquals(1, importer.calls)
        assertEquals(listOf("A _ Test_ Book.txt"), importer.displayNames)
        assertFalse(staged.exists())
        assertEquals(42L, ledger.get(book.stableKey))
    }

    @Test
    fun `unsafe format and authorization failures stop fallback`() = runBlocking {
        val terminalFailures = listOf(
            DownloadFailure.UNSAFE_URL,
            DownloadFailure.RESPONSE_TOO_LARGE,
            DownloadFailure.FORMAT_MISMATCH,
            DownloadFailure.HTTP_UNAUTHORIZED,
        )
        terminalFailures.forEach { failure ->
            val downloader = FakeDownloader(DownloadResult.Failure(failure))
            val result = coordinator(downloader).import(book())
            assertEquals(OnlineImportResult.Failed(OnlineImportFailure.fromDownload(failure)), result)
            assertEquals(1, downloader.requests.size)
        }
    }

    @Test
    fun `unclear book rights prevent every acquisition attempt`() = runBlocking {
        val downloader = FakeDownloader()

        val result = coordinator(downloader).import(book().copy(rightsStatus = RightsStatus.UNKNOWN))

        assertEquals(
            OnlineImportResult.Failed(OnlineImportFailure.NO_ELIGIBLE_ACQUISITION),
            result,
        )
        assertTrue(downloader.requests.isEmpty())
    }

    @Test
    fun `valid ledger hit skips download while stale hit is removed and reacquired`() = runBlocking {
        val validLedger = MemoryLedger(mutableMapOf("gutenberg:1" to 7L))
        val unusedDownloader = FakeDownloader()
        assertEquals(
            OnlineImportResult.Added(7L, duplicate = true),
            coordinator(unusedDownloader, ledger = validLedger, existingBookIds = mutableSetOf(7L)).import(book()),
        )
        assertTrue(unusedDownloader.requests.isEmpty())

        val staged = temporaryFolder.newFile("stale.txt").apply { writeText("hello") }
        val staleLedger = MemoryLedger(mutableMapOf("gutenberg:1" to 8L))
        val downloader = FakeDownloader(DownloadResult.Success(DownloadedBook(staged, OnlineBookFormat.TXT)))
        val result = coordinator(
            downloader,
            FakeImporter(ImportResult.Duplicate(9L)),
            staleLedger,
            existingBookIds = mutableSetOf(),
        ).import(book())
        assertEquals(OnlineImportResult.Added(9L, duplicate = true), result)
        assertEquals(9L, staleLedger.get("gutenberg:1"))
    }

    @Test
    fun `same stable key shares completed work and cancelled waiter does not cancel owner`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val staged = temporaryFolder.newFile("shared.txt").apply { writeText("hello") }
        val downloader = object : OnlineBookDownloader {
            var calls = 0
            override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                calls++
                entered.complete(Unit)
                release.await()
                return DownloadResult.Success(DownloadedBook(staged, OnlineBookFormat.TXT))
            }
        }
        val ledger = MemoryLedger()
        val existing = mutableSetOf<Long>()
        val importer = object : OnlineBookImporter {
            override suspend fun import(file: File, displayName: String): ImportResult {
                existing += 17L
                return ImportResult.Imported(17L)
            }
        }
        val coordinator = coordinator(downloader, importer, ledger, existing)
        val owner = async { coordinator.import(book()) }
        entered.await()
        val waiter = async { coordinator.import(book()) }
        yield()
        waiter.cancelAndJoin()
        release.complete(Unit)

        assertEquals(OnlineImportResult.Added(17L, duplicate = false), owner.await())
        assertEquals(1, downloader.calls)
        assertEquals(17L, ledger.get("gutenberg:1"))
    }

    @Test
    fun `different stable keys download independently`() = runBlocking {
        val bothEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val downloader = object : OnlineBookDownloader {
            var calls = 0
            override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): DownloadResult {
                calls++
                if (calls == 2) bothEntered.complete(Unit)
                release.await()
                val file = File.createTempFile("parallel-", ".txt", temporaryFolder.root).apply { writeText("hello") }
                return DownloadResult.Success(DownloadedBook(file, OnlineBookFormat.TXT))
            }
        }
        val coordinator = coordinator(downloader, FakeImporter(ImportResult.Duplicate(3L)))
        val first = async { coordinator.import(book()) }
        val second = async { coordinator.import(book().copy(stableKey = "gutenberg:2")) }

        bothEntered.await()
        release.complete(Unit)

        assertTrue(first.await() is OnlineImportResult.Added)
        assertTrue(second.await() is OnlineImportResult.Added)
        assertEquals(2, downloader.calls)
    }

    @Test
    fun `import rejection maps safely and cancellation always deletes staging`() = runBlocking {
        val rejectedFile = temporaryFolder.newFile("rejected.txt")
        val rejected = coordinator(
            FakeDownloader(DownloadResult.Success(DownloadedBook(rejectedFile, OnlineBookFormat.TXT))),
            FakeImporter(ImportResult.Rejected(ImportRejection.PROTECTED)),
        ).import(book())
        assertEquals(OnlineImportResult.Failed(OnlineImportFailure.PROTECTED), rejected)
        assertFalse(rejectedFile.exists())

        val cancelledFile = temporaryFolder.newFile("cancelled.txt")
        val cancellingImporter = object : OnlineBookImporter {
            override suspend fun import(file: File, displayName: String): ImportResult =
                throw CancellationException("cancelled")
        }
        try {
            coordinator(
                FakeDownloader(DownloadResult.Success(DownloadedBook(cancelledFile, OnlineBookFormat.TXT))),
                cancellingImporter,
            ).import(book())
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected.
        }
        assertFalse(cancelledFile.exists())
    }

    private fun coordinator(
        downloader: OnlineBookDownloader,
        importer: OnlineBookImporter = FakeImporter(ImportResult.Imported(1L)),
        ledger: OnlineImportLedger = MemoryLedger(),
        existingBookIds: MutableSet<Long> = mutableSetOf(),
    ) = OnlineBookImportCoordinator(
        downloader = downloader,
        importer = importer,
        ledger = ledger,
        localBookLookup = LocalBookLookup(existingBookIds::contains),
    )

    private fun book(
        acquisitions: List<OnlineAcquisition> = listOf(
            acquisition("https://www.gutenberg.org/book.txt", OnlineBookFormat.TXT, 20),
            acquisition("https://www.gutenberg.org/backup.epub", OnlineBookFormat.EPUB, 30),
        ),
    ) = OnlineBook(
        stableKey = "gutenberg:1",
        title = "A / Test: Book",
        authors = listOf("Author"),
        summary = null,
        languages = listOf("en"),
        subjects = emptyList(),
        coverUrl = null,
        sourceRank = 1,
        popularity = null,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = emptyList(),
        acquisitions = acquisitions,
    )

    private fun acquisition(
        url: String,
        format: OnlineBookFormat,
        priority: Int,
        access: AcquisitionAccess = AcquisitionAccess.FREE_FULL,
    ) = OnlineAcquisition("gutenberg-opds", format, url, access, priority)

    private class FakeDownloader(vararg results: DownloadResult) : OnlineBookDownloader {
        private val results = ArrayDeque(results.toList())
        val requests = mutableListOf<DownloadRequest>()
        override suspend fun download(request: DownloadRequest, onProgress: (DownloadProgress) -> Unit): DownloadResult {
            requests += request
            return results.removeFirst()
        }
    }

    private class FakeImporter(private val result: ImportResult) : OnlineBookImporter {
        var calls = 0
        val displayNames = mutableListOf<String>()
        override suspend fun import(file: File, displayName: String): ImportResult {
            calls++
            displayNames += displayName
            return result
        }
    }

    private class MemoryLedger(
        private val entries: MutableMap<String, Long> = mutableMapOf(),
    ) : OnlineImportLedger {
        override suspend fun get(stableKey: String): Long? = entries[stableKey]
        override suspend fun put(stableKey: String, bookId: Long) { entries[stableKey] = bookId }
        override suspend fun remove(stableKey: String) { entries.remove(stableKey) }
    }
}

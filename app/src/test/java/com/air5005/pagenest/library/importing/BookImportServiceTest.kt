package com.air5005.pagenest.library.importing

import com.wxn.base.bean.Book
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookImportServiceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun unsupportedFilenameRejectsBeforeOpeningInputOrCallingDependencies() {
        val root = File(temporaryFolder.root, "unsupported-books")
        var inputOpened = false
        var inspected = false
        var parsed = false
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ ->
                inspected = true
                ProtectionVerdict.CLEAR
            },
            parser = BookMetadataParser { _, _ ->
                parsed = true
                book("unused")
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(
                ImportRequest("archive.zip") {
                    inputOpened = true
                    "content".byteInputStream()
                },
            )
        }

        assertEquals(ImportResult.Rejected(ImportRejection.UNSUPPORTED_FORMAT), result)
        assertFalse(inputOpened)
        assertFalse(root.exists())
        assertFalse(inspected)
        assertFalse(parsed)
        assertEquals(0, catalog.findCalls)
        assertEquals(0, catalog.insertCalls)
    }

    @Test
    fun inputOpenFailureMapsToUnreadableWithoutCreatingPrivateStorage() {
        val root = File(temporaryFolder.root, "open-failure-books")
        var inspected = false
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ ->
                inspected = true
                ProtectionVerdict.CLEAR
            },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.epub") { throw IOException("cannot open") })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.UNREADABLE), result)
        assertFalse(root.exists())
        assertFalse(inspected)
        assertEquals(0, catalog.findCalls)
    }

    @Test
    fun privateCopyFailureMapsToStorageFailureAndStopsBeforeInspection() {
        val root = File(temporaryFolder.root, "copy-failure-books")
        var inspected = false
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ ->
                inspected = true
                ProtectionVerdict.CLEAR
            },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.pdf") { ThrowingInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertFalse(inspected)
        assertEquals(0, catalog.findCalls)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun cancellationWhileClosingAFailedCopyPropagatesInsteadOfMappingToStorageFailure() {
        val root = File(temporaryFolder.root, "copy-close-cancellation-books")
        val cancellation = CancellationException("cancel while closing source")
        val input = object : ThrowingInputStream() {
            override fun close() {
                throw cancellation
            }
        }
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.pdf") { input })
            }
        }

        assertTrue(thrown === cancellation)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun protectedBookDeletesNewCopyAndStopsBeforeCatalogLookup() {
        val root = File(temporaryFolder.root, "protected-books")
        var parsed = false
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.PROTECTED },
            parser = BookMetadataParser { _, _ ->
                parsed = true
                book("unused")
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("protected.mobi") { "protected".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.PROTECTED), result)
        assertPrivateRootEmpty(root)
        assertFalse(parsed)
        assertEquals(0, catalog.findCalls)
        assertEquals(0, catalog.insertCalls)
    }

    @Test
    fun unreadableInspectionDeletesNewCopyAndStopsBeforeCatalogLookup() {
        val root = File(temporaryFolder.root, "unreadable-books")
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.UNREADABLE },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("broken.azw3") { "broken".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.UNREADABLE), result)
        assertPrivateRootEmpty(root)
        assertEquals(0, catalog.findCalls)
    }

    @Test
    fun duplicateHashReturnsExistingIdWithoutParsingOrInsertingAndKeepsTheCopy() {
        val root = temporaryFolder.newFolder("duplicate-books")
        val existing = PrivateBookStore(root, StrongTestPrivateBookStoreFileOperations).store(
            "same bytes".byteInputStream(),
            "first.epub",
        )
        var parsed = false
        val catalog = RecordingCatalog(existingId = 41L)
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ ->
                parsed = true
                book("unused")
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("second.EPUB") { "same bytes".byteInputStream() })
        }

        assertEquals(ImportResult.Duplicate(41L), result)
        assertTrue(existing.file.isFile)
        assertEquals("same bytes", existing.file.readText())
        assertFalse(parsed)
        assertEquals(1, catalog.findCalls)
        assertEquals(0, catalog.insertCalls)
        assertEquals(listOf("find:${existing.sha256}"), catalog.events)
    }

    @Test
    fun parserNullDeletesNewCopyAndReturnsParseFailure() {
        val root = File(temporaryFolder.root, "null-parser-books")
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> null },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.txt") { "text".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.PARSE_FAILED), result)
        assertPrivateRootEmpty(root)
        assertEquals(1, catalog.findCalls)
        assertEquals(0, catalog.insertCalls)
    }

    @Test
    fun parserExceptionDeletesNewCopyAndReturnsParseFailure() {
        val root = File(temporaryFolder.root, "throwing-parser-books")
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> throw IOException("malformed metadata") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.pdf") { "pdf".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.PARSE_FAILED), result)
        assertPrivateRootEmpty(root)
        assertEquals(0, catalog.insertCalls)
    }

    @Test
    fun catalogLookupExceptionDeletesNewCopyAndReturnsStorageFailure() {
        val root = File(temporaryFolder.root, "lookup-failure-books")
        var parsed = false
        val catalog = RecordingCatalog(findFailure = IOException("database unavailable"))
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ ->
                parsed = true
                book("unused")
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.epub") { "epub".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertPrivateRootEmpty(root)
        assertFalse(parsed)
        assertEquals(0, catalog.insertCalls)
    }

    @Test
    fun catalogInsertExceptionDeletesNewCopyAndLeavesNoCatalogRecord() {
        val root = File(temporaryFolder.root, "insert-failure-books")
        val catalog = RecordingCatalog(insertFailure = IOException("database full"))
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { file, format ->
                book(file.toURI().toString(), fileType = format.extension)
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.epub") { "epub".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertPrivateRootEmpty(root)
        assertEquals(1, catalog.insertCalls)
        assertTrue(catalog.inserted.isEmpty())
    }

    @Test
    fun cleanupFailureIsReportedAsStorageFailureInsteadOfClaimingProtectedCleanup() {
        val root = File(temporaryFolder.root, "cleanup-failure-books")
        val catalog = RecordingCatalog()
        val service = service(
            root = root,
            inspector = BookProtectionInspector { file, _ ->
                assertTrue(file.delete())
                assertTrue(file.mkdir())
                File(file, "blocker").writeText("keep directory non-empty")
                ProtectionVerdict.PROTECTED
            },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("protected.pdf") { "pdf".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertEquals(0, catalog.findCalls)
        assertTrue(root.walkTopDown().any { it.name == "blocker" })
    }

    @Test
    fun cancellationFromParserPropagatesAfterDeletingNewCopy() {
        val root = File(temporaryFolder.root, "cancelled-parser-books")
        val cancellation = CancellationException("cancel import")
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> throw cancellation },
            catalog = RecordingCatalog(),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.mobi") { "mobi".byteInputStream() })
            }
        }

        assertTrue(thrown === cancellation)
        assertPrivateRootEmpty(root)
    }

    @Test
    fun publishedCleanupExceptionContinuesWithItsStoredBookInsteadOfRecopying() {
        val root = temporaryFolder.newFolder("published-cleanup-books")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = StrongTestPrivateBookStoreFileOperations.openRoot(root, operations)
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun close() {
                        delegate.close()
                        throw IOException("root handle close failed after publication")
                    }
                }
            }
        }
        val privateBookStore = PrivateBookStore(root, operations)
        val catalog = RecordingCatalog(insertedId = 73L)
        var parsedFile: File? = null
        val service = BookImportService(
            privateBookStore = privateBookStore,
            protectionInspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            metadataParser = BookMetadataParser { file, format ->
                parsedFile = file
                book(file.toURI().toString(), fileType = format.extension)
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.pdf") { "published".byteInputStream() })
        }

        assertEquals(ImportResult.Imported(73L), result)
        assertTrue(parsedFile!!.isFile)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "pdf" })
        assertEquals(1, catalog.insertCalls)
    }

    @Test
    fun successRunsFailFastPipelineOnceAndPersistsParserBookWithPrivateUri() {
        val root = File(temporaryFolder.root, "successful-books")
        val events = mutableListOf<String>()
        val catalog = RecordingCatalog(insertedId = 99L, sharedEvents = events)
        var parserCalls = 0
        var inspectedFile: File? = null
        var parsedBook: Book? = null
        val service = service(
            root = root,
            inspector = BookProtectionInspector { file, format ->
                events += "inspect:${format.extension}"
                inspectedFile = file
                ProtectionVerdict.CLEAR
            },
            parser = BookMetadataParser { file, format ->
                parserCalls++
                events += "parse:${format.extension}"
                book(file.toURI().toString(), fileType = format.extension).also { parsedBook = it }
            },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(
                ImportRequest("Novel.AZW3") {
                    events += "open"
                    "clear azw3".byteInputStream()
                },
            )
        }

        assertEquals(ImportResult.Imported(99L), result)
        assertEquals(
            listOf("open", "inspect:azw3", "find", "parse:azw3", "insert"),
            events,
        )
        assertEquals(1, parserCalls)
        assertEquals(1, catalog.findCalls)
        assertEquals(1, catalog.insertCalls)
        assertTrue(inspectedFile!!.isFile)
        assertEquals(inspectedFile!!.toURI().toString(), parsedBook!!.filePath)
        assertEquals(parsedBook, catalog.inserted.single().first)
        assertEquals(inspectedFile!!.nameWithoutExtension, catalog.inserted.single().second)
    }

    private fun service(
        root: File,
        inspector: BookProtectionInspector,
        parser: BookMetadataParser,
        catalog: BookImportCatalog,
    ): BookImportService = BookImportService(
        privateBookStore = PrivateBookStore(root, StrongTestPrivateBookStoreFileOperations),
        protectionInspector = inspector,
        metadataParser = parser,
        catalog = catalog,
    )

    private fun assertPrivateRootEmpty(root: File) {
        assertTrue(root.isDirectory)
        assertTrue(root.listFiles().orEmpty().isEmpty())
    }

    private fun book(filePath: String, fileType: String = "epub") = Book(
        title = "Test Book",
        author = "Test Author",
        description = null,
        filePath = filePath,
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 0f,
        lastOpened = null,
        category = null,
        fileType = fileType,
    )

    private fun <T> runSuspend(block: suspend () -> T): T {
        val completed = CountDownLatch(1)
        var result: Result<T>? = null
        block.startCoroutine(
            object : Continuation<T> {
                override val context = EmptyCoroutineContext

                override fun resumeWith(resumeResult: Result<T>) {
                    result = resumeResult
                    completed.countDown()
                }
            },
        )
        check(completed.await(5, TimeUnit.SECONDS)) { "Suspend test did not complete" }
        return result?.getOrThrow() ?: error("Suspend test completed without a result")
    }

    private class RecordingCatalog(
        private val existingId: Long? = null,
        private val insertedId: Long = 1L,
        private val findFailure: Throwable? = null,
        private val insertFailure: Throwable? = null,
        private val sharedEvents: MutableList<String>? = null,
    ) : BookImportCatalog {
        var findCalls = 0
            private set
        var insertCalls = 0
            private set
        val events = mutableListOf<String>()
        val inserted = mutableListOf<Pair<Book, String>>()

        override suspend fun findBySha256(sha256: String): Long? {
            findCalls++
            events += "find:$sha256"
            sharedEvents?.add("find")
            findFailure?.let { throw it }
            return existingId
        }

        override suspend fun insert(book: Book, sha256: String): Long {
            insertCalls++
            events += "insert:$sha256"
            sharedEvents?.add("insert")
            insertFailure?.let { throw it }
            inserted += book to sha256
            return insertedId
        }
    }

    private open class ThrowingInputStream : InputStream() {
        override fun read(): Int = throw IOException("copy failed")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("copy failed")
    }
}

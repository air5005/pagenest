package com.air5005.pagenest.library.importing

import com.wxn.base.bean.Book
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    fun sourceCloseCancellationTakesPriorityOverStoreCancellation() {
        val root = temporaryFolder.newFolder("store-and-close-cancellation-books")
        val storeCancellation = CancellationException("cancel store")
        val closeCancellation = CancellationException("cancel source close")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle = throw storeCancellation
        }
        val source = object : ByteArrayInputStream("book".toByteArray()) {
            override fun close() = throw closeCancellation
        }
        val service = BookImportService(
            privateBookStore = PrivateBookStore(root, operations),
            protectionInspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            metadataParser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
            coordinator = InProcessBookImportCoordinator(),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.pdf") { source })
            }
        }

        assertTrue(thrown === closeCancellation)
        assertTrue(thrown.suppressed.contains(storeCancellation))
        assertPrivateRootEmpty(root)
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
        val catalog = RecordingCatalog(existingMatch = CatalogMatch(41L, existing.file))
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
    fun catalogCancellationPropagatesAndDeletesNewCopyInsideHashLock() {
        val root = File(temporaryFolder.root, "catalog-cancellation-books")
        val cancellation = CancellationException("cancel catalog lookup")
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(findFailure = cancellation),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.epub") { "epub".byteInputStream() })
            }
        }

        assertTrue(thrown === cancellation)
        assertPrivateRootEmpty(root)
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
            coordinator = InProcessBookImportCoordinator(),
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
    fun publishedCleanupCancellationDeletesNewFinalAndPropagatesCancellation() {
        val root = temporaryFolder.newFolder("published-cleanup-cancellation-books")
        val cancellation = CancellationException("cancel while closing published root")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = StrongTestPrivateBookStoreFileOperations.openRoot(root, operations)
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun close() {
                        delegate.close()
                        throw cancellation
                    }
                }
            }
        }
        var sourceClosed = false
        val source = object : ByteArrayInputStream("published".toByteArray()) {
            override fun close() {
                sourceClosed = true
                super.close()
            }
        }
        val service = BookImportService(
            privateBookStore = PrivateBookStore(root, operations),
            protectionInspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            metadataParser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
            coordinator = InProcessBookImportCoordinator(),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.pdf") { source })
            }
        }

        assertTrue(thrown === cancellation)
        assertTrue(sourceClosed)
        assertPrivateRootEmpty(root)
    }

    @Test
    fun publishedCleanupCancellationStillDeletesFinalWhenSourceCloseAlsoCancels() {
        val root = temporaryFolder.newFolder("published-and-source-cancellation-books")
        val publicationCancellation = CancellationException("cancel published root cleanup")
        val sourceCloseCancellation = CancellationException("cancel source close")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = StrongTestPrivateBookStoreFileOperations.openRoot(root, operations)
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun close() {
                        delegate.close()
                        throw publicationCancellation
                    }
                }
            }
        }
        val source = object : ByteArrayInputStream("published".toByteArray()) {
            override fun close() = throw sourceCloseCancellation
        }
        val service = BookImportService(
            privateBookStore = PrivateBookStore(root, operations),
            protectionInspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            metadataParser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
            coordinator = InProcessBookImportCoordinator(),
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.pdf") { source })
            }
        }

        assertTrue(thrown === sourceCloseCancellation)
        assertTrue(thrown.suppressed.contains(publicationCancellation))
        assertPrivateRootEmpty(root)
    }

    @Test
    fun publishedCleanupCancellationReportsDeleteReturningFalseAsSuppressed() {
        val root = temporaryFolder.newFolder("published-cancellation-delete-false-books")
        val publicationCancellation = CancellationException("cancel published root cleanup")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle {
                val delegate = StrongTestPrivateBookStoreFileOperations.openRoot(root, operations)
                return object : PrivateBookStoreRootHandle by delegate {
                    override fun close() {
                        delegate.close()
                        throw publicationCancellation
                    }
                }
            }
        }
        val service = BookImportService(
            privateBookStore = PrivateBookStore(root, operations),
            protectionInspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            metadataParser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
            coordinator = InProcessBookImportCoordinator(),
            deletePrivateFile = { false },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.pdf") { "published".byteInputStream() })
            }
        }

        assertTrue(thrown === publicationCancellation)
        assertTrue(thrown.suppressed.any { it is IOException })
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "pdf" })
    }

    @Test
    fun storeLinkageErrorClosesSourceAndMapsToStorageFailure() {
        val root = temporaryFolder.newFolder("linkage-store-books")
        val operations = object : PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
            override fun openRoot(
                root: File,
                operations: PrivateBookStoreFileOperations,
            ): PrivateBookStoreRootHandle = throw UnsatisfiedLinkError("native store unavailable")
        }
        var sourceClosed = false
        val source = object : ByteArrayInputStream("book".toByteArray()) {
            override fun close() {
                sourceClosed = true
                super.close()
            }
        }
        val service = BookImportService(
            privateBookStore = PrivateBookStore(root, operations),
            protectionInspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            metadataParser = BookMetadataParser { _, _ -> error("parser must not run") },
            catalog = RecordingCatalog(),
            coordinator = InProcessBookImportCoordinator(),
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.epub") { source })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertTrue(sourceClosed)
        assertPrivateRootEmpty(root)
    }

    @Test
    fun cancellationFromCoordinatorPropagatesAndDeletesNewCopy() {
        val root = File(temporaryFolder.root, "coordinator-cancellation-books")
        val cancellation = CancellationException("cancel lock acquisition")
        var parsed = false
        val coordinator = object : BookImportCoordinator {
            override suspend fun <T> withHashLock(
                sha256: String,
                block: suspend () -> T,
            ): T = throw cancellation
        }
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> error("inspector must not run") },
            parser = BookMetadataParser { _, _ ->
                parsed = true
                book("unused")
            },
            catalog = RecordingCatalog(),
            coordinator = coordinator,
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.epub") { "book".byteInputStream() })
            }
        }

        assertTrue(thrown === cancellation)
        assertFalse(parsed)
        assertPrivateRootEmpty(root)
    }

    @Test
    fun cancellationFromDeletePropagatesWithoutBecomingAParseFailure() {
        val root = File(temporaryFolder.root, "delete-cancellation-books")
        val cancellation = CancellationException("cancel private file deletion")
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> null },
            catalog = RecordingCatalog(),
            deletePrivateFile = { throw cancellation },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend {
                service.execute(ImportRequest("book.txt") { "book".byteInputStream() })
            }
        }

        assertTrue(thrown === cancellation)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "txt" })
    }

    @Test
    fun atomicCatalogExistingResultReturnsDuplicateAndDeletesThisNewCopy() {
        val root = File(temporaryFolder.root, "atomic-existing-books")
        val catalog = RecordingCatalog(
            writeResult = CatalogWriteResult.Existing(
                88L,
                File(temporaryFolder.root, "catalog-existing.epub"),
            ),
        )
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> book("content://source/book") },
            catalog = catalog,
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.epub") { "book".byteInputStream() })
        }

        assertEquals(ImportResult.Duplicate(88L), result)
        assertEquals(1, catalog.insertCalls)
        assertTrue(catalog.inserted.isEmpty())
        assertPrivateRootEmpty(root)
    }

    @Test
    fun atomicCatalogExistingCleanupFailureMapsToStorageFailure() {
        val root = File(temporaryFolder.root, "atomic-existing-cleanup-failure-books")
        val catalog = RecordingCatalog(
            writeResult = CatalogWriteResult.Existing(
                89L,
                File(temporaryFolder.root, "catalog-existing.pdf"),
            ),
        )
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> book("content://source/book") },
            catalog = catalog,
            deletePrivateFile = { throw IOException("cannot delete redundant copy") },
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.pdf") { "book".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "pdf" })
    }

    @Test
    fun atomicCatalogExistingDeleteReturningFalseMapsToStorageFailure() {
        val root = File(temporaryFolder.root, "atomic-existing-delete-false-books")
        val catalog = RecordingCatalog(
            writeResult = CatalogWriteResult.Existing(
                91L,
                File(temporaryFolder.root, "catalog-existing.azw3"),
            ),
        )
        val service = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> book("content://source/book") },
            catalog = catalog,
            deletePrivateFile = { false },
        )

        val result = runSuspend {
            service.execute(ImportRequest("book.azw3") { "book".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "azw3" })
    }

    @Test
    fun catalogPathComparisonFailureFailsClosedWithoutDeletingTheNewCopy() {
        val root = File(temporaryFolder.root, "catalog-path-comparison-failure-books")
        var parsed = false
        val catalog = RecordingCatalog(
            existingMatch = CatalogMatch(90L, File("invalid\u0000catalog-path.epub")),
        )
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
            service.execute(ImportRequest("book.epub") { "book".byteInputStream() })
        }

        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), result)
        assertFalse(parsed)
        assertEquals(1, root.listFiles().orEmpty().count { it.extension == "epub" })
    }

    @Test
    fun sameBytesUnderDifferentExtensionsDeleteOnlyTheRedundantNewCopy() {
        val root = File(temporaryFolder.root, "cross-extension-books")
        val coordinator = InProcessBookImportCoordinator()
        val catalog = ThreadSafeAtomicCatalog()
        val firstService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> book("content://source/first") },
            catalog = catalog,
            coordinator = coordinator,
        )
        var secondParsed = false
        val secondService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ ->
                secondParsed = true
                book("content://source/second")
            },
            catalog = catalog,
            coordinator = coordinator,
        )

        val first = runSuspend {
            firstService.execute(ImportRequest("book.epub") { "same bytes".byteInputStream() })
        }
        val second = runSuspend {
            secondService.execute(ImportRequest("book.pdf") { "same bytes".byteInputStream() })
        }

        assertEquals(ImportResult.Imported(1L), first)
        assertEquals(ImportResult.Duplicate(1L), second)
        assertFalse(secondParsed)
        assertEquals(listOf("epub"), root.listFiles().orEmpty().map { it.extension })
        assertEquals("epub", File(java.net.URI(catalog.rows.single().book.filePath)).extension)
    }

    @Test
    fun concurrentSameHashAcrossServicesCreatesOneRowAndOneLivePrivateUri() {
        val root = File(temporaryFolder.root, "concurrent-success-books")
        val coordinator = OwnerFirstSharedCoordinator()
        val catalog = ThreadSafeAtomicCatalog()
        val parserCalls = AtomicInteger()
        val ownerService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ ->
                parserCalls.incrementAndGet()
                book("content://owner/source")
            },
            catalog = catalog,
            coordinator = coordinator,
        )
        val existingService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ ->
                parserCalls.incrementAndGet()
                book("content://existing/source")
            },
            catalog = catalog,
            coordinator = coordinator,
        )

        val (ownerResult, existingResult) = executeOwnerThenExisting(
            coordinator = coordinator,
            owner = { ownerService.execute(ImportRequest("owner.epub") { "same".byteInputStream() }) },
            existing = {
                existingService.execute(ImportRequest("existing.epub") { "same".byteInputStream() })
            },
        )

        assertEquals(ImportResult.Imported(1L), ownerResult)
        assertEquals(ImportResult.Duplicate(1L), existingResult)
        assertEquals(1, parserCalls.get())
        assertEquals(1, catalog.insertCalls.get())
        assertEquals(1, catalog.rows.size)
        val privateFile = File(java.net.URI(catalog.rows.single().book.filePath))
        assertTrue(privateFile.isFile)
        assertEquals(listOf(privateFile), root.listFiles().orEmpty().toList())
        assertEquals(1, coordinator.maxActive.get())
    }

    @Test
    fun existingSideInsertBeforeOwnerLookupKeepsTheirSharedPrivateFile() {
        val root = File(temporaryFolder.root, "concurrent-existing-first-books")
        val coordinator = ExistingFirstSharedCoordinator()
        val catalog = ThreadSafeAtomicCatalog()
        val ownerService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> error("owner must observe the existing row") },
            catalog = catalog,
            coordinator = coordinator,
        )
        val existingService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> book("content://existing/source") },
            catalog = catalog,
            coordinator = coordinator,
        )

        val ownerTask = FutureTask {
            runSuspend {
                ownerService.execute(ImportRequest("owner.epub") { "same".byteInputStream() })
            }
        }
        val existingTask = FutureTask {
            runSuspend {
                existingService.execute(ImportRequest("existing.epub") { "same".byteInputStream() })
            }
        }
        Thread(ownerTask, ExistingFirstSharedCoordinator.OWNER_THREAD).start()
        assertTrue(coordinator.ownerEntered.await(5, TimeUnit.SECONDS))
        Thread(existingTask, ExistingFirstSharedCoordinator.EXISTING_THREAD).start()
        val ownerResult = ownerTask.get(10, TimeUnit.SECONDS)
        val existingResult = existingTask.get(10, TimeUnit.SECONDS)

        assertEquals(ImportResult.Duplicate(1L), ownerResult)
        assertEquals(ImportResult.Imported(1L), existingResult)
        assertEquals(1, catalog.rows.size)
        val privateFile = File(java.net.URI(catalog.rows.single().book.filePath))
        assertTrue("Catalog URI must not be left dangling", privateFile.isFile)
        assertEquals(listOf(privateFile), root.listFiles().orEmpty().toList())
        assertEquals(1, coordinator.maxActive.get())
    }

    @Test
    fun ownerParseFailureDeletesFileBeforeExistingSideValidatesInsideSharedLock() {
        val root = File(temporaryFolder.root, "concurrent-owner-failure-books")
        val coordinator = OwnerFirstSharedCoordinator()
        val catalog = ThreadSafeAtomicCatalog()
        var existingInspected = false
        var existingParsed = false
        val ownerService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ -> ProtectionVerdict.CLEAR },
            parser = BookMetadataParser { _, _ -> null },
            catalog = catalog,
            coordinator = coordinator,
        )
        val existingService = service(
            root = root,
            inspector = BookProtectionInspector { _, _ ->
                existingInspected = true
                ProtectionVerdict.CLEAR
            },
            parser = BookMetadataParser { _, _ ->
                existingParsed = true
                book("content://existing/source")
            },
            catalog = catalog,
            coordinator = coordinator,
        )

        val (ownerResult, existingResult) = executeOwnerThenExisting(
            coordinator = coordinator,
            owner = { ownerService.execute(ImportRequest("owner.pdf") { "same".byteInputStream() }) },
            existing = {
                existingService.execute(ImportRequest("existing.pdf") { "same".byteInputStream() })
            },
        )

        assertEquals(ImportResult.Rejected(ImportRejection.PARSE_FAILED), ownerResult)
        assertEquals(ImportResult.Rejected(ImportRejection.STORAGE_FAILED), existingResult)
        assertFalse(existingInspected)
        assertFalse(existingParsed)
        assertTrue(catalog.rows.isEmpty())
        assertPrivateRootEmpty(root)
        assertEquals(1, coordinator.maxActive.get())
    }

    @Test
    fun successRunsFailFastPipelineOnceAndPersistsParserBookWithPrivateUri() {
        val root = File(temporaryFolder.root, "successful-books")
        val events = mutableListOf<String>()
        val catalog = RecordingCatalog(insertedId = 99L, sharedEvents = events)
        var parserCalls = 0
        var inspectedFile: File? = null
        var parserResult: Book? = null
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
                book("content://untrusted/source", fileType = format.extension).also {
                    parserResult = it
                }
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
        assertEquals("content://untrusted/source", parserResult!!.filePath)
        assertEquals(inspectedFile!!.toURI().toString(), catalog.inserted.single().first.filePath)
        assertEquals(inspectedFile!!.nameWithoutExtension, catalog.inserted.single().second)
    }

    private fun service(
        root: File,
        inspector: BookProtectionInspector,
        parser: BookMetadataParser,
        catalog: BookImportCatalog,
        coordinator: BookImportCoordinator = InProcessBookImportCoordinator(),
        deletePrivateFile: (File) -> Boolean = { Files.deleteIfExists(it.toPath()) },
    ): BookImportService = BookImportService(
        privateBookStore = PrivateBookStore(root, StrongTestPrivateBookStoreFileOperations),
        protectionInspector = inspector,
        metadataParser = parser,
        catalog = catalog,
        coordinator = coordinator,
        deletePrivateFile = deletePrivateFile,
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

    private fun executeOwnerThenExisting(
        coordinator: OwnerFirstSharedCoordinator,
        owner: suspend () -> ImportResult,
        existing: suspend () -> ImportResult,
    ): Pair<ImportResult, ImportResult> {
        val ownerTask = FutureTask { runSuspend(owner) }
        val existingTask = FutureTask { runSuspend(existing) }
        val ownerThread = Thread(ownerTask, OwnerFirstSharedCoordinator.OWNER_THREAD)
        val existingThread = Thread(existingTask, OwnerFirstSharedCoordinator.EXISTING_THREAD)
        ownerThread.start()
        check(coordinator.ownerEntered.await(5, TimeUnit.SECONDS)) {
            "Owner did not store and reach the coordinator"
        }
        existingThread.start()
        return ownerTask.get(10, TimeUnit.SECONDS) to existingTask.get(10, TimeUnit.SECONDS)
    }

    private class OwnerFirstSharedCoordinator(
        private val delegate: BookImportCoordinator = InProcessBookImportCoordinator(),
    ) : BookImportCoordinator {
        val ownerEntered = CountDownLatch(1)
        val maxActive = AtomicInteger()
        private val existingEntered = CountDownLatch(1)
        private val ownerLocked = CountDownLatch(1)
        private val active = AtomicInteger()

        override suspend fun <T> withHashLock(
            sha256: String,
            block: suspend () -> T,
        ): T = when (Thread.currentThread().name) {
            OWNER_THREAD -> {
                ownerEntered.countDown()
                check(existingEntered.await(5, TimeUnit.SECONDS)) {
                    "Existing-side import did not reach the shared coordinator"
                }
                delegate.withHashLock(sha256) {
                    ownerLocked.countDown()
                    trackActive(block)
                }
            }
            EXISTING_THREAD -> {
                existingEntered.countDown()
                check(ownerLocked.await(5, TimeUnit.SECONDS)) {
                    "Owner did not acquire the shared hash lock"
                }
                delegate.withHashLock(sha256) { trackActive(block) }
            }
            else -> error("Unexpected import thread: ${Thread.currentThread().name}")
        }

        private suspend fun <T> trackActive(block: suspend () -> T): T {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current, ::maxOf)
            return try {
                block()
            } finally {
                active.decrementAndGet()
            }
        }

        companion object {
            const val OWNER_THREAD = "owner-import"
            const val EXISTING_THREAD = "existing-import"
        }
    }

    private class ExistingFirstSharedCoordinator(
        private val delegate: BookImportCoordinator = InProcessBookImportCoordinator(),
    ) : BookImportCoordinator {
        val ownerEntered = CountDownLatch(1)
        val maxActive = AtomicInteger()
        private val existingLocked = CountDownLatch(1)
        private val active = AtomicInteger()

        override suspend fun <T> withHashLock(
            sha256: String,
            block: suspend () -> T,
        ): T = when (Thread.currentThread().name) {
            OWNER_THREAD -> {
                ownerEntered.countDown()
                check(existingLocked.await(5, TimeUnit.SECONDS)) {
                    "Existing-side import did not acquire the shared hash lock"
                }
                delegate.withHashLock(sha256) { trackActive(block) }
            }
            EXISTING_THREAD -> delegate.withHashLock(sha256) {
                existingLocked.countDown()
                trackActive(block)
            }
            else -> error("Unexpected import thread: ${Thread.currentThread().name}")
        }

        private suspend fun <T> trackActive(block: suspend () -> T): T {
            val current = active.incrementAndGet()
            maxActive.accumulateAndGet(current) { previous, candidate ->
                maxOf(previous, candidate)
            }
            return try {
                block()
            } finally {
                active.decrementAndGet()
            }
        }

        companion object {
            const val OWNER_THREAD = "reverse-owner-import"
            const val EXISTING_THREAD = "reverse-existing-import"
        }
    }

    private class ThreadSafeAtomicCatalog : BookImportCatalog {
        data class Row(val id: Long, val book: Book, val sha256: String)

        val rows = mutableListOf<Row>()
        val insertCalls = AtomicInteger()

        override suspend fun findBySha256(sha256: String): CatalogMatch? = synchronized(this) {
            rows.singleOrNull { it.sha256 == sha256 }?.let { row ->
                CatalogMatch(row.id, File(java.net.URI(row.book.filePath)))
            }
        }

        override suspend fun insertOrGet(book: Book, sha256: String): CatalogWriteResult =
            synchronized(this) {
                insertCalls.incrementAndGet()
                val existing = rows.singleOrNull { it.sha256 == sha256 }
                if (existing != null) {
                    CatalogWriteResult.Existing(
                        existing.id,
                        File(java.net.URI(existing.book.filePath)),
                    )
                } else {
                    val row = Row(rows.size + 1L, book, sha256)
                    rows += row
                    CatalogWriteResult.Inserted(row.id)
                }
            }
    }

    private class RecordingCatalog(
        private val existingMatch: CatalogMatch? = null,
        private val insertedId: Long = 1L,
        private val findFailure: Throwable? = null,
        private val insertFailure: Throwable? = null,
        private val writeResult: CatalogWriteResult? = null,
        private val sharedEvents: MutableList<String>? = null,
    ) : BookImportCatalog {
        var findCalls = 0
            private set
        var insertCalls = 0
            private set
        val events = mutableListOf<String>()
        val inserted = mutableListOf<Pair<Book, String>>()

        override suspend fun findBySha256(sha256: String): CatalogMatch? {
            findCalls++
            events += "find:$sha256"
            sharedEvents?.add("find")
            findFailure?.let { throw it }
            return existingMatch
        }

        override suspend fun insertOrGet(book: Book, sha256: String): CatalogWriteResult {
            insertCalls++
            events += "insert:$sha256"
            sharedEvents?.add("insert")
            insertFailure?.let { throw it }
            val result = writeResult ?: CatalogWriteResult.Inserted(insertedId)
            if (result is CatalogWriteResult.Inserted) inserted += book to sha256
            return result
        }
    }

    private open class ThrowingInputStream : InputStream() {
        override fun read(): Int = throw IOException("copy failed")

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            throw IOException("copy failed")
    }
}

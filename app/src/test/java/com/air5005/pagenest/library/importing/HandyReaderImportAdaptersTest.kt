package com.air5005.pagenest.library.importing

import androidx.documentfile.provider.DocumentFile
import com.wxn.base.bean.Book
import com.wxn.bookparser.FileParser
import com.wxn.bookparser.domain.file.CachedFile
import com.wxn.reader.data.dto.BookEntity
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
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
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HandyReaderImportAdaptersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun metadataAdapterParsesThePrivateCachedFileAndForcesItsUri() {
        val privateFile = temporaryFolder.newFile("private.epub")
        val cachedFile = allocateWithoutAndroidConstructor(CachedFile::class.java)
        var factoryFile: File? = null
        var parserInput: CachedFile? = null
        val fileParser = object : FileParser {
            override suspend fun parse(cachedFile: CachedFile): Book? {
                parserInput = cachedFile
                return book("content://untrusted/source")
            }

            override suspend fun parse(file: DocumentFile): Book? =
                error("The private-file adapter must use CachedFile")
        }
        val adapter = HandyReaderBookMetadataParser(fileParser) { file ->
            factoryFile = file
            cachedFile
        }

        val parsed = runSuspend { adapter.parse(privateFile, SupportedBookFormat.EPUB) }

        assertEquals(privateFile, factoryFile)
        assertTrue(parserInput === cachedFile)
        assertEquals(privateFile.toURI().toString(), parsed?.filePath)
    }

    @Test
    fun roomCatalogReturnsTheGeneratedIdAndExistingPrivateCandidate() {
        val insertedUri = temporaryFolder.newFile("inserted.pdf").toURI().toString()
        val existingUri = temporaryFolder.newFile("existing.pdf").toURI().toString()
        val dataSource = RecordingImportDataSource(
            writes = ArrayDeque(
                listOf(
                    BookImportDatabaseWrite(73L, insertedUri, inserted = true),
                    BookImportDatabaseWrite(91L, existingUri, inserted = false),
                ),
            ),
        )
        val catalog = RoomBookImportCatalog(dataSource) { value, sha256 ->
            entity(value.filePath, sha256)
        }

        val inserted = runSuspend { catalog.insertOrGet(book(insertedUri), "a".repeat(64)) }
        val existing = runSuspend { catalog.insertOrGet(book("ignored"), "b".repeat(64)) }

        assertEquals(CatalogWriteResult.Inserted(73L), inserted)
        assertEquals(
            CatalogWriteResult.Existing(91L, File(java.net.URI(existingUri))),
            existing,
        )
        assertEquals(listOf("a".repeat(64), "b".repeat(64)), dataSource.inserted.map { it.sha256 })
    }

    @Test
    fun roomCatalogResolvesCommitThenThrowWithoutExposingAThrowable() {
        val committedFile = temporaryFolder.newFile("committed.epub")
        val sha256 = "c".repeat(64)
        val dataSource = CommitThenFailImportDataSource(
            BookImportDatabaseRow(101L, committedFile.toURI().toString()),
            IOException("throw after commit"),
        )
        val catalog = RoomBookImportCatalog(dataSource) { value, hash ->
            entity(value.filePath, hash)
        }

        val result = runSuspend { catalog.insertOrGet(book(committedFile.toURI().toString()), sha256) }

        assertEquals(CatalogWriteResult.Existing(101L, committedFile), result)
        assertEquals(1, dataSource.lookupCalls)
    }

    @Test
    fun roomCatalogResolvesCommitThenCancellationWithoutExposingCancellation() {
        val committedFile = temporaryFolder.newFile("committed.pdf")
        val sha256 = "d".repeat(64)
        val dataSource = CommitThenFailImportDataSource(
            BookImportDatabaseRow(102L, committedFile.toURI().toString()),
            CancellationException("cancel after commit"),
        )
        val catalog = RoomBookImportCatalog(dataSource) { value, hash ->
            entity(value.filePath, hash)
        }

        val result = runSuspend { catalog.insertOrGet(book(committedFile.toURI().toString()), sha256) }

        assertEquals(CatalogWriteResult.Existing(102L, committedFile), result)
        assertTrue(committedFile.isFile)
    }

    @Test
    fun roomCatalogStillPropagatesPrecommitCancellationWhenNoRowExists() {
        val cancellation = CancellationException("cancel before commit")
        val dataSource = CommitThenFailImportDataSource(null, cancellation)
        val catalog = RoomBookImportCatalog(dataSource) { value, hash ->
            entity(value.filePath, hash)
        }

        val thrown = assertThrows(CancellationException::class.java) {
            runSuspend { catalog.insertOrGet(book("file:/missing.epub"), "e".repeat(64)) }
        }

        assertTrue(thrown === cancellation)
    }

    @Test
    fun descriptorValidatorRejectsMissingOutsideSymlinkAndNonRegularCandidates() {
        val parent = temporaryFolder.newFolder("validator-parent")
        val root = File(parent, "books").apply { assertTrue(mkdir()) }
        val validator = TrustedRootPrivateBookFileValidator(
            parent,
            "books",
            EntryIdentityPrivateBookStoreFileOperations,
        )
        val missing = File(root, "missing.epub")
        val outside = temporaryFolder.newFile("outside.epub")
        val directory = File(root, "directory.pdf").apply { assertTrue(mkdir()) }
        val link = File(root, "link.epub")
        try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
        } catch (failure: Exception) {
            assumeNoException("Host must support symbolic links for this security test", failure)
        }

        assertFalse(validator.validate(missing))
        assertFalse(validator.validate(outside))
        assertFalse(validator.validate(link))
        assertFalse(validator.validate(directory))
        assertTrue(outside.isFile)
    }

    @Test
    fun descriptorValidatorComparesLiveIdentitiesAndDurablyDeletesOnlyANewDifferentCopy() {
        val parent = temporaryFolder.newFolder("duplicate-parent")
        val root = File(parent, "books").apply { assertTrue(mkdir()) }
        val stored = File(root, "new.epub").apply { writeText("same bytes") }
        val existing = File(root, "existing.epub").apply { writeText("same bytes") }
        val validator = TrustedRootPrivateBookFileValidator(
            parent,
            "books",
            EntryIdentityPrivateBookStoreFileOperations,
        )

        assertEquals(
            DuplicateResolution.SAME,
            validator.resolveDuplicate(StoredBook(existing, "f".repeat(64), true), existing),
        )
        assertEquals(
            DuplicateResolution.DIFFERENT,
            validator.resolveDuplicate(StoredBook(stored, "f".repeat(64), false), existing),
        )
        assertFalse(stored.exists())
        assertTrue(existing.isFile)
    }

    @Test
    fun descriptorValidatorPathSwapCannotDeleteAnOutsideTarget() {
        val parent = temporaryFolder.newFolder("mutation-parent")
        val root = File(parent, "books").apply { assertTrue(mkdir()) }
        val outside = temporaryFolder.newFile("outside-target.epub").apply { writeText("outside") }
        val newCopy = File(root, "new-copy.epub").apply { writeText("new") }
        val existing = File(root, "existing.epub").apply { writeText("existing") }
        val validator = TrustedRootPrivateBookFileValidator(
            parent,
            "books",
            EntryIdentityPrivateBookStoreFileOperations,
            beforeDelete = { candidate ->
                assertEquals(newCopy, candidate)
                assertTrue(candidate.delete())
                try {
                    Files.createSymbolicLink(candidate.toPath(), outside.toPath())
                } catch (failure: Exception) {
                    assumeNoException("Host must support symbolic links for this security test", failure)
                }
            },
        )

        val result = validator.resolveDuplicate(
            StoredBook(newCopy, "0".repeat(64), wasExisting = false),
            existing,
        )

        assertEquals(DuplicateResolution.DIFFERENT, result)
        assertEquals("outside", outside.readText())
        assertFalse(newCopy.exists())
        assertTrue(existing.isFile)
    }

    @Test
    fun descriptorValidatorPromotesNestedCancellationFromItsMutationBoundary() {
        val parent = temporaryFolder.newFolder("validator-cancellation-parent")
        val root = File(parent, "books").apply { assertTrue(mkdir()) }
        val newCopy = File(root, "new-copy.pdf").apply { writeText("new") }
        val existing = File(root, "existing.pdf").apply { writeText("existing") }
        val cancellation = CancellationException("cancel validator cleanup")
        val wrapper = IOException("validator wrapper", cancellation)
        val validator = TrustedRootPrivateBookFileValidator(
            parent,
            "books",
            EntryIdentityPrivateBookStoreFileOperations,
            beforeDelete = { throw wrapper },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            validator.resolveDuplicate(
                StoredBook(newCopy, "9".repeat(64), wasExisting = false),
                existing,
            )
        }

        assertTrue(thrown === cancellation)
        assertTrue(thrown.suppressed.contains(wrapper))
        assertTrue(newCopy.isFile)
        assertTrue(existing.isFile)
    }

    private fun entity(uri: String, sha256: String?) = BookEntity(
        uri = uri,
        fileType = "epub",
        title = "Title",
        authors = "Author",
        description = null,
        publishDate = null,
        publisher = null,
        language = null,
        numberOfPages = null,
        wordCount = 0,
        subjects = null,
        coverPath = null,
        locator = "",
        sha256 = sha256,
    )

    private fun book(filePath: String) = Book(
        title = "Title",
        author = "Author",
        description = null,
        filePath = filePath,
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 0f,
        lastOpened = null,
        category = null,
        fileType = "epub",
    )

    private fun <T> allocateWithoutAndroidConstructor(type: Class<T>): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return unsafeClass.getMethod("allocateInstance", Class::class.java)
            .invoke(field.get(null), type) as T
    }

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

    private class RecordingImportDataSource(
        private val writes: ArrayDeque<BookImportDatabaseWrite>,
    ) : BookImportDataSource {
        val inserted = mutableListOf<BookEntity>()

        override suspend fun findImportBySha256(sha256: String): BookImportDatabaseRow? = null

        override suspend fun insertOrGetImport(entity: BookEntity): BookImportDatabaseWrite {
            inserted += entity
            return writes.removeFirst()
        }
    }

    private object EntryIdentityPrivateBookStoreFileOperations :
        PrivateBookStoreFileOperations by StrongTestPrivateBookStoreFileOperations {
        override fun openExistingBook(file: File): ExistingBookInput =
            JvmExistingBookInput.open(file, EntryIdentityJvmExistingBookFileOperations)

        override fun readJvmAttributes(file: File) =
            EntryIdentityJvmExistingBookFileOperations.readAttributes(file.toPath())

        override fun readJvmUnixState(file: File) =
            EntryIdentityJvmExistingBookFileOperations.readUnixState(file.toPath())

        override fun digestJvmFile(file: File) =
            EntryIdentityJvmExistingBookFileOperations.digestPath(file.toPath())
    }

    private object EntryIdentityJvmExistingBookFileOperations :
        JvmExistingBookFileOperations by StrongTestJvmExistingBookFileOperations {
        override fun readUnixState(path: Path): JvmUnixFileState {
            val state = StrongTestJvmExistingBookFileOperations.readUnixState(path)
            return state.copy(inode = path.toAbsolutePath().normalize().toString().hashCode().toLong())
        }
    }

    private class CommitThenFailImportDataSource(
        private val committed: BookImportDatabaseRow?,
        private val failure: Throwable,
    ) : BookImportDataSource {
        var lookupCalls = 0

        override suspend fun findImportBySha256(sha256: String): BookImportDatabaseRow? {
            lookupCalls++
            return committed
        }

        override suspend fun insertOrGetImport(entity: BookEntity): BookImportDatabaseWrite =
            throw failure
    }
}

package com.air5005.pagenest.library.importing

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.wxn.base.bean.Book
import com.wxn.bookparser.FileParser
import com.wxn.bookparser.domain.file.CachedFile
import com.wxn.bookparser.domain.file.CachedFileCompat
import com.wxn.reader.data.dto.BookEntity
import com.wxn.reader.data.mapper.book.BookMapper
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

class AndroidImportRequestFactory(private val context: Context) {
    fun create(uri: Uri): ImportRequest {
        val resolver = context.contentResolver
        val displayName = try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }?.takeIf(String::isNotBlank)
            ?: uri.lastPathSegment?.substringAfterLast('/')
            ?: throw IOException("Selected book has no display name")
        return ImportRequest(displayName) {
            resolver.openInputStream(uri)
                ?: throw IOException("Unable to open selected book")
        }
    }
}

class HandyReaderBookMetadataParser internal constructor(
    private val fileParser: FileParser,
    private val cachedFileFactory: (File) -> CachedFile,
) : BookMetadataParser {
    constructor(context: Context, fileParser: FileParser) : this(
        fileParser,
        { file ->
            CachedFile(
                context = context,
                uri = Uri.fromFile(file),
                builder = CachedFileCompat.build(
                    name = file.name,
                    path = file.absolutePath,
                    size = file.length(),
                    lastModified = file.lastModified(),
                    isDirectory = false,
                ),
            )
        },
    )

    override suspend fun parse(file: File, format: SupportedBookFormat): Book? =
        fileParser.parse(cachedFileFactory(file))?.copy(filePath = file.toURI().toString())
}

data class BookImportDatabaseRow(
    val id: Long,
    val uri: String,
)

data class BookImportDatabaseWrite(
    val id: Long,
    val uri: String,
    val inserted: Boolean,
)

interface BookImportDataSource {
    suspend fun findImportBySha256(sha256: String): BookImportDatabaseRow?

    suspend fun insertOrGetImport(entity: BookEntity): BookImportDatabaseWrite
}

class RoomBookImportCatalog internal constructor(
    private val dataSource: BookImportDataSource,
    private val toEntity: suspend (Book, String) -> BookEntity,
) : BookImportCatalog {
    constructor(dataSource: BookImportDataSource, mapper: BookMapper) : this(
        dataSource,
        { book, sha256 -> mapper.toBookEntity(book).copy(sha256 = sha256) },
    )

    override suspend fun findBySha256(sha256: String): CatalogMatch? =
        dataSource.findImportBySha256(sha256)?.toCatalogMatch()

    override suspend fun insertOrGet(book: Book, sha256: String): CatalogWriteResult {
        val entity = toEntity(book, sha256)
        val write = try {
            dataSource.insertOrGetImport(entity)
        } catch (failure: Throwable) {
            val committed = try {
                withContext(NonCancellable) {
                    dataSource.findImportBySha256(sha256)
                }
            } catch (lookupFailure: Throwable) {
                if (lookupFailure !== failure) failure.addSuppressed(lookupFailure)
                null
            }
            if (committed != null) {
                return CatalogWriteResult.Existing(
                    committed.id,
                    committed.uri.toPrivateFile(),
                )
            }
            throw failure
        }
        return if (write.inserted) {
            CatalogWriteResult.Inserted(write.id)
        } else {
            CatalogWriteResult.Existing(write.id, write.uri.toPrivateFile())
        }
    }

    private fun BookImportDatabaseRow.toCatalogMatch() =
        CatalogMatch(id, uri.toPrivateFile())

    private fun String.toPrivateFile(): File = try {
        File(java.net.URI(this))
    } catch (failure: Exception) {
        throw IOException("Catalog URI is not a private file URI", failure)
    }
}

class TrustedRootPrivateBookFileValidator internal constructor(
    private val trustedParent: File,
    private val rootName: String,
    private val operations: PrivateBookStoreFileOperations,
    private val beforeDelete: (File) -> Unit = {},
) : PrivateBookFileValidator {
    constructor(trustedParent: File, rootName: String = "books") : this(
        trustedParent.absoluteFile,
        rootName,
        SystemPrivateBookStoreFileOperations,
    )

    private val root = File(trustedParent, rootName).absoluteFile

    override fun validate(file: File): Boolean = try {
        withRoot { handle ->
            val state = handle.readStableState(requireRootEntry(file))
            handle.verifyExistingBook(file, state)
            true
        }
    } catch (failure: Throwable) {
        failure.promotedCancellation()?.let { throw it }
        when (failure) {
            is Exception,
            is LinkageError,
            -> false
            else -> throw failure
        }
    }

    override fun resolveDuplicate(
        storedBook: StoredBook,
        catalogFile: File,
    ): DuplicateResolution = try {
        withRoot { handle ->
            val storedFile = requireRootEntry(storedBook.file)
            val existingFile = requireRootEntry(catalogFile)
            val storedState = handle.readStableState(storedFile)
            val existingState = handle.readStableState(existingFile)
            handle.verifyExistingBook(storedFile, storedState)
            handle.verifyExistingBook(existingFile, existingState)
            if (storedState.sameIdentity(existingState)) {
                DuplicateResolution.SAME
            } else if (storedBook.wasExisting) {
                DuplicateResolution.DIFFERENT
            } else {
                beforeDelete(storedFile)
                if (handle.cleanupPartDurably(storedFile)) {
                    DuplicateResolution.DIFFERENT
                } else {
                    DuplicateResolution.CLEANUP_FAILED
                }
            }
        }
    } catch (failure: Throwable) {
        failure.promotedCancellation()?.let { throw it }
        when (failure) {
            is Exception,
            is LinkageError,
            -> DuplicateResolution.INVALID
            else -> throw failure
        }
    }

    override fun deleteNewCopy(storedBook: StoredBook): Boolean {
        if (storedBook.wasExisting) return true
        return try {
            withRoot { handle ->
                val file = requireRootEntry(storedBook.file)
                val state = handle.readStableState(file)
                handle.verifyExistingBook(file, state)
                beforeDelete(file)
                handle.cleanupPartDurably(file)
            }
        } catch (failure: Throwable) {
            failure.promotedCancellation()?.let { throw it }
            when (failure) {
                is Exception,
                is LinkageError,
                -> false
                else -> throw failure
            }
        }
    }

    private fun requireRootEntry(file: File): File {
        val absolute = file.absoluteFile
        if (absolute.parentFile != root || absolute.name.isEmpty() ||
            absolute.name == "." || absolute.name == ".." ||
            absolute.name.contains('/') || absolute.name.contains('\\') ||
            absolute.name.indexOf('\u0000') >= 0
        ) {
            throw IOException("Book candidate is outside the private book root")
        }
        return absolute
    }

    private inline fun <T> withRoot(block: (PrivateBookStoreRootHandle) -> T): T {
        val handle = operations.openTrustedRoot(trustedParent, rootName, operations)
        return handle.use(block)
    }

    private fun PrivateBookStoreRootHandle.readStableState(file: File): ExistingBookFileState =
        openExistingBook(file).use { input -> input.verifiedStateAfterHash() }

    private fun ExistingBookFileState.sameIdentity(other: ExistingBookFileState): Boolean =
        if (device != null && inode != null && other.device != null && other.inode != null) {
            device == other.device && inode == other.inode
        } else {
            fileKey != null && fileKey == other.fileKey
        }
}

internal interface BookImportFileLockOperations {
    fun open(file: File): Any

    suspend fun acquire(handle: Any): Any

    fun release(lock: Any)

    fun close(handle: Any)
}

private object SystemBookImportFileLockOperations : BookImportFileLockOperations {
    override fun open(file: File): Any = FileChannel.open(
        file.toPath(),
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
    )

    override suspend fun acquire(handle: Any): Any {
        val channel = handle as FileChannel
        while (true) {
            currentCoroutineContext().ensureActive()
            val lock = channel.tryLock()
            if (lock != null) return lock
            delay(LOCK_RETRY_MILLIS)
        }
    }

    override fun release(lock: Any) {
        (lock as FileLock).release()
    }

    override fun close(handle: Any) {
        (handle as FileChannel).close()
    }

    private const val LOCK_RETRY_MILLIS = 25L
}

class PersistentHashBookImportCoordinator internal constructor(
    private val lockDirectory: File,
    private val operations: BookImportFileLockOperations,
) : BookImportCoordinator {
    constructor(lockDirectory: File) : this(lockDirectory, SystemBookImportFileLockOperations)

    override suspend fun <T> withHashLock(
        sha256: String,
        block: suspend () -> T,
    ): T {
        require(SHA_256.matches(sha256)) { "Invalid SHA-256 lock key" }
        val processLock = processLocks[Math.floorMod(sha256.hashCode(), processLocks.size)]
        processLock.lock()
        try {
            if (!lockDirectory.isDirectory && !lockDirectory.mkdirs() && !lockDirectory.isDirectory) {
                throw IOException("Unable to create private import lock directory")
            }
            val lockFile = File(lockDirectory, "$sha256.lock")
            val handle = operations.open(lockFile)
            val fileLock = try {
                operations.acquire(handle)
            } catch (failure: Throwable) {
                closeAfterAcquisitionFailure(handle, failure)
                throw failure
            }

            val outcome = try {
                Result.success(block())
            } catch (failure: Throwable) {
                Result.failure(failure)
            }
            val cleanupFailures = withContext(NonCancellable) {
                releaseAndClose(fileLock, handle)
            }
            val primary = outcome.exceptionOrNull()
            if (primary != null) {
                cleanupFailures.forEach { failure ->
                    if (failure !== primary) primary.addSuppressed(failure)
                }
                throw primary
            }
            return outcome.getOrThrow()
        } finally {
            processLock.unlock()
        }
    }

    private suspend fun closeAfterAcquisitionFailure(handle: Any, primary: Throwable) {
        withContext(NonCancellable) {
            try {
                operations.close(handle)
            } catch (closeFailure: Throwable) {
                if (closeFailure !== primary) primary.addSuppressed(closeFailure)
            }
        }
    }

    private fun releaseAndClose(lock: Any, handle: Any): List<Throwable> {
        val failures = mutableListOf<Throwable>()
        try {
            operations.release(lock)
        } catch (failure: Throwable) {
            failures += failure
        }
        try {
            operations.close(handle)
        } catch (failure: Throwable) {
            failures += failure
        }
        return failures
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
        val processLocks = Array(64) { Mutex() }
    }
}

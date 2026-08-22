package com.air5005.pagenest.library.importing

import com.wxn.base.bean.Book
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class BookImportService(
    private val privateBookStore: PrivateBookStore,
    private val protectionInspector: BookProtectionInspector,
    private val metadataParser: BookMetadataParser,
    private val catalog: BookImportCatalog,
    private val coordinator: BookImportCoordinator,
    private val privateBookFileValidator: PrivateBookFileValidator,
    private val deletePrivateFile: (File) -> Boolean = {
        Files.deleteIfExists(it.toPath())
    },
) {
    suspend fun execute(request: ImportRequest): ImportResult {
        val format = SupportedBookFormat.fromFileName(request.displayName)
            ?: return rejected(ImportRejection.UNSUPPORTED_FORMAT)
        val input = try {
            request.openInput()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.promotedCancellation()?.let { throw it }
            return rejected(ImportRejection.UNREADABLE)
        } catch (failure: LinkageError) {
            failure.promotedCancellation()?.let { throw it }
            return rejected(ImportRejection.UNREADABLE)
        }

        val publishedImport = store(input, request.displayName)
            ?: return rejected(ImportRejection.STORAGE_FAILED)
        val storedBook = publishedImport.storedBook

        var enteredLock = false
        var completedLockedBlock = false
        return try {
            coordinator.withHashLock(storedBook.sha256) {
                enteredLock = true
                val result = try {
                    executeLocked(publishedImport, input, format)
                } catch (cancellation: CancellationException) {
                    cleanupForCancellationInsideLock(
                        storedBook,
                        cancellation,
                        catalogStateUnknown = true,
                    )
                }
                completedLockedBlock = true
                result
            }
        } catch (failure: Throwable) {
            if (completedLockedBlock) throw failure
            if (!enteredLock) closeAfterFailure(input, failure)
            failure.promotedCancellation()?.let { throw it }
            when (failure) {
                is Exception,
                is LinkageError,
                -> rejected(ImportRejection.STORAGE_FAILED)
                else -> throw failure
            }
        }
    }

    private suspend fun executeLocked(
        publishedImport: PublishedImport,
        input: InputStream,
        format: SupportedBookFormat,
    ): ImportResult {
        val storedBook = publishedImport.storedBook
        val closeFailure = try {
            input.close()
            null
        } catch (failure: Throwable) {
            failure
        }
        if (closeFailure != null) {
            val postStoreFailure = publishedImport.postStoreFailure
            if (postStoreFailure != null &&
                postStoreFailure !== closeFailure &&
                closeFailure.suppressed.none { it === postStoreFailure }
            ) {
                closeFailure.addSuppressed(postStoreFailure)
            }
            val closeCancellation = closeFailure.promotedCancellation()
            if (closeCancellation != null) {
                postStoreFailure?.findCancellationInGraph()?.let { publishedCancellation ->
                    if (publishedCancellation !== closeCancellation &&
                        closeCancellation.suppressed.none { it === publishedCancellation }
                    ) {
                        closeCancellation.addSuppressed(publishedCancellation)
                    }
                }
                throw closeCancellation
            }
            return when (closeFailure) {
                is Exception,
                is LinkageError,
                -> rejectAndSafelyCleanupUnknownCatalog(storedBook)
                else -> rethrowAfterSafeCleanup(storedBook, closeFailure)
            }
        }

        val publishedCancellation = publishedImport.postStoreFailure
            ?.promotedCancellation()
        if (publishedCancellation != null) {
            throw publishedCancellation
        }

        if (!validateStoredFile(storedBook.file)) {
            return rejected(ImportRejection.STORAGE_FAILED)
        }

        val protectionVerdict = try {
            protectionInspector.inspect(storedBook.file, format)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(
                storedBook,
                ImportRejection.UNREADABLE,
            )
        } catch (failure: LinkageError) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(
                storedBook,
                ImportRejection.UNREADABLE,
            )
        }
        when (protectionVerdict) {
            ProtectionVerdict.PROTECTED ->
                return rejectAndSafelyCleanupUnknownCatalog(
                    storedBook,
                    ImportRejection.PROTECTED,
                )
            ProtectionVerdict.UNREADABLE ->
                return rejectAndSafelyCleanupUnknownCatalog(
                    storedBook,
                    ImportRejection.UNREADABLE,
                )
            ProtectionVerdict.CLEAR -> Unit
        }

        val existingMatch = try {
            catalog.findBySha256(storedBook.sha256)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(storedBook)
        } catch (failure: LinkageError) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(storedBook)
        }
        if (existingMatch != null) {
            return duplicateAndCleanupNewCopy(
                storedBook = storedBook,
                bookId = existingMatch.bookId,
                catalogFile = existingMatch.privateFile,
            )
        }

        val parsedBook = try {
            metadataParser.parse(storedBook.file, format)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        } catch (failure: LinkageError) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        } ?: return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        val privateBook = parsedBook.withPrivateFile(storedBook.file)

        val writeResult = try {
            catalog.insertOrGet(privateBook, storedBook.sha256)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(storedBook)
        } catch (failure: LinkageError) {
            failure.promotedCancellation()?.let { throw it }
            return rejectAndSafelyCleanupUnknownCatalog(storedBook)
        }
        return when (writeResult) {
            is CatalogWriteResult.Inserted -> ImportResult.Imported(writeResult.bookId)
            is CatalogWriteResult.Existing ->
                duplicateAndCleanupNewCopy(
                    storedBook = storedBook,
                    bookId = writeResult.bookId,
                    catalogFile = writeResult.privateFile,
                )
        }
    }

    private fun store(input: InputStream, displayName: String): PublishedImport? = try {
        PublishedImport(privateBookStore.store(input, displayName))
    } catch (cancellation: CancellationException) {
        closeAfterFailure(input, cancellation)
        throw cancellation
    } catch (published: PublishedBookCleanupException) {
        PublishedImport(published.storedBook, published)
    } catch (failure: Throwable) {
        closeAfterFailure(input, failure)
        failure.promotedCancellation()?.let { throw it }
        when (failure) {
            is Exception,
            is LinkageError,
            -> null
            else -> throw failure
        }
    }

    private fun validateStoredFile(file: File): Boolean = try {
        privateBookFileValidator.validate(file)
    } catch (failure: Throwable) {
        failure.promotedCancellation()?.let { throw it }
        when (failure) {
            is Exception,
            is LinkageError,
            -> false
            else -> throw failure
        }
    }

    private fun duplicateAndCleanupNewCopy(
        storedBook: StoredBook,
        bookId: Long,
        catalogFile: File,
    ): ImportResult {
        if (storedBook.wasExisting) return ImportResult.Duplicate(bookId)
        return when (matchPrivateFiles(storedBook.file, catalogFile)) {
            PrivateBookFileMatch.INVALID -> rejected(ImportRejection.STORAGE_FAILED)
            PrivateBookFileMatch.SAME -> ImportResult.Duplicate(bookId)
            PrivateBookFileMatch.DIFFERENT -> if (deleteStoredFile(storedBook.file)) {
                ImportResult.Duplicate(bookId)
            } else {
                rejected(ImportRejection.STORAGE_FAILED)
            }
        }
    }

    private fun matchPrivateFiles(
        storedFile: File,
        catalogFile: File,
    ): PrivateBookFileMatch = try {
        privateBookFileValidator.match(storedFile, catalogFile)
    } catch (failure: Throwable) {
        failure.promotedCancellation()?.let { throw it }
        when (failure) {
            is Exception,
            is LinkageError,
            -> PrivateBookFileMatch.INVALID
            else -> throw failure
        }
    }

    private fun rejectAndCleanup(
        storedBook: StoredBook,
        reason: ImportRejection,
    ): ImportResult.Rejected {
        if (storedBook.wasExisting) return rejected(reason)
        return if (deleteStoredFile(storedBook.file)) {
            rejected(reason)
        } else {
            rejected(ImportRejection.STORAGE_FAILED)
        }
    }

    private suspend fun rejectAndSafelyCleanupUnknownCatalog(
        storedBook: StoredBook,
        reason: ImportRejection = ImportRejection.STORAGE_FAILED,
    ): ImportResult.Rejected {
        val cleanupFailure = cleanupNewCopyInsideLock(storedBook, catalogStateUnknown = true)
        cleanupFailure?.promotedCancellation()?.let { throw it }
        return when (cleanupFailure) {
            null -> rejected(reason)
            is Exception,
            is LinkageError,
            -> rejected(ImportRejection.STORAGE_FAILED)
            else -> throw cleanupFailure
        }
    }

    private suspend fun rethrowAfterSafeCleanup(
        storedBook: StoredBook,
        failure: Throwable,
    ): Nothing {
        val cleanupFailure = cleanupNewCopyInsideLock(storedBook, catalogStateUnknown = true)
        if (cleanupFailure != null && cleanupFailure !== failure) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }

    private fun deleteStoredFile(file: File): Boolean = try {
        deletePrivateFile(file)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        failure.promotedCancellation()?.let { throw it }
        false
    } catch (failure: LinkageError) {
        failure.promotedCancellation()?.let { throw it }
        false
    }

    private suspend fun cleanupForCancellationInsideLock(
        storedBook: StoredBook,
        cancellation: CancellationException,
        catalogStateUnknown: Boolean = false,
    ): Nothing {
        val cleanupFailure = cleanupNewCopyInsideLock(storedBook, catalogStateUnknown)
        if (cleanupFailure != null && cleanupFailure !== cancellation) {
            cancellation.addSuppressed(cleanupFailure)
        }
        throw cancellation
    }

    private suspend fun cleanupNewCopyInsideLock(
        storedBook: StoredBook,
        catalogStateUnknown: Boolean,
    ): Throwable? {
        if (storedBook.wasExisting) return null
        if (catalogStateUnknown) {
            val existingMatch = try {
                catalog.findBySha256(storedBook.sha256)
            } catch (failure: Throwable) {
                return failure
            }
            if (existingMatch != null) {
                return when (matchPrivateFiles(storedBook.file, existingMatch.privateFile)) {
                    PrivateBookFileMatch.SAME -> null
                    PrivateBookFileMatch.DIFFERENT -> deleteFailure(storedBook.file)
                    PrivateBookFileMatch.INVALID ->
                        IOException("Unable to validate the catalog's private book file")
                }
            }
        }
        return deleteFailure(storedBook.file)
    }

    private fun deleteFailure(file: File): Throwable? = try {
        if (deletePrivateFile(file)) null
        else IOException("Private book cleanup did not delete $file")
    } catch (failure: Throwable) {
        failure
    }

    private fun closeAfterFailure(input: InputStream, failure: Throwable) {
        try {
            input.close()
        } catch (closeFailure: Throwable) {
            val closeCancellation = closeFailure.promotedCancellation()
            if (closeCancellation != null) {
                if (closeCancellation !== failure &&
                    closeCancellation.suppressed.none { it === failure }
                ) {
                    closeCancellation.addSuppressed(failure)
                }
                throw closeCancellation
            }
            when (closeFailure) {
                is Exception,
                is LinkageError,
                -> failure.addSuppressed(closeFailure)
                else -> {
                    if (closeFailure !== failure) closeFailure.addSuppressed(failure)
                    throw closeFailure
                }
            }
        }
    }

    private fun Book.withPrivateFile(file: File): Book = copy(filePath = file.toURI().toString())

    private data class PublishedImport(
        val storedBook: StoredBook,
        val postStoreFailure: PublishedBookCleanupException? = null,
    )

    private fun rejected(reason: ImportRejection) = ImportResult.Rejected(reason)
}

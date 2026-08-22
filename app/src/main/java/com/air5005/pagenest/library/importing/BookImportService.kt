package com.air5005.pagenest.library.importing

import com.wxn.base.bean.Book
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.CancellationException

class BookImportService(
    private val privateBookStore: PrivateBookStore,
    private val protectionInspector: BookProtectionInspector,
    private val metadataParser: BookMetadataParser,
    private val catalog: BookImportCatalog,
    private val coordinator: BookImportCoordinator,
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
        } catch (_: Exception) {
            return rejected(ImportRejection.UNREADABLE)
        } catch (_: LinkageError) {
            return rejected(ImportRejection.UNREADABLE)
        }

        val storedBook = store(input, request.displayName)
            ?: return rejected(ImportRejection.STORAGE_FAILED)
        try {
            input.close()
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        } catch (_: LinkageError) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        }

        var enteredLock = false
        var completedLockedBlock = false
        return try {
            coordinator.withHashLock(storedBook.sha256) {
                enteredLock = true
                val result = try {
                    executeLocked(storedBook, format)
                } catch (cancellation: CancellationException) {
                    cleanupForCancellation(storedBook, cancellation)
                }
                completedLockedBlock = true
                result
            }
        } catch (cancellation: CancellationException) {
            if (!enteredLock) cleanupForCancellation(storedBook, cancellation)
            throw cancellation
        } catch (failure: Exception) {
            if (completedLockedBlock) throw failure
            rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        } catch (failure: LinkageError) {
            if (completedLockedBlock) throw failure
            rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        }
    }

    private suspend fun executeLocked(
        storedBook: StoredBook,
        format: SupportedBookFormat,
    ): ImportResult {
        if (!storedFileIsRegular(storedBook.file)) {
            return rejected(ImportRejection.STORAGE_FAILED)
        }

        val protectionVerdict = try {
            protectionInspector.inspect(storedBook.file, format)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.UNREADABLE)
        } catch (_: LinkageError) {
            return rejectAndCleanup(storedBook, ImportRejection.UNREADABLE)
        }
        when (protectionVerdict) {
            ProtectionVerdict.PROTECTED ->
                return rejectAndCleanup(storedBook, ImportRejection.PROTECTED)
            ProtectionVerdict.UNREADABLE ->
                return rejectAndCleanup(storedBook, ImportRejection.UNREADABLE)
            ProtectionVerdict.CLEAR -> Unit
        }

        val existingMatch = try {
            catalog.findBySha256(storedBook.sha256)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        } catch (_: LinkageError) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
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
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        } catch (_: LinkageError) {
            return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        } ?: return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        val privateBook = parsedBook.withPrivateFile(storedBook.file)

        val writeResult = try {
            catalog.insertOrGet(privateBook, storedBook.sha256)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        } catch (_: LinkageError) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
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

    private fun store(input: InputStream, displayName: String): StoredBook? = try {
        privateBookStore.store(input, displayName)
    } catch (cancellation: CancellationException) {
        closeAfterFailure(input, cancellation)
        throw cancellation
    } catch (published: PublishedBookCleanupException) {
        val cancellation = published.cause as? CancellationException
        if (cancellation != null) {
            val propagatedCancellation = closeForPublishedCancellation(input, cancellation)
            cleanupForCancellation(published.storedBook, propagatedCancellation)
        }
        published.storedBook
    } catch (failure: Throwable) {
        closeAfterFailure(input, failure)
        when (failure) {
            is Exception,
            is LinkageError,
            -> null
            else -> throw failure
        }
    }

    private fun storedFileIsRegular(file: File): Boolean = try {
        Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun duplicateAndCleanupNewCopy(
        storedBook: StoredBook,
        bookId: Long,
        catalogFile: File,
    ): ImportResult {
        if (storedBook.wasExisting) return ImportResult.Duplicate(bookId)
        return when (samePrivateFile(storedBook.file, catalogFile)) {
            null -> rejected(ImportRejection.STORAGE_FAILED)
            true -> ImportResult.Duplicate(bookId)
            false -> if (deleteStoredFile(storedBook.file)) {
                ImportResult.Duplicate(bookId)
            } else {
                rejected(ImportRejection.STORAGE_FAILED)
            }
        }
    }

    private fun samePrivateFile(storedFile: File, catalogFile: File): Boolean? = try {
        storedFile.canonicalFile.toPath().normalize() ==
            catalogFile.canonicalFile.toPath().normalize()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
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

    private fun deleteStoredFile(file: File): Boolean = try {
        deletePrivateFile(file)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun cleanupForCancellation(
        storedBook: StoredBook,
        cancellation: CancellationException,
    ): Nothing {
        if (!storedBook.wasExisting) {
            try {
                if (!deletePrivateFile(storedBook.file)) {
                    cancellation.addSuppressed(
                        IOException("Private book cleanup did not delete ${storedBook.file}"),
                    )
                }
            } catch (cleanupCancellation: CancellationException) {
                if (cleanupCancellation !== cancellation) {
                    cancellation.addSuppressed(cleanupCancellation)
                }
            } catch (cleanupFailure: Throwable) {
                cancellation.addSuppressed(cleanupFailure)
            }
        }
        throw cancellation
    }

    private fun closeForPublishedCancellation(
        input: InputStream,
        publicationCancellation: CancellationException,
    ): CancellationException = try {
        input.close()
        publicationCancellation
    } catch (closeCancellation: CancellationException) {
        if (closeCancellation !== publicationCancellation) {
            closeCancellation.addSuppressed(publicationCancellation)
        }
        closeCancellation
    } catch (closeFailure: Throwable) {
        publicationCancellation.addSuppressed(closeFailure)
        publicationCancellation
    }

    private fun closeAfterFailure(input: InputStream, failure: Throwable) {
        try {
            input.close()
        } catch (closeCancellation: CancellationException) {
            if (closeCancellation !== failure) {
                closeCancellation.addSuppressed(failure)
            }
            throw closeCancellation
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun Book.withPrivateFile(file: File): Book = copy(filePath = file.toURI().toString())

    private fun rejected(reason: ImportRejection) = ImportResult.Rejected(reason)
}

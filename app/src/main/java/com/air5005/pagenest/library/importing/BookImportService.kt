package com.air5005.pagenest.library.importing

import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CancellationException

class BookImportService(
    private val privateBookStore: PrivateBookStore,
    private val protectionInspector: BookProtectionInspector,
    private val metadataParser: BookMetadataParser,
    private val catalog: BookImportCatalog,
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
        }

        val storedBook = store(input, request.displayName)
            ?: return rejected(ImportRejection.STORAGE_FAILED)
        try {
            input.close()
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        }

        val protectionVerdict = try {
            protectionInspector.inspect(storedBook.file, format)
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.UNREADABLE)
        }
        when (protectionVerdict) {
            ProtectionVerdict.PROTECTED ->
                return rejectAndCleanup(storedBook, ImportRejection.PROTECTED)
            ProtectionVerdict.UNREADABLE ->
                return rejectAndCleanup(storedBook, ImportRejection.UNREADABLE)
            ProtectionVerdict.CLEAR -> Unit
        }

        val existingBookId = try {
            catalog.findBySha256(storedBook.sha256)
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        }
        if (existingBookId != null) return ImportResult.Duplicate(existingBookId)

        val parsedBook = try {
            metadataParser.parse(storedBook.file, format)
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)
        } ?: return rejectAndCleanup(storedBook, ImportRejection.PARSE_FAILED)

        val bookId = try {
            catalog.insert(parsedBook, storedBook.sha256)
        } catch (cancellation: CancellationException) {
            cleanupForCancellation(storedBook, cancellation)
        } catch (_: Exception) {
            return rejectAndCleanup(storedBook, ImportRejection.STORAGE_FAILED)
        }
        return ImportResult.Imported(bookId)
    }

    private fun store(input: InputStream, displayName: String): StoredBook? = try {
        privateBookStore.store(input, displayName)
    } catch (cancellation: CancellationException) {
        closeAfterFailure(input, cancellation)
        throw cancellation
    } catch (published: PublishedBookCleanupException) {
        published.storedBook
    } catch (failure: Exception) {
        closeAfterFailure(input, failure)
        null
    }

    private fun rejectAndCleanup(
        storedBook: StoredBook,
        reason: ImportRejection,
    ): ImportResult.Rejected {
        if (storedBook.wasExisting) return rejected(reason)
        return try {
            Files.deleteIfExists(storedBook.file.toPath())
            rejected(reason)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            rejected(ImportRejection.STORAGE_FAILED)
        }
    }

    private fun cleanupForCancellation(
        storedBook: StoredBook,
        cancellation: CancellationException,
    ): Nothing {
        if (!storedBook.wasExisting) {
            try {
                Files.deleteIfExists(storedBook.file.toPath())
            } catch (cleanupCancellation: CancellationException) {
                if (cleanupCancellation !== cancellation) {
                    cancellation.addSuppressed(cleanupCancellation)
                }
            } catch (cleanupFailure: Exception) {
                cancellation.addSuppressed(cleanupFailure)
            }
        }
        throw cancellation
    }

    private fun closeAfterFailure(input: InputStream, failure: Throwable) {
        try {
            input.close()
        } catch (closeCancellation: CancellationException) {
            if (failure is CancellationException) {
                if (closeCancellation !== failure) failure.addSuppressed(closeCancellation)
            } else {
                closeCancellation.addSuppressed(failure)
                throw closeCancellation
            }
        } catch (closeFailure: Throwable) {
            failure.addSuppressed(closeFailure)
        }
    }

    private fun rejected(reason: ImportRejection) = ImportResult.Rejected(reason)
}

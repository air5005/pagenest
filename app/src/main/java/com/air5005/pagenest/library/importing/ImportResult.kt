package com.air5005.pagenest.library.importing

import com.wxn.base.bean.Book
import java.io.File
import java.io.InputStream

sealed interface ImportResult {
    data class Imported(val bookId: Long) : ImportResult

    data class Duplicate(val bookId: Long) : ImportResult

    data class Rejected(val reason: ImportRejection) : ImportResult
}

enum class ImportRejection {
    UNSUPPORTED_FORMAT,
    PROTECTED,
    UNREADABLE,
    PARSE_FAILED,
    STORAGE_FAILED,
}

data class ImportRequest(
    val displayName: String,
    val openInput: () -> InputStream,
)

fun interface BookMetadataParser {
    suspend fun parse(file: File, format: SupportedBookFormat): Book?
}

sealed interface CatalogWriteResult {
    data class Inserted(val bookId: Long) : CatalogWriteResult

    data class Existing(val bookId: Long, val privateFile: File) : CatalogWriteResult
}

data class CatalogMatch(val bookId: Long, val privateFile: File)

interface BookImportCatalog {
    /** Returns the ID and private-file candidate represented by an existing SHA-256 row. */
    suspend fun findBySha256(sha256: String): CatalogMatch?

    /**
     * Atomically inserts [book] under a database-unique [sha256], or returns the winning row.
     *
     * Task 7 must implement this as one Room transaction backed by a SHA-256 unique constraint,
     * return the generated database ID, and never derive an ID from an inserted-row count. Its
     * public boundary must not throw cancellation or another failure after commit; if an
     * underlying call can do so, the adapter must resolve the committed row before returning or
     * throwing. [BookImportService] defensively re-queries after failures, but that is not a
     * substitute for this transaction contract.
     */
    suspend fun insertOrGet(book: Book, sha256: String): CatalogWriteResult
}

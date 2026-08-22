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
    /** Returns the ID and canonical app-private file represented by an existing SHA-256 row. */
    suspend fun findBySha256(sha256: String): CatalogMatch?

    /**
     * Atomically inserts [book] under a database-unique [sha256], or returns the winning row.
     *
     * Task 7 must implement this as one Room transaction backed by a SHA-256 unique constraint
     * and must return the generated database ID. This method must not derive an ID from an
     * inserted-row count. If it throws, no new catalog record may be visible.
     */
    suspend fun insertOrGet(book: Book, sha256: String): CatalogWriteResult
}

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

interface BookImportCatalog {
    suspend fun findBySha256(sha256: String): Long?

    /**
     * Atomically inserts [book] under [sha256]. If this throws, no catalog record may be visible.
     */
    suspend fun insert(book: Book, sha256: String): Long
}

package com.air5005.pagenest.discovery.importing

import com.air5005.pagenest.discovery.download.DownloadProgress
import com.air5005.pagenest.discovery.download.DownloadRequest
import com.air5005.pagenest.discovery.download.DownloadResult
import com.air5005.pagenest.discovery.download.SecureBookDownloader
import com.air5005.pagenest.library.importing.BookImportService
import com.air5005.pagenest.library.importing.ImportRequest
import com.air5005.pagenest.library.importing.ImportResult
import com.wxn.reader.domain.repository.BooksRepository
import java.io.File

interface OnlineBookDownloader {
    suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult
}

class SecureOnlineBookDownloader(
    private val delegate: SecureBookDownloader,
) : OnlineBookDownloader {
    override suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult = delegate.download(request, onProgress)
}

interface OnlineBookImporter {
    suspend fun import(file: File, displayName: String): ImportResult
}

class BookImportServiceAdapter(
    private val delegate: BookImportService,
) : OnlineBookImporter {
    override suspend fun import(file: File, displayName: String): ImportResult = delegate.execute(
        ImportRequest(
            displayName = displayName,
            openInput = file::inputStream,
        ),
    )
}

fun interface LocalBookLookup {
    suspend fun exists(bookId: Long): Boolean
}

class BooksRepositoryLocalBookLookup(
    private val repository: BooksRepository,
) : LocalBookLookup {
    override suspend fun exists(bookId: Long): Boolean = repository.getBookById(bookId) != null
}

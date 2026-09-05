package com.wxn.reader.presentation.home

import androidx.activity.result.contract.ActivityResultContracts

internal val BOOK_IMPORT_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "text/plain",
    "application/pdf",
    "application/x-mobipocket-ebook",
    "application/vnd.amazon.ebook",
    "application/octet-stream",
)

internal fun bookImportPickerContract() = ActivityResultContracts.OpenMultipleDocuments()

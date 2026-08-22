package com.air5005.pagenest.library.importing

enum class SupportedBookFormat(val extension: String) {
    EPUB("epub"),
    TXT("txt"),
    PDF("pdf"),
    MOBI("mobi"),
    AZW3("azw3");

    companion object {
        fun fromFileName(fileName: String): SupportedBookFormat? {
            if (fileName.startsWith('.') || '/' in fileName || '\\' in fileName) return null

            val extension = fileName.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == extension }
        }
    }
}

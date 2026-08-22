package com.air5005.pagenest.library.importing

import java.io.File

enum class ProtectionVerdict {
    CLEAR,
    PROTECTED,
    UNREADABLE,
}

fun interface BookProtectionInspector {
    fun inspect(file: File, format: SupportedBookFormat): ProtectionVerdict
}

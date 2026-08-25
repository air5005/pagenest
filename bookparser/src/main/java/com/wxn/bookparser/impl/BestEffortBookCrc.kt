package com.wxn.bookparser.impl

internal inline fun bestEffortBookCrc(
    filePath: String,
    lookup: (String) -> Int?,
): Int = try {
    lookup(filePath) ?: 0
} catch (_: Exception) {
    0
} catch (_: LinkageError) {
    0
}

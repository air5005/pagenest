package com.air5005.pagenest.library.importing

import java.io.File

enum class PrivateBookFileMatch {
    SAME,
    DIFFERENT,
    INVALID,
}

/**
 * Validates live app-private book identities without trusting path text or following links.
 *
 * Task 7's production implementation must pin the trusted books root by directory descriptor,
 * open each relative entry with no-follow semantics, reject missing/outside/link/non-regular
 * entries, and compare the identities of the live opened regular files. Path canonicalization is
 * not a substitute for this contract because it follows symbolic links and is race-prone.
 */
interface PrivateBookFileValidator {
    fun validate(file: File): Boolean

    fun match(storedFile: File, catalogFile: File): PrivateBookFileMatch
}

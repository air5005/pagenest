package com.air5005.pagenest.library.importing

import java.io.File

enum class DuplicateResolution {
    SAME,
    DIFFERENT,
    INVALID,
    CLEANUP_FAILED,
}

/**
 * Owns live app-private validation and descriptor-relative mutation as one trust boundary.
 *
 * Task 7's production implementation must reuse Task 5's pinned trusted-root directory descriptor
 * and perform descriptor-relative `NOFOLLOW` opens/`fstatat`, `unlinkat`, and parent-directory
 * `fsync`. It rejects missing/outside/link/non-regular entries and compares identities of live
 * opened regular files. [resolveDuplicate] must combine comparison and any redundant-new-copy
 * removal without a service-visible match-then-delete gap. [deleteNewCopy] must never delete when
 * [StoredBook.wasExisting] is true. Path canonicalization is not a substitute for this contract
 * because it follows symbolic links and is race-prone.
 *
 * Task 5's threat model does not claim protection from arbitrary malicious code sharing the app
 * UID. These operations do prevent cooperating PageNest lifecycle paths from bypassing the
 * trusted-root, no-follow, per-SHA mutation boundary.
 */
interface PrivateBookFileValidator {
    fun validate(file: File): Boolean

    fun resolveDuplicate(storedBook: StoredBook, catalogFile: File): DuplicateResolution

    fun deleteNewCopy(storedBook: StoredBook): Boolean
}

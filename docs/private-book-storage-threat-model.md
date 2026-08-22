# Private book storage threat model

PageNest's private book store treats imported book bytes and existing directory entries as untrusted. It is designed to fail safely for malformed content, symbolic links and special files, interrupted writes, process crashes, and concurrent publication by cooperating PageNest processes.

On Android, production callers anchor storage at `Context.filesDir` and pass a single validated root basename. The store pins parent and root directory descriptors, creates random no-follow part files, synchronizes each completed part, and serializes publication through a persistent root-relative `flock`. While holding that lock it checks the part descriptor against the part entry's complete regular-file state, invokes `renameat2(RENAME_NOREPLACE)`, checks the final entry, and synchronizes the pinned root. A kernel that does not support no-replace rename causes publication to fail closed. Publication never requires a hard link or `/proc/self/fd` path.

The JVM adapter requires a filesystem-provided non-null `fileKey`; it does not invent identity from millisecond timestamps. Existing files are checked using regular-file type, identity, size, nanosecond mtime, available Unix device/inode/ctime state, and content digests before acceptance.

This protocol does not claim to isolate the store from arbitrary malicious code running under the same Android UID. Such code already has direct authority to modify the app's private files, including a published final file, so a file protocol cannot provide that security boundary. Random part names, no-follow opens, and repeated full-state checks remain defense in depth; the cross-process publication lock assumes cooperating PageNest processes use the protocol.

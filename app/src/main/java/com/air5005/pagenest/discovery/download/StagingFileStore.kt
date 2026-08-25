package com.air5005.pagenest.discovery.download

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.Instant

class StagingFileStore(
    private val directory: File,
) {
    init {
        check(directory.mkdirs() || directory.isDirectory) { "Unable to initialize staging directory" }
    }

    fun createPart(): File = File.createTempFile("download-", PART_SUFFIX, directory)

    fun cleanupOldParts(
        olderThan: Duration = DEFAULT_MAX_AGE,
        now: Instant = Instant.now(),
    ) {
        val trustedDirectory = directory.toPath().toAbsolutePath().normalize()
        Files.newDirectoryStream(trustedDirectory).use { entries ->
            for (entry in entries) {
                if (!entry.fileName.toString().endsWith(PART_SUFFIX)) continue
                val attributes = try {
                    Files.readAttributes(
                        entry,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                } catch (_: Exception) {
                    continue
                }
                if (!attributes.isRegularFile || attributes.isSymbolicLink) continue
                val modifiedAt = attributes.lastModifiedTime().toInstant()
                if (!modifiedAt.plus(olderThan).isAfter(now)) {
                    try {
                        Files.deleteIfExists(entry)
                    } catch (_: Exception) {
                        // A later startup can retry; cleanup must not block application startup.
                    }
                }
            }
        }
    }

    companion object {
        private const val PART_SUFFIX = ".part"
        private val DEFAULT_MAX_AGE: Duration = Duration.ofDays(1)
    }
}

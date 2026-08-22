package com.air5005.pagenest.library.importing

import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.UUID

data class StoredBook(
    val file: File,
    val sha256: String,
    val wasExisting: Boolean,
)

class PublishedBookCleanupException(
    val storedBook: StoredBook,
    cause: Throwable,
) : IOException("Book was published, but temporary cleanup was not durably synchronized", cause)

class PrivateBookStore internal constructor(
    private val root: File,
    private val fileOperations: PrivateBookStoreFileOperations,
) {
    constructor(root: File) : this(root, SystemPrivateBookStoreFileOperations)

    fun store(input: InputStream, originalName: String): StoredBook {
        val format = requireNotNull(SupportedBookFormat.fromFileName(originalName)) {
            "Unsupported book file name: $originalName"
        }
        ensureRootDirectory()

        val part = fileOperations.createPart(root)
        val storedBook = try {
            val digest = MessageDigest.getInstance("SHA-256")
            fileOperations.openPart(part).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
                output.flush()
                output.sync()
            }

            val sha256 = digest.digest().toHexString()
            val finalFile = File(root, "$sha256.${format.extension}")
            val wasExisting = try {
                fileOperations.publishAtomically(part, finalFile)
                false
            } catch (_: FileAlreadyExistsException) {
                validateExistingBook(finalFile, sha256)
                true
            }
            StoredBook(finalFile, sha256, wasExisting)
        } catch (failure: Throwable) {
            cleanupUnpublishedPart(part, failure)
            throw failure
        }

        fileOperations.syncDirectory(root)

        try {
            if (!fileOperations.deletePart(part)) {
                throw IOException("Temporary book part disappeared before cleanup")
            }
            fileOperations.syncDirectory(root)
        } catch (cleanupFailure: Throwable) {
            throw PublishedBookCleanupException(storedBook, cleanupFailure)
        }
        return storedBook
    }

    private fun ensureRootDirectory() {
        val directory = root.absoluteFile
        if (!createDirectoryTreeDurably(directory)) {
            val parent = directory.parentFile
                ?: throw IOException("Private book directory must have a parent: $directory")
            fileOperations.syncDirectory(parent)
        }
    }

    private fun createDirectoryTreeDurably(directory: File): Boolean {
        val path = directory.toPath()
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return false
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Private book path is not a directory: $directory")
        }

        val parent = directory.parentFile
            ?: throw IOException("Private book directory must have a parent: $directory")
        if (!Files.isDirectory(parent.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            createDirectoryTreeDurably(parent)
        }

        Files.createDirectory(path)
        try {
            fileOperations.syncDirectory(parent)
        } catch (failure: Throwable) {
            try {
                if (Files.deleteIfExists(path)) fileOperations.syncDirectory(parent)
            } catch (rollbackFailure: Throwable) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
        return true
    }

    private fun validateExistingBook(file: File, expectedSha256: String) {
        if (!Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException("Existing book target is not a regular file")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        if (digest.digest().toHexString() != expectedSha256) {
            throw IOException("Existing book does not match its SHA-256 file name")
        }
    }

    private fun cleanupUnpublishedPart(part: File, failure: Throwable) {
        try {
            if (fileOperations.deletePart(part)) fileOperations.syncDirectory(root)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 8 * 1024
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

internal interface PrivateBookStoreFileOperations {
    fun createPart(root: File): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun syncDirectory(directory: File)

    fun deletePart(file: File): Boolean
}

internal interface DurableBookOutput : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int)

    fun flush()

    fun sync()
}

internal object SystemPrivateBookStoreFileOperations : PrivateBookStoreFileOperations {
    override fun createPart(root: File): File {
        while (true) {
            val candidate = File(root, "${UUID.randomUUID()}.part")
            if (candidate.createNewFile()) return candidate
        }
    }

    override fun openPart(file: File): DurableBookOutput = FileDurableBookOutput(file)

    override fun publishAtomically(source: File, target: File) {
        Files.createLink(target.toPath(), source.toPath())
    }

    override fun syncDirectory(directory: File) {
        if (File.separatorChar == WINDOWS_SEPARATOR) return
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    override fun deletePart(file: File): Boolean = Files.deleteIfExists(file.toPath())

    private const val WINDOWS_SEPARATOR = '\\'
}

private class FileDurableBookOutput(file: File) : DurableBookOutput {
    private val output = FileOutputStream(file)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
    }

    override fun flush() {
        output.flush()
    }

    override fun sync() {
        output.fd.sync()
    }

    override fun close() {
        output.close()
    }
}

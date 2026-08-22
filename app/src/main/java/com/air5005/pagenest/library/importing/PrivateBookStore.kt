package com.air5005.pagenest.library.importing

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
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
            var copiedSize = 0L
            fileOperations.openPart(part).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    copiedSize += count
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
                validateExistingBook(finalFile, sha256, copiedSize)
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

        var createdByThisStore = false
        try {
            fileOperations.createDirectory(directory)
            createdByThisStore = true
        } catch (failure: FileAlreadyExistsException) {
            if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Private book path is not a directory: $directory", failure)
            }
        }
        try {
            fileOperations.syncDirectory(parent)
        } catch (failure: Throwable) {
            if (createdByThisStore) {
                try {
                    if (Files.deleteIfExists(path)) fileOperations.syncDirectory(parent)
                } catch (rollbackFailure: Throwable) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
        return true
    }

    private fun validateExistingBook(file: File, expectedSha256: String, expectedSize: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
        fileOperations.openExistingBook(file).use { input ->
            if (input.size != expectedSize) {
                throw IOException("Existing book does not match its SHA-256 file name")
            }
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer, 0, buffer.size)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            input.verifyPathStillMatches()
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
    fun createDirectory(directory: File)

    fun createPart(root: File): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun openExistingBook(file: File): ExistingBookInput

    fun syncDirectory(directory: File)

    fun deletePart(file: File): Boolean
}

internal interface DurableBookOutput : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int)

    fun flush()

    fun sync()
}

internal interface ExistingBookInput : Closeable {
    val size: Long

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun verifyPathStillMatches()
}

internal object SystemPrivateBookStoreFileOperations : PrivateBookStoreFileOperations {
    override fun createDirectory(directory: File) {
        Files.createDirectory(directory.toPath())
    }

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

    override fun openExistingBook(file: File): ExistingBookInput =
        if (isAndroidRuntime()) {
            AndroidExistingBookInput.open(file)
        } else {
            JvmExistingBookInput.open(file)
        }

    override fun syncDirectory(directory: File) {
        if (File.separatorChar == WINDOWS_SEPARATOR) return
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    override fun deletePart(file: File): Boolean = Files.deleteIfExists(file.toPath())

    private fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name") == "Dalvik" ||
            System.getProperty("java.runtime.name") == "Android Runtime"

    private const val WINDOWS_SEPARATOR = '\\'
}

private class JvmExistingBookInput private constructor(
    private val channel: FileChannel,
    private val path: java.nio.file.Path,
    private val fileKey: Any?,
) : ExistingBookInput {
    private val input = Channels.newInputStream(channel)

    override val size: Long = channel.size()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun verifyPathStillMatches() {
        val current = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!current.isRegularFile ||
            (fileKey != null && current.fileKey() != fileKey)
        ) {
            throw IOException("Existing book target changed during validation")
        }
    }

    override fun close() {
        input.close()
    }

    companion object {
        fun open(file: File): ExistingBookInput {
            val path = file.toPath()
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Existing book target is not a regular file")
            }
            val channel = try {
                FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
            } catch (failure: Throwable) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Existing book target is not a regular file", failure)
                }
                throw failure
            }
            try {
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                if (!attributes.isRegularFile) {
                    throw IOException("Existing book target is not a regular file")
                }
                return JvmExistingBookInput(channel, path, attributes.fileKey())
            } catch (failure: Throwable) {
                try {
                    channel.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
}

private class AndroidExistingBookInput private constructor(
    private val descriptor: java.io.FileDescriptor,
    private val path: String,
    private val device: Long,
    private val inode: Long,
    override val size: Long,
) : ExistingBookInput {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = try {
            Os.read(descriptor, buffer, offset, length)
        } catch (failure: ErrnoException) {
            throw failure.rethrowAsIOException()
        }
        return if (count == 0) -1 else count
    }

    override fun verifyPathStillMatches() {
        val current = try {
            Os.lstat(path)
        } catch (failure: ErrnoException) {
            throw failure.rethrowAsIOException()
        }
        if (!OsConstants.S_ISREG(current.st_mode) ||
            current.st_dev != device ||
            current.st_ino != inode
        ) {
            throw IOException("Existing book target changed during validation")
        }
    }

    override fun close() {
        try {
            Os.close(descriptor)
        } catch (failure: ErrnoException) {
            throw failure.rethrowAsIOException()
        }
    }

    companion object {
        fun open(file: File): ExistingBookInput {
            val descriptor = try {
                Os.open(
                    file.absolutePath,
                    OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or
                        OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK,
                    0,
                )
            } catch (failure: ErrnoException) {
                throw failure.rethrowAsIOException()
            }
            try {
                val stat = Os.fstat(descriptor)
                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw IOException("Existing book target is not a regular file")
                }
                return AndroidExistingBookInput(
                    descriptor,
                    file.absolutePath,
                    stat.st_dev,
                    stat.st_ino,
                    stat.st_size,
                )
            } catch (failure: Throwable) {
                try {
                    Os.close(descriptor)
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }
    }
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

package com.air5005.pagenest.library.importing

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
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

        return fileOperations.openRoot(root.absoluteFile, fileOperations).use { rootHandle ->
            storeInPinnedRoot(input, format, rootHandle)
        }
    }

    private fun storeInPinnedRoot(
        input: InputStream,
        format: SupportedBookFormat,
        rootHandle: PrivateBookStoreRootHandle,
    ): StoredBook {
        val part = rootHandle.createPart()
        val storedBook = try {
            val digest = MessageDigest.getInstance("SHA-256")
            var copiedSize = 0L
            rootHandle.openPart(part).use { output ->
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
                rootHandle.publishAtomically(part, finalFile)
                false
            } catch (_: FileAlreadyExistsException) {
                validateExistingBook(finalFile, sha256, copiedSize, rootHandle)
                true
            }
            StoredBook(finalFile, sha256, wasExisting)
        } catch (failure: Throwable) {
            cleanupUnpublishedPart(part, failure, rootHandle)
            throw failure
        }

        rootHandle.syncDirectory()

        try {
            if (!rootHandle.deletePart(part)) {
                throw IOException("Temporary book part disappeared before cleanup")
            }
            rootHandle.syncDirectory()
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

    private fun validateExistingBook(
        file: File,
        expectedSha256: String,
        expectedSize: Long,
        rootHandle: PrivateBookStoreRootHandle,
    ) {
        val digest = MessageDigest.getInstance("SHA-256")
        rootHandle.openExistingBook(file).use { input ->
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

    private fun cleanupUnpublishedPart(
        part: File,
        failure: Throwable,
        rootHandle: PrivateBookStoreRootHandle,
    ) {
        try {
            if (rootHandle.deletePart(part)) rootHandle.syncDirectory()
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
    fun openRoot(
        root: File,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle

    fun createDirectory(directory: File)

    fun createPart(root: File): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun openExistingBook(file: File): ExistingBookInput

    fun syncDirectory(directory: File)

    fun deletePart(file: File): Boolean
}

internal interface PrivateBookStoreRootHandle : Closeable {
    fun createPart(): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun openExistingBook(file: File): ExistingBookInput

    fun syncDirectory()

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
    override fun openRoot(
        root: File,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle = if (isAndroidRuntime()) {
        AndroidPrivateBookStoreRootHandle.open(root)
    } else {
        JvmPrivateBookStoreRootHandle.open(root, operations)
    }

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
        JvmExistingBookInput.open(file)

    override fun syncDirectory(directory: File) {
        if (File.separatorChar == WINDOWS_SEPARATOR) return
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
            channel.force(true)
        }
    }

    override fun deletePart(file: File): Boolean = Files.deleteIfExists(file.toPath())

    internal fun isAndroidRuntime(): Boolean =
        System.getProperty("java.vm.name") == "Dalvik" ||
            System.getProperty("java.runtime.name") == "Android Runtime"

    private const val WINDOWS_SEPARATOR = '\\'
}

private class JvmPrivateBookStoreRootHandle private constructor(
    private val root: File,
    private val identity: JvmFileIdentity,
    private val operations: PrivateBookStoreFileOperations,
) : PrivateBookStoreRootHandle {
    override fun createPart(): File = guarded { operations.createPart(root) }

    override fun openPart(file: File): DurableBookOutput {
        verifyIdentity()
        return operations.openPart(file)
    }

    override fun publishAtomically(source: File, target: File) {
        guarded { operations.publishAtomically(source, target) }
    }

    override fun openExistingBook(file: File): ExistingBookInput {
        verifyIdentity()
        return operations.openExistingBook(file)
    }

    override fun syncDirectory() {
        guarded { operations.syncDirectory(root) }
    }

    override fun deletePart(file: File): Boolean = guarded { operations.deletePart(file) }

    override fun close() {
        verifyIdentity()
    }

    private inline fun <T> guarded(action: () -> T): T {
        verifyIdentity()
        val result = action()
        verifyIdentity()
        return result
    }

    private fun verifyIdentity() {
        val current = Files.readAttributes(
            root.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (!current.isDirectory || !identity.matches(current)) {
            throw IOException("Private book root changed during storage")
        }
    }

    companion object {
        fun open(
            root: File,
            operations: PrivateBookStoreFileOperations,
        ): PrivateBookStoreRootHandle {
            val attributes = Files.readAttributes(
                root.toPath(),
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isDirectory) {
                throw IOException("Private book path is not a directory: $root")
            }
            return JvmPrivateBookStoreRootHandle(root, JvmFileIdentity.from(attributes), operations)
        }
    }
}

internal interface JvmExistingBookFileOperations {
    fun readAttributes(path: java.nio.file.Path): BasicFileAttributes

    fun openChannel(path: java.nio.file.Path): FileChannel
}

internal object SystemJvmExistingBookFileOperations : JvmExistingBookFileOperations {
    override fun readAttributes(path: java.nio.file.Path): BasicFileAttributes =
        Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )

    override fun openChannel(path: java.nio.file.Path): FileChannel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)
}

internal class JvmExistingBookInput private constructor(
    private val channel: FileChannel,
    private val path: java.nio.file.Path,
    private val identity: JvmFileIdentity,
    private val initialLastModifiedMillis: Long,
    private val fileOperations: JvmExistingBookFileOperations,
) : ExistingBookInput {
    private val input = Channels.newInputStream(channel)

    override val size: Long = channel.size()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun verifyPathStillMatches() {
        val current = fileOperations.readAttributes(path)
        if (channel.size() != size ||
            !current.isRegularFile ||
            current.size() != size ||
            current.lastModifiedTime().toMillis() != initialLastModifiedMillis ||
            !identity.matches(current)
        ) {
            throw IOException("Existing book target changed during validation")
        }
    }

    override fun close() {
        input.close()
    }

    companion object {
        fun open(
            file: File,
            fileOperations: JvmExistingBookFileOperations = SystemJvmExistingBookFileOperations,
        ): ExistingBookInput {
            val path = file.toPath()
            val beforeOpen = fileOperations.readAttributes(path)
            if (!beforeOpen.isRegularFile) {
                throw IOException("Existing book target is not a regular file")
            }
            val channel = try {
                fileOperations.openChannel(path)
            } catch (failure: Throwable) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw IOException("Existing book target is not a regular file", failure)
                }
                throw failure
            }
            try {
                val attributes = fileOperations.readAttributes(path)
                if (!attributes.isRegularFile) {
                    throw IOException("Existing book target is not a regular file")
                }
                val identity = JvmFileIdentity.from(beforeOpen)
                if (!identity.matches(attributes) ||
                    beforeOpen.size() != channel.size() ||
                    attributes.size() != channel.size() ||
                    beforeOpen.lastModifiedTime() != attributes.lastModifiedTime()
                ) {
                    throw IOException("Existing book target changed while it was opened")
                }
                return JvmExistingBookInput(
                    channel,
                    path,
                    identity,
                    attributes.lastModifiedTime().toMillis(),
                    fileOperations,
                )
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

private data class JvmFileIdentity(
    private val fileKey: Any?,
    private val creationTimeMillis: Long,
) {
    fun matches(attributes: BasicFileAttributes): Boolean =
        if (fileKey != null && attributes.fileKey() != null) {
            attributes.fileKey() == fileKey
        } else {
            attributes.creationTime().toMillis() == creationTimeMillis
        }

    companion object {
        fun from(attributes: BasicFileAttributes): JvmFileIdentity =
            JvmFileIdentity(attributes.fileKey(), attributes.creationTime().toMillis())
    }
}

private class AndroidPrivateBookStoreRootHandle private constructor(
    private val root: File,
    private val rootDescriptor: Int,
) : PrivateBookStoreRootHandle {
    private val unopenedParts = mutableMapOf<String, ParcelFileDescriptor>()

    override fun createPart(): File {
        verifyRoot()
        while (true) {
            val name = "${UUID.randomUUID()}.part"
            val descriptor = AndroidPrivateBookStoreNative.openPart(rootDescriptor, name)
            when {
                descriptor >= 0 -> {
                    val parcel = ParcelFileDescriptor.adoptFd(descriptor)
                    unopenedParts[name] = parcel
                    try {
                        verifyRoot()
                    } catch (failure: Throwable) {
                        unopenedParts.remove(name)
                        try {
                            parcel.close()
                        } catch (closeFailure: Throwable) {
                            failure.addSuppressed(closeFailure)
                        }
                        val unlinkResult = AndroidPrivateBookStoreNative.unlink(rootDescriptor, name)
                        if (unlinkResult < 0) {
                            failure.addSuppressed(
                                nativeIOException("delete failed temporary book setup", -unlinkResult),
                            )
                        }
                        throw failure
                    }
                    return File(root, name)
                }
                descriptor == -OsConstants.EEXIST -> continue
                else -> throw nativeIOException("create temporary book", -descriptor)
            }
        }
    }

    override fun openPart(file: File): DurableBookOutput {
        verifyRoot()
        val name = entryName(file)
        val descriptor = unopenedParts.remove(name)
            ?: throw IOException("Temporary book is not owned by this root handle")
        return ParcelDurableBookOutput(descriptor)
    }

    override fun publishAtomically(source: File, target: File) {
        verifyRoot()
        val errno = AndroidPrivateBookStoreNative.link(
            rootDescriptor,
            entryName(source),
            entryName(target),
        )
        if (errno == OsConstants.EEXIST) throw FileAlreadyExistsException(target.absolutePath)
        if (errno != 0) throw nativeIOException("publish book without replacement", errno)
        verifyRoot()
    }

    override fun openExistingBook(file: File): ExistingBookInput {
        verifyRoot()
        val name = entryName(file)
        val descriptor = AndroidPrivateBookStoreNative.openExisting(rootDescriptor, name)
        if (descriptor < 0) {
            throw nativeIOException("open existing book without following links", -descriptor)
        }
        return AndroidExistingBookInput.open(
            ParcelFileDescriptor.adoptFd(descriptor),
            this,
            name,
        )
    }

    override fun syncDirectory() {
        verifyRoot()
        val errno = AndroidPrivateBookStoreNative.sync(rootDescriptor)
        if (errno != 0) throw nativeIOException("sync private book directory", errno)
        verifyRoot()
    }

    override fun deletePart(file: File): Boolean {
        verifyRoot()
        val name = entryName(file)
        unopenedParts.remove(name)?.close()
        val result = AndroidPrivateBookStoreNative.unlink(rootDescriptor, name)
        if (result < 0) throw nativeIOException("delete temporary book", -result)
        verifyRoot()
        return result == 1
    }

    fun verifyEntry(name: String, device: Long, inode: Long) {
        verifyRoot()
        val result = AndroidPrivateBookStoreNative.verifyEntry(
            rootDescriptor,
            name,
            device,
            inode,
        )
        if (result < 0) throw nativeIOException("revalidate existing book entry", -result)
        if (result == 0) throw IOException("Existing book target changed during validation")
    }

    override fun close() {
        var failure: Throwable? = null
        unopenedParts.values.forEach { descriptor ->
            try {
                descriptor.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
            }
        }
        unopenedParts.clear()
        val errno = AndroidPrivateBookStoreNative.closeDescriptor(rootDescriptor)
        if (errno != 0) {
            val closeFailure = nativeIOException("close private book directory", errno)
            if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }

    private fun verifyRoot() {
        val result = AndroidPrivateBookStoreNative.verifyRoot(rootDescriptor, root.absolutePath)
        if (result < 0) throw nativeIOException("revalidate private book directory", -result)
        if (result == 0) throw IOException("Private book root changed during storage")
    }

    private fun entryName(file: File): String {
        val absolute = file.absoluteFile
        if (absolute.parentFile != root || absolute.name.contains('/') ||
            absolute.name.contains('\\') || absolute.name.indexOf('\u0000') >= 0
        ) {
            throw IOException("Book entry is outside the private book directory")
        }
        return absolute.name
    }

    companion object {
        fun open(root: File): PrivateBookStoreRootHandle {
            val descriptor = AndroidPrivateBookStoreNative.openRoot(root.absolutePath)
            if (descriptor < 0) {
                throw nativeIOException("open private book directory without following links", -descriptor)
            }
            val handle = AndroidPrivateBookStoreRootHandle(root.absoluteFile, descriptor)
            try {
                handle.verifyRoot()
                return handle
            } catch (failure: Throwable) {
                val errno = AndroidPrivateBookStoreNative.closeDescriptor(descriptor)
                if (errno != 0) {
                    failure.addSuppressed(nativeIOException("close failed private book directory setup", errno))
                }
                throw failure
            }
        }
    }
}

private class AndroidExistingBookInput private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val rootHandle: AndroidPrivateBookStoreRootHandle,
    private val name: String,
    private val initialStat: android.system.StructStat,
    override val size: Long,
) : ExistingBookInput {
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = try {
            Os.read(descriptor.fileDescriptor, buffer, offset, length)
        } catch (failure: ErrnoException) {
            throw failure.asIOException("read existing book")
        }
        return if (count == 0) -1 else count
    }

    override fun verifyPathStillMatches() {
        val currentStat = try {
            Os.fstat(descriptor.fileDescriptor)
        } catch (failure: ErrnoException) {
            throw failure.asIOException("revalidate opened existing book")
        }
        if (!sameStableFileState(initialStat, currentStat)) {
            throw IOException("Existing book target changed during validation")
        }
        rootHandle.verifyEntry(name, initialStat.st_dev, initialStat.st_ino)
    }

    override fun close() {
        descriptor.close()
    }

    companion object {
        fun open(
            descriptor: ParcelFileDescriptor,
            rootHandle: AndroidPrivateBookStoreRootHandle,
            name: String,
        ): ExistingBookInput {
            try {
                val stat = try {
                    Os.fstat(descriptor.fileDescriptor)
                } catch (failure: ErrnoException) {
                    throw failure.asIOException("inspect opened existing book")
                }
                if (!OsConstants.S_ISREG(stat.st_mode)) {
                    throw IOException("Existing book target is not a regular file")
                }
                return AndroidExistingBookInput(
                    descriptor,
                    rootHandle,
                    name,
                    stat,
                    stat.st_size,
                )
            } catch (failure: Throwable) {
                try {
                    descriptor.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        private fun sameStableFileState(
            before: android.system.StructStat,
            after: android.system.StructStat,
        ): Boolean =
            OsConstants.S_ISREG(after.st_mode) &&
                before.st_dev == after.st_dev &&
                before.st_ino == after.st_ino &&
                before.st_size == after.st_size &&
                before.st_mtim.tv_sec == after.st_mtim.tv_sec &&
                before.st_mtim.tv_nsec == after.st_mtim.tv_nsec &&
                before.st_ctim.tv_sec == after.st_ctim.tv_sec &&
                before.st_ctim.tv_nsec == after.st_ctim.tv_nsec
    }
}

private class ParcelDurableBookOutput(
    private val descriptor: ParcelFileDescriptor,
) : DurableBookOutput {
    private val output = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        output.write(buffer, offset, length)
    }

    override fun flush() {
        output.flush()
    }

    override fun sync() {
        descriptor.fileDescriptor.sync()
    }

    override fun close() {
        output.close()
    }
}

@Keep
private object AndroidPrivateBookStoreNative {
    init {
        System.loadLibrary("pagenest_storage")
    }

    external fun openRoot(path: String): Int

    external fun openPart(rootDescriptor: Int, name: String): Int

    external fun openExisting(rootDescriptor: Int, name: String): Int

    external fun link(rootDescriptor: Int, sourceName: String, targetName: String): Int

    external fun unlink(rootDescriptor: Int, name: String): Int

    external fun sync(rootDescriptor: Int): Int

    external fun verifyRoot(rootDescriptor: Int, path: String): Int

    external fun verifyEntry(rootDescriptor: Int, name: String, device: Long, inode: Long): Int

    external fun closeDescriptor(descriptor: Int): Int
}

private fun nativeIOException(operation: String, errno: Int): IOException =
    IOException("$operation failed with errno $errno")

private fun ErrnoException.asIOException(operation: String): IOException =
    IOException("$operation failed: $message", this)

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

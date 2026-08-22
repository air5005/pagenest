package com.air5005.pagenest.library.importing

import android.content.Context
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
import java.nio.ByteBuffer
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

class PrivateBookStore private constructor(
    private val rootLocation: PrivateBookStoreRootLocation,
    private val fileOperations: PrivateBookStoreFileOperations,
) {
    private val root: File = rootLocation.root

    internal constructor(
        root: File,
        fileOperations: PrivateBookStoreFileOperations,
    ) : this(PrivateBookStoreRootLocation.Legacy(root.absoluteFile), fileOperations)

    @Throws(IOException::class)
    constructor(root: File) : this(root, SystemPrivateBookStoreFileOperations) {
        if (SystemPrivateBookStoreFileOperations.isAndroidRuntime()) {
            throw IOException(
                "Android private book storage requires a trusted app-files parent and one root name",
            )
        }
    }

    fun store(input: InputStream, originalName: String): StoredBook {
        val format = requireNotNull(SupportedBookFormat.fromFileName(originalName)) {
            "Unsupported book file name: $originalName"
        }
        val rootHandle = when (val location = rootLocation) {
            is PrivateBookStoreRootLocation.Legacy -> {
                ensureRootDirectory()
                fileOperations.openRoot(location.root, fileOperations)
            }
            is PrivateBookStoreRootLocation.Trusted -> fileOperations.openTrustedRoot(
                location.parent,
                location.rootName,
                fileOperations,
            )
        }
        var storedBook: StoredBook? = null
        var primaryFailure: Throwable? = null
        try {
            storedBook = storeInPinnedRoot(input, format, rootHandle)
        } catch (failure: Throwable) {
            primaryFailure = failure
        }
        try {
            rootHandle.close()
        } catch (closeFailure: Throwable) {
            val earlierFailure = primaryFailure
            if (earlierFailure != null) {
                earlierFailure.addSuppressed(closeFailure)
            } else {
                val publishedBook = checkNotNull(storedBook)
                throw PublishedBookCleanupException(publishedBook, closeFailure)
            }
        }
        primaryFailure?.let { throw it }
        return checkNotNull(storedBook)
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
            if (!rootHandle.cleanupPartDurably(part)) {
                throw IOException("Temporary book part disappeared before cleanup")
            }
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
            val verifiedState = input.verifiedStateAfterHash()
            rootHandle.verifyExistingBook(file, verifiedState)
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
            rootHandle.cleanupPartDurably(part)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte ->
        HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f].toString() +
            HEX_DIGITS[byte.toInt() and 0x0f]
    }

    companion object {
        private const val COPY_BUFFER_SIZE = 8 * 1024
        private const val HEX_DIGITS = "0123456789abcdef"

        @JvmStatic
        fun inAppFiles(
            context: Context,
            rootName: String = "books",
        ): PrivateBookStore = inTrustedDirectory(
            context.filesDir,
            rootName,
            SystemPrivateBookStoreFileOperations,
        )

        internal fun inTrustedDirectory(
            trustedParent: File,
            rootName: String,
            fileOperations: PrivateBookStoreFileOperations,
        ): PrivateBookStore {
            requireValidBasename(rootName, "Private book root name")
            return PrivateBookStore(
                PrivateBookStoreRootLocation.Trusted(
                    trustedParent.absoluteFile,
                    rootName,
                ),
                fileOperations,
            )
        }
    }
}

private sealed interface PrivateBookStoreRootLocation {
    val root: File

    data class Legacy(override val root: File) : PrivateBookStoreRootLocation

    data class Trusted(
        val parent: File,
        val rootName: String,
    ) : PrivateBookStoreRootLocation {
        override val root: File = File(parent, rootName)
    }
}

internal interface PrivateBookStoreFileOperations {
    fun openRoot(
        root: File,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle

    fun openTrustedRoot(
        trustedParent: File,
        rootName: String,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle

    fun createDirectory(directory: File)

    fun createPart(root: File): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun openExistingBook(file: File): ExistingBookInput

    fun syncDirectory(directory: File)

    fun deletePart(file: File): Boolean

    fun readJvmAttributes(file: File): BasicFileAttributes
}

internal interface PrivateBookStoreRootHandle : Closeable {
    fun createPart(): File

    fun openPart(file: File): DurableBookOutput

    fun publishAtomically(source: File, target: File)

    fun openExistingBook(file: File): ExistingBookInput

    fun verifyExistingBook(file: File, state: ExistingBookFileState)

    fun syncDirectory()

    fun deletePart(file: File): Boolean

    fun cleanupPartDurably(file: File): Boolean
}

internal interface DurableBookOutput : Closeable {
    fun write(buffer: ByteArray, offset: Int, length: Int)

    fun flush()

    fun sync()
}

internal interface ExistingBookInput : Closeable {
    val size: Long

    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun verifiedStateAfterHash(): ExistingBookFileState
}

internal data class ExistingBookFileState(
    val device: Long?,
    val inode: Long?,
    val size: Long,
    val modifiedSeconds: Long,
    val modifiedNanoseconds: Long,
    val changedSeconds: Long?,
    val changedNanoseconds: Long?,
    val fileKey: Any?,
) {
    fun matches(attributes: BasicFileAttributes): Boolean {
        val modified = attributes.lastModifiedTime().toInstant()
        return attributes.isRegularFile &&
            attributes.size() == size &&
            modified.epochSecond == modifiedSeconds &&
            modified.nano.toLong() == modifiedNanoseconds &&
            fileKey != null && attributes.fileKey() == fileKey
    }

    companion object {
        fun from(attributes: BasicFileAttributes): ExistingBookFileState {
            val modified = attributes.lastModifiedTime().toInstant()
            return ExistingBookFileState(
                device = null,
                inode = null,
                size = attributes.size(),
                modifiedSeconds = modified.epochSecond,
                modifiedNanoseconds = modified.nano.toLong(),
                changedSeconds = null,
                changedNanoseconds = null,
                fileKey = attributes.fileKey(),
            )
        }

        fun from(stat: android.system.StructStat): ExistingBookFileState =
            ExistingBookFileState(
                device = stat.st_dev,
                inode = stat.st_ino,
                size = stat.st_size,
                modifiedSeconds = stat.st_mtim.tv_sec,
                modifiedNanoseconds = stat.st_mtim.tv_nsec,
                changedSeconds = stat.st_ctim.tv_sec,
                changedNanoseconds = stat.st_ctim.tv_nsec,
                fileKey = null,
            )
    }
}

internal object SystemPrivateBookStoreFileOperations : PrivateBookStoreFileOperations {
    override fun openRoot(
        root: File,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle {
        if (isAndroidRuntime()) {
            throw IOException("Android private book storage requires a trusted parent")
        }
        return JvmPrivateBookStoreRootHandle.open(root, operations)
    }

    override fun openTrustedRoot(
        trustedParent: File,
        rootName: String,
        operations: PrivateBookStoreFileOperations,
    ): PrivateBookStoreRootHandle {
        requireValidBasename(rootName, "Private book root name")
        if (isAndroidRuntime()) {
            return AndroidPrivateBookStoreRootHandle.openTrusted(trustedParent, rootName)
        }
        val root = File(trustedParent, rootName)
        try {
            operations.createDirectory(root)
        } catch (_: FileAlreadyExistsException) {
            if (!Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                throw IOException("Private book path is not a directory: $root")
            }
        }
        operations.syncDirectory(trustedParent)
        return JvmPrivateBookStoreRootHandle.open(root, operations)
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

    override fun readJvmAttributes(file: File): BasicFileAttributes =
        SystemJvmExistingBookFileOperations.readAttributes(file.toPath())

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

    override fun verifyExistingBook(file: File, state: ExistingBookFileState) {
        verifyIdentity()
        val attributes = operations.readJvmAttributes(file)
        if (!state.matches(attributes)) {
            throw IOException("Existing book target changed during validation")
        }
        verifyIdentity()
    }

    override fun syncDirectory() {
        guarded { operations.syncDirectory(root) }
    }

    override fun deletePart(file: File): Boolean = guarded { operations.deletePart(file) }

    override fun cleanupPartDurably(file: File): Boolean = guarded {
        val deleted = operations.deletePart(file)
        if (deleted) operations.syncDirectory(root)
        deleted
    }

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
        val current = operations.readJvmAttributes(root)
        if (!current.isDirectory || !identity.matches(current)) {
            throw IOException("Private book root changed during storage")
        }
    }

    companion object {
        fun open(
            root: File,
            operations: PrivateBookStoreFileOperations,
        ): PrivateBookStoreRootHandle {
            val attributes = operations.readJvmAttributes(root)
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

    fun openVerificationChannel(path: java.nio.file.Path): FileChannel
}

internal object SystemJvmExistingBookFileOperations : JvmExistingBookFileOperations {
    override fun readAttributes(path: java.nio.file.Path): BasicFileAttributes {
        val attributes = Files.readAttributes(
            path,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (attributes.fileKey() != null) return attributes
        val created = attributes.creationTime().toInstant()
        val contentDigest = if (attributes.isRegularFile) digestPath(path) else null
        val strongIdentity = ContentBoundJvmFileIdentity(
            attributes.isDirectory,
            created.epochSecond,
            created.nano,
            if (attributes.isDirectory) 0L else attributes.size(),
            contentDigest,
        )
        return object : BasicFileAttributes by attributes {
            override fun fileKey(): Any = strongIdentity
        }
    }

    override fun openChannel(path: java.nio.file.Path): FileChannel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    override fun openVerificationChannel(path: java.nio.file.Path): FileChannel =
        FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)

    private fun digestPath(path: java.nio.file.Path): String =
        openVerificationChannel(path).use { channel ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(8 * 1024)
            while (channel.read(buffer) >= 0) {
                if (buffer.position() == 0) continue
                buffer.flip()
                digest.update(buffer)
                buffer.clear()
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
}

private data class ContentBoundJvmFileIdentity(
    val directory: Boolean,
    val createdSeconds: Long,
    val createdNanoseconds: Int,
    val size: Long,
    val contentDigest: String?,
)

internal class JvmExistingBookInput private constructor(
    private val channel: FileChannel,
    private val path: java.nio.file.Path,
    private val identity: JvmFileIdentity,
    private val initialLastModifiedSeconds: Long,
    private val initialLastModifiedNanoseconds: Int,
    private val fileOperations: JvmExistingBookFileOperations,
) : ExistingBookInput {
    private val input = Channels.newInputStream(channel)

    override val size: Long = channel.size()

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        input.read(buffer, offset, length)

    override fun verifiedStateAfterHash(): ExistingBookFileState {
        val current = fileOperations.readAttributes(path)
        val currentModified = current.lastModifiedTime().toInstant()
        if (channel.size() != size ||
            !current.isRegularFile ||
            current.size() != size ||
            currentModified.epochSecond != initialLastModifiedSeconds ||
            currentModified.nano != initialLastModifiedNanoseconds ||
            !identity.matches(current)
        ) {
            throw IOException("Existing book target changed during validation")
        }
        return ExistingBookFileState.from(current)
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
                val openedDigest = digest(channel)
                val pathDigest = fileOperations.openVerificationChannel(path).use(::digest)
                val afterDigest = fileOperations.readAttributes(path)
                if (!identity.matches(afterDigest) ||
                    !afterDigest.isRegularFile ||
                    afterDigest.size() != channel.size() ||
                    openedDigest != pathDigest
                ) {
                    throw IOException("Existing book channel does not match its directory entry")
                }
                channel.position(0)
                val modified = attributes.lastModifiedTime().toInstant()
                return JvmExistingBookInput(
                    channel,
                    path,
                    identity,
                    modified.epochSecond,
                    modified.nano,
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

        private fun digest(channel: FileChannel): String {
            val originalPosition = channel.position()
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteBuffer.allocate(8 * 1024)
            channel.position(0)
            try {
                while (channel.read(buffer) >= 0) {
                    if (buffer.position() == 0) continue
                    buffer.flip()
                    digest.update(buffer)
                    buffer.clear()
                }
            } finally {
                channel.position(originalPosition)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private data class JvmFileIdentity(
    private val fileKey: Any,
) {
    fun matches(attributes: BasicFileAttributes): Boolean =
        attributes.fileKey() == fileKey

    companion object {
        fun from(attributes: BasicFileAttributes): JvmFileIdentity =
            JvmFileIdentity(
                attributes.fileKey()
                    ?: throw IOException("Filesystem does not expose a stable file identity"),
            )
    }
}

private class AndroidPrivateBookStoreRootHandle private constructor(
    private val root: File,
    private val parentDescriptor: Int,
    private val rootName: String,
    private val rootDescriptor: Int,
) : PrivateBookStoreRootHandle {
    private val ownedParts = mutableMapOf<String, ParcelFileDescriptor>()

    override fun createPart(): File {
        verifyRoot()
        while (true) {
            val name = "${UUID.randomUUID()}.part"
            val descriptor = AndroidPrivateBookStoreNative.openPart(rootDescriptor, name)
            when {
                descriptor >= 0 -> {
                    val parcel = ParcelFileDescriptor.adoptFd(descriptor)
                    ownedParts[name] = parcel
                    try {
                        verifyRoot()
                    } catch (failure: Throwable) {
                        ownedParts.remove(name)
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
        val descriptor = ownedParts[name]
            ?: throw IOException("Temporary book is not owned by this root handle")
        return ParcelDurableBookOutput(ParcelFileDescriptor.dup(descriptor.fileDescriptor))
    }

    override fun publishAtomically(source: File, target: File) {
        verifyRoot()
        val sourceName = entryName(source)
        val sourceDescriptor = ownedParts[sourceName]
            ?: throw IOException("Temporary book is not owned by this root handle")
        val errno = AndroidPrivateBookStoreNative.linkOpenedFile(
            sourceDescriptor.fd,
            rootDescriptor,
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
        )
    }

    override fun verifyExistingBook(file: File, state: ExistingBookFileState) {
        verifyRoot()
        val device = state.device
            ?: throw IOException("Existing Android book state has no device identity")
        val inode = state.inode
            ?: throw IOException("Existing Android book state has no inode identity")
        val changedSeconds = state.changedSeconds
            ?: throw IOException("Existing Android book state has no change time")
        val changedNanoseconds = state.changedNanoseconds
            ?: throw IOException("Existing Android book state has no change time")
        val result = AndroidPrivateBookStoreNative.verifyEntry(
            rootDescriptor,
            entryName(file),
            device,
            inode,
            state.size,
            state.modifiedSeconds,
            state.modifiedNanoseconds,
            changedSeconds,
            changedNanoseconds,
        )
        if (result < 0) throw nativeIOException("revalidate existing book entry", -result)
        if (result == 0) throw IOException("Existing book target changed during validation")
        verifyRoot()
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
        ownedParts.remove(name)?.close()
        val result = AndroidPrivateBookStoreNative.unlink(rootDescriptor, name)
        if (result < 0) throw nativeIOException("delete temporary book", -result)
        verifyRoot()
        return result == 1
    }

    override fun cleanupPartDurably(file: File): Boolean {
        val name = entryName(file)
        val descriptor = ownedParts.remove(name)
        var failure: Throwable? = null
        var result = 0
        try {
            result = AndroidPrivateBookStoreNative.unlink(rootDescriptor, name)
            if (result < 0) throw nativeIOException("delete temporary book", -result)
            if (result == 1) {
                val syncErrno = AndroidPrivateBookStoreNative.sync(rootDescriptor)
                if (syncErrno != 0) {
                    throw nativeIOException("sync private book directory after cleanup", syncErrno)
                }
            }
            verifyRoot()
        } catch (cleanupFailure: Throwable) {
            failure = cleanupFailure
        }
        try {
            descriptor?.close()
        } catch (closeFailure: Throwable) {
            if (failure == null) failure = closeFailure else failure.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
        return result == 1
    }

    override fun close() {
        var failure: Throwable? = null
        ownedParts.values.forEach { descriptor ->
            try {
                descriptor.close()
            } catch (closeFailure: Throwable) {
                if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
            }
        }
        ownedParts.clear()
        val errno = AndroidPrivateBookStoreNative.closeDescriptor(rootDescriptor)
        if (errno != 0) {
            val closeFailure = nativeIOException("close private book directory", errno)
            if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
        }
        val parentErrno = AndroidPrivateBookStoreNative.closeDescriptor(parentDescriptor)
        if (parentErrno != 0) {
            val closeFailure = nativeIOException("close trusted parent directory", parentErrno)
            if (failure == null) failure = closeFailure else failure!!.addSuppressed(closeFailure)
        }
        failure?.let { throw it }
    }

    private fun verifyRoot() {
        val result = AndroidPrivateBookStoreNative.verifyRoot(
            parentDescriptor,
            rootDescriptor,
            rootName,
        )
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
        fun openTrusted(
            trustedParent: File,
            rootName: String,
        ): PrivateBookStoreRootHandle {
            val parentDescriptor = AndroidPrivateBookStoreNative.openTrustedParent(
                trustedParent.absolutePath,
            )
            if (parentDescriptor < 0) {
                throw nativeIOException(
                    "open trusted parent directory without following links",
                    -parentDescriptor,
                )
            }
            val rootDescriptor = AndroidPrivateBookStoreNative.openOrCreateRoot(
                parentDescriptor,
                rootName,
            )
            if (rootDescriptor < 0) {
                val failure = nativeIOException(
                    "open private book directory relative to trusted parent",
                    -rootDescriptor,
                )
                val closeErrno = AndroidPrivateBookStoreNative.closeDescriptor(parentDescriptor)
                if (closeErrno != 0) {
                    failure.addSuppressed(
                        nativeIOException("close failed trusted parent setup", closeErrno),
                    )
                }
                throw failure
            }
            val handle = AndroidPrivateBookStoreRootHandle(
                File(trustedParent, rootName).absoluteFile,
                parentDescriptor,
                rootName,
                rootDescriptor,
            )
            try {
                handle.verifyRoot()
                return handle
            } catch (failure: Throwable) {
                val rootCloseErrno = AndroidPrivateBookStoreNative.closeDescriptor(rootDescriptor)
                if (rootCloseErrno != 0) {
                    failure.addSuppressed(
                        nativeIOException("close failed private book directory setup", rootCloseErrno),
                    )
                }
                val parentCloseErrno = AndroidPrivateBookStoreNative.closeDescriptor(parentDescriptor)
                if (parentCloseErrno != 0) {
                    failure.addSuppressed(
                        nativeIOException("close failed trusted parent setup", parentCloseErrno),
                    )
                }
                throw failure
            }
        }
    }
}

private class AndroidExistingBookInput private constructor(
    private val descriptor: ParcelFileDescriptor,
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

    override fun verifiedStateAfterHash(): ExistingBookFileState {
        val currentStat = try {
            Os.fstat(descriptor.fileDescriptor)
        } catch (failure: ErrnoException) {
            throw failure.asIOException("revalidate opened existing book")
        }
        if (!sameStableFileState(initialStat, currentStat)) {
            throw IOException("Existing book target changed during validation")
        }
        return ExistingBookFileState.from(currentStat)
    }

    override fun close() {
        descriptor.close()
    }

    companion object {
        fun open(
            descriptor: ParcelFileDescriptor,
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

    external fun openTrustedParent(path: String): Int

    external fun openOrCreateRoot(parentDescriptor: Int, rootName: String): Int

    external fun openPart(rootDescriptor: Int, name: String): Int

    external fun openExisting(rootDescriptor: Int, name: String): Int

    external fun linkOpenedFile(
        partDescriptor: Int,
        rootDescriptor: Int,
        targetName: String,
    ): Int

    external fun unlink(rootDescriptor: Int, name: String): Int

    external fun sync(rootDescriptor: Int): Int

    external fun verifyRoot(
        parentDescriptor: Int,
        rootDescriptor: Int,
        rootName: String,
    ): Int

    external fun verifyEntry(
        rootDescriptor: Int,
        name: String,
        device: Long,
        inode: Long,
        size: Long,
        modifiedSeconds: Long,
        modifiedNanoseconds: Long,
        changedSeconds: Long,
        changedNanoseconds: Long,
    ): Int

    external fun closeDescriptor(descriptor: Int): Int
}

private fun nativeIOException(operation: String, errno: Int): IOException =
    IOException("$operation failed with errno $errno")

private fun requireValidBasename(name: String, label: String) {
    require(
        name.isNotEmpty() && name != "." && name != ".." &&
            !name.contains('/') && !name.contains('\\') && name.indexOf('\u0000') < 0,
    ) { "$label must be one non-empty basename" }
}

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

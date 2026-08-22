package com.air5005.pagenest.library.importing

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

data class StoredBook(
    val file: File,
    val sha256: String,
    val wasExisting: Boolean,
)

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
        try {
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
                true
            }
            return StoredBook(finalFile, sha256, wasExisting)
        } finally {
            Files.deleteIfExists(part.toPath())
        }
    }

    private fun ensureRootDirectory() {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("Could not create private book directory: $root")
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

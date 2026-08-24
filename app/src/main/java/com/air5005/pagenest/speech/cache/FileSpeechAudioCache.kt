package com.air5005.pagenest.speech.cache

import com.air5005.pagenest.speech.engine.SpeechRequest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

fun interface CacheFilePublisher {
    @Throws(IOException::class)
    fun publish(temporaryFile: File, destinationFile: File)
}

object AtomicCacheFilePublisher : CacheFilePublisher {
    override fun publish(temporaryFile: File, destinationFile: File) {
        Files.move(
            temporaryFile.toPath(),
            destinationFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }
}

class FileSpeechAudioCache(
    private val rootDirectory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val expiryMillis: Long = DEFAULT_EXPIRY_MILLIS,
    private val publisher: CacheFilePublisher = AtomicCacheFilePublisher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val stagingDirectory: File = File(
        rootDirectory.parentFile ?: rootDirectory,
        "${rootDirectory.name}-staging",
    ),
) : SpeechAudioCache {
    private val mutex = Mutex()
    private var scopeGeneration = 0L
    private var activeToken: SpeechCacheScopeToken? = null

    init {
        require(maxBytes >= 0) { "maxBytes must not be negative" }
        require(expiryMillis >= 0) { "expiryMillis must not be negative" }
    }

    override suspend fun retainScope(request: SpeechRequest): SpeechCacheScopeToken = withContext(ioDispatcher) {
        mutex.withLock {
            cleanupOrphanTemps(rootDirectory)
            cleanupOrphanTemps(stagingDirectory)
            val scope = retainScopeOnDisk(request)
            enforceCapacity(scope, maxBytes)
            SpeechCacheScopeToken(
                generation = ++scopeGeneration,
                bookId = request.segment.position.bookId,
                chapterIndex = request.segment.position.chapterIndex,
            ).also { activeToken = it }
        }
    }

    override suspend fun get(
        token: SpeechCacheScopeToken,
        request: SpeechRequest,
        nowMillis: Long,
    ): ByteArray? = withContext(ioDispatcher) {
        mutex.withLock {
            if (!isActive(token, request)) return@withLock null
            val scope = scopeDirectory(request)
            removeExpiredAndCorrupt(scope, nowMillis)
            val file = entryFile(scope, request)
            val entry = readEntry(file) ?: return@withLock null
            if (isExpired(entry.createdAtMillis, nowMillis)) {
                file.delete()
                removeEmptyParents(file.parentFile)
                return@withLock null
            }
            file.setLastModified(nowMillis)
            entry.audio
        }
    }

    override suspend fun put(
        token: SpeechCacheScopeToken,
        request: SpeechRequest,
        audio: ByteArray,
        nowMillis: Long,
    ) = withContext(ioDispatcher) {
        mutex.withLock {
            if (!isActive(token, request)) return@withLock
            val scope = scopeDirectory(request)
            removeExpiredAndCorrupt(scope, nowMillis)
            val entryBytes = HEADER_BYTES + audio.size.toLong()
            if (entryBytes > maxBytes) {
                enforceCapacity(scope, maxBytes)
                return@withLock
            }

            val destination = entryFile(scope, request)
            enforceCapacity(scope, maxBytes - entryBytes, destination)
            scope.mkdirs()
            stagingDirectory.mkdirs()
            val temporary = File(stagingDirectory, ".${UUID.randomUUID()}.tmp")
            try {
                writeEntry(temporary, nowMillis, audio)
                if (!isActive(token, request)) return@withLock
                publisher.publish(temporary, destination)
                destination.setLastModified(nowMillis)
            } finally {
                temporary.delete()
                removeEmptyStagingDirectory()
            }
            enforceCapacity(scope, maxBytes)
        }
    }

    override suspend fun remove(token: SpeechCacheScopeToken, request: SpeechRequest) = withContext(ioDispatcher) {
        mutex.withLock {
            if (!isActive(token, request)) return@withLock
            val scope = scopeDirectory(request)
            val file = entryFile(scope, request)
            file.delete()
            removeEmptyParents(file.parentFile)
        }
    }

    override suspend fun clear() = withContext(ioDispatcher) {
        mutex.withLock {
            activeToken = null
            scopeGeneration++
            deleteRecursively(rootDirectory)
            deleteRecursively(stagingDirectory)
        }
    }

    private fun retainScopeOnDisk(request: SpeechRequest): File {
        val scope = scopeDirectory(request)
        val bookDirectory = scope.parentFile!!
        if (rootDirectory.exists()) {
            rootDirectory.listFiles().orEmpty().forEach { child ->
                if (child.name != bookDirectory.name) deleteRecursively(child)
            }
            bookDirectory.listFiles().orEmpty().forEach { child ->
                if (child.name != scope.name) deleteRecursively(child)
            }
        }
        return scope
    }

    private fun scopeDirectory(request: SpeechRequest): File = File(
        File(rootDirectory, request.segment.position.bookId.toString()),
        "chapter-${request.segment.position.chapterIndex}",
    )

    private fun isActive(token: SpeechCacheScopeToken, request: SpeechRequest): Boolean =
        token == activeToken &&
            token.bookId == request.segment.position.bookId &&
            token.chapterIndex == request.segment.position.chapterIndex

    private fun entryFile(scope: File, request: SpeechRequest): File =
        File(scope, "${cacheDigest(request)}.cache")

    private fun cacheDigest(request: SpeechRequest): String {
        val source = buildString {
            append(request.segment.text)
            append('\u0000')
            append(request.voiceId.orEmpty())
            append('\u0000')
            append(request.rate)
            append('\u0000')
            append(request.pitch)
            append('\u0000')
            append(request.localeTag)
        }.toByteArray(Charsets.UTF_8)
        return try {
            MessageDigest.getInstance("SHA-256").digest(source).joinToString("") { "%02x".format(it) }
        } finally {
            source.fill(0)
        }
    }

    private fun writeEntry(file: File, createdAtMillis: Long, audio: ByteArray) {
        FileOutputStream(file).use { stream ->
            DataOutputStream(BufferedOutputStream(stream)).use { output ->
                output.writeInt(MAGIC)
                output.writeInt(VERSION)
                output.writeLong(createdAtMillis)
                output.writeInt(audio.size)
                output.write(sha256(audio))
                output.write(audio)
                output.flush()
                stream.fd.sync()
            }
        }
    }

    private fun readEntry(file: File): CacheEntry? {
        if (!file.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
                if (input.readInt() != MAGIC || input.readInt() != VERSION) return corrupt(file)
                val createdAtMillis = input.readLong()
                val length = input.readInt()
                val expectedFileBytes = HEADER_BYTES + length.toLong()
                if (
                    createdAtMillis < 0 ||
                    length < 0 ||
                    expectedFileBytes != file.length() ||
                    expectedFileBytes > maxBytes
                ) return corrupt(file)
                val expectedHash = ByteArray(HASH_BYTES)
                input.readFully(expectedHash)
                val audio = ByteArray(length)
                input.readFully(audio)
                if (input.read() != -1 || !MessageDigest.isEqual(expectedHash, sha256(audio))) {
                    audio.fill(0)
                    return corrupt(file)
                }
                CacheEntry(createdAtMillis, audio)
            }
        } catch (_: EOFException) {
            corrupt(file)
        } catch (_: IOException) {
            corrupt(file)
        }
    }

    private fun removeExpiredAndCorrupt(scope: File, nowMillis: Long) {
        scope.listFiles().orEmpty().forEach { file ->
            if (file.extension != CACHE_EXTENSION) {
                if (file.name.endsWith(".tmp")) file.delete()
                return@forEach
            }
            val entry = readEntry(file)
            if (entry == null || isExpired(entry.createdAtMillis, nowMillis)) file.delete()
            entry?.audio?.fill(0)
        }
        removeEmptyParents(scope)
    }

    private fun enforceCapacity(scope: File, allowedBytes: Long, except: File? = null) {
        val entries = scope.listFiles().orEmpty().mapNotNull { file ->
            if (file == except || file.extension != CACHE_EXTENSION) return@mapNotNull null
            CacheFile(file, file.length())
        }.sortedWith(compareBy<CacheFile> { it.file.lastModified() }.thenBy { it.file.name })
        var total = entries.sumOf(CacheFile::entryBytes)
        entries.forEach { entry ->
            if (total <= allowedBytes.coerceAtLeast(0)) return
            if (entry.file.delete()) total -= entry.entryBytes
        }
    }

    private fun isExpired(createdAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis >= createdAtMillis && nowMillis - createdAtMillis >= expiryMillis

    private fun corrupt(file: File): CacheEntry? {
        file.delete()
        return null
    }

    private fun removeEmptyParents(start: File?) {
        var current = start
        while (current != null && current != rootDirectory && current.listFiles().orEmpty().isEmpty()) {
            current.delete()
            current = current.parentFile
        }
    }

    private fun cleanupOrphanTemps(directory: File) {
        if (!directory.exists()) return
        directory.walkBottomUp().forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp")) file.delete()
        }
        removeEmptyStagingDirectory()
    }

    private fun removeEmptyStagingDirectory() {
        if (stagingDirectory.isDirectory && stagingDirectory.listFiles().orEmpty().isEmpty()) {
            stagingDirectory.delete()
        }
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        file.walkBottomUp().forEach(File::delete)
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private data class CacheEntry(val createdAtMillis: Long, val audio: ByteArray)
    private data class CacheFile(val file: File, val entryBytes: Long)

    companion object {
        const val DEFAULT_MAX_BYTES = 128L * 1024 * 1024
        const val DEFAULT_EXPIRY_MILLIS = 24L * 60 * 60 * 1000
        private const val MAGIC = 0x504E5343
        private const val VERSION = 1
        private const val HASH_BYTES = 32
        private const val HEADER_BYTES = 4L + 4L + 8L + 4L + HASH_BYTES
        private const val CACHE_EXTENSION = "cache"
    }
}

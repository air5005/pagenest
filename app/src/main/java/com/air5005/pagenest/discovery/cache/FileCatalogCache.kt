package com.air5005.pagenest.discovery.cache

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class FileCatalogCache(
    private val directory: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val json: Json = DEFAULT_JSON,
) : CatalogCache {
    private val mutex = Mutex()

    init {
        require(maxTotalBytes >= 0) { "Cache size limit must not be negative" }
    }

    override suspend fun get(key: String): CachedCatalogPage? = mutex.withLock {
        read(fileFor(key), deleteIfInvalid = true)
    }

    override suspend fun put(key: String, value: CachedCatalogPage) = mutex.withLock {
        if (!directory.exists() && !directory.mkdirs()) return@withLock
        val target = fileFor(key)
        val temporary = File(directory, "${target.nameWithoutExtension}.tmp")
        val bytes = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            atomicReplace(temporary, target)
            enforceTotalCap()
        } finally {
            temporary.delete()
        }
    }

    override suspend fun remove(key: String) = mutex.withLock {
        fileFor(key).delete()
        File(directory, "${fileFor(key).nameWithoutExtension}.tmp").delete()
        Unit
    }

    private fun read(file: File, deleteIfInvalid: Boolean): CachedCatalogPage? {
        if (!file.isFile || file.length() > maxTotalBytes.coerceAtLeast(MAX_SINGLE_READ_BYTES)) return null
        return try {
            json.decodeFromString<CachedCatalogPage>(file.readText(Charsets.UTF_8))
        } catch (_: SerializationException) {
            if (deleteIfInvalid) file.delete()
            null
        } catch (_: IllegalArgumentException) {
            if (deleteIfInvalid) file.delete()
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun enforceTotalCap() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .toMutableList()
        var total = files.sumOf(File::length)
        if (total <= maxTotalBytes) return
        files.sortedWith(
            compareBy<File> { file -> read(file, deleteIfInvalid = true)?.cachedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy(File::getName),
        ).forEach { file ->
            if (total <= maxTotalBytes) return
            val length = file.length()
            if (file.delete()) total -= length
        }
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun fileFor(key: String): File = File(directory, fileNameForKey(key))

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES: Long = 4L * 1024L * 1024L
        private const val MAX_SINGLE_READ_BYTES: Long = DEFAULT_MAX_TOTAL_BYTES

        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun fileNameForKey(key: String): String = "${CatalogCacheKey.sha256(key)}.json"
    }
}

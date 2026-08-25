package com.air5005.pagenest.discovery.openlibrary

import com.air5005.pagenest.discovery.cache.CatalogCacheKey
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedOpenLibraryMetadata(
    val cachedAtEpochMillis: Long,
    val metadata: OpenLibraryMetadata?,
)

interface OpenLibraryMetadataCache {
    suspend fun get(key: String): CachedOpenLibraryMetadata?
    suspend fun put(key: String, value: CachedOpenLibraryMetadata)
    suspend fun remove(key: String)
}

class FileOpenLibraryMetadataCache(
    private val directory: File,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    private val json: Json = DEFAULT_JSON,
) : OpenLibraryMetadataCache {
    private val mutex = Mutex()

    override suspend fun get(key: String): CachedOpenLibraryMetadata? = mutex.withLock {
        read(fileFor(key), deleteInvalid = true)
    }

    override suspend fun put(key: String, value: CachedOpenLibraryMetadata) = mutex.withLock {
        if (!directory.exists() && !directory.mkdirs()) return@withLock
        val target = fileFor(key)
        val temporary = File(directory, "${target.nameWithoutExtension}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.encodeToString(value).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            atomicReplace(temporary, target)
            enforceCap()
        } finally {
            temporary.delete()
        }
    }

    override suspend fun remove(key: String) = mutex.withLock {
        fileFor(key).delete()
        Unit
    }

    private fun read(file: File, deleteInvalid: Boolean): CachedOpenLibraryMetadata? {
        if (!file.isFile || file.length() > DEFAULT_MAX_TOTAL_BYTES) return null
        return try {
            json.decodeFromString<CachedOpenLibraryMetadata>(file.readText(Charsets.UTF_8))
        } catch (_: SerializationException) {
            if (deleteInvalid) file.delete()
            null
        } catch (_: IllegalArgumentException) {
            if (deleteInvalid) file.delete()
            null
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun enforceCap() {
        val files = directory.listFiles { file -> file.isFile && file.extension == "json" }
            .orEmpty()
            .toMutableList()
        var total = files.sumOf { it.length() }
        files.sortedWith(
            compareBy<File> { read(it, deleteInvalid = true)?.cachedAtEpochMillis ?: Long.MIN_VALUE }
                .thenBy { it.name },
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

    private fun fileFor(key: String) = File(directory, fileNameForKey(key))

    companion object {
        const val DEFAULT_MAX_TOTAL_BYTES = 1024L * 1024L
        private val DEFAULT_JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }

        fun fileNameForKey(key: String): String = "${CatalogCacheKey.sha256(key)}.json"
    }
}

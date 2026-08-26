package com.air5005.pagenest.discovery.importing

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface OnlineImportLedger {
    suspend fun get(stableKey: String): Long?
    suspend fun put(stableKey: String, bookId: Long)
    suspend fun remove(stableKey: String)
}

class FileOnlineImportLedger(
    private val ledgerFile: File,
    private val json: Json = Json,
) : OnlineImportLedger {
    private val mutex = Mutex()

    override suspend fun get(stableKey: String): Long? = mutex.withLock {
        validateStableKey(stableKey)
        withContext(Dispatchers.IO) { readEntries()[stableKey] }
    }

    override suspend fun put(stableKey: String, bookId: Long) = mutex.withLock {
        validateStableKey(stableKey)
        require(bookId > 0L) { "bookId must be positive" }
        withContext(Dispatchers.IO) {
            val entries = readEntries().toMutableMap()
            entries[stableKey] = bookId
            writeEntries(entries)
        }
    }

    override suspend fun remove(stableKey: String) = mutex.withLock {
        validateStableKey(stableKey)
        withContext(Dispatchers.IO) {
            val entries = readEntries().toMutableMap()
            if (entries.remove(stableKey) != null) writeEntries(entries)
        }
    }

    private fun readEntries(): Map<String, Long> {
        if (!ledgerFile.isFile) return emptyMap()
        return try {
            json.decodeFromString<Map<String, Long>>(ledgerFile.readText(StandardCharsets.UTF_8))
                .filter { (key, value) -> isValidStableKey(key) && value > 0L }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeEntries(entries: Map<String, Long>) {
        val parent = ledgerFile.parentFile ?: error("Ledger requires a parent directory")
        check(parent.mkdirs() || parent.isDirectory) { "Unable to initialize ledger directory" }
        val temporary = File(parent, "${ledgerFile.name}.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
                val orderedEntries: Map<String, Long> = entries.toSortedMap()
                writer.write(json.encodeToString<Map<String, Long>>(orderedEntries))
                writer.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    ledgerFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (unsupported: AtomicMoveNotSupportedException) {
                throw IllegalStateException("Atomic ledger replacement is unavailable", unsupported)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun validateStableKey(stableKey: String) {
        require(isValidStableKey(stableKey)) { "Invalid stable key" }
    }

    private fun isValidStableKey(stableKey: String): Boolean =
        stableKey.isNotBlank() &&
            stableKey.length <= MAX_STABLE_KEY_LENGTH &&
            ':' in stableKey &&
            "://" !in stableKey &&
            stableKey.none(Char::isISOControl)

    companion object {
        private const val MAX_STABLE_KEY_LENGTH = 512
    }
}

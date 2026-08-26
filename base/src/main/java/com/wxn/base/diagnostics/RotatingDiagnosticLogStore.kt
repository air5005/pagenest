package com.wxn.base.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class RotatingDiagnosticLogStore(
    private val directory: File,
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxFiles: Int = DEFAULT_MAX_FILES,
) : DiagnosticLogStore {
    init {
        require(maxFileBytes > 0)
        require(maxFiles > 0)
        directory.mkdirs()
        cleanupUnexpectedFiles()
    }

    @Synchronized
    override fun append(entry: DiagnosticLogEntry) {
        directory.mkdirs()
        val bytes = (DiagnosticLogCodec.encode(entry) + "\n").toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= maxFileBytes) { "Diagnostic entry exceeds the file limit" }
        val current = logFile(0)
        if (current.exists() && current.length() + bytes.size > maxFileBytes) rotate()
        FileOutputStream(current, true).use { output ->
            output.write(bytes)
        }
    }

    @Synchronized
    override fun readRecent(limit: Int): List<DiagnosticLogEntry> {
        if (limit <= 0) return emptyList()
        return (0 until maxFiles)
            .asSequence()
            .map(::logFile)
            .filter(File::isFile)
            .flatMap { file -> file.useLines { lines -> lines.mapNotNull(DiagnosticLogCodec::decode).toList().asSequence() } }
            .sortedWith(DiagnosticLogEntry.NEWEST_FIRST)
            .take(limit)
            .toList()
    }

    @Synchronized
    override fun clear() {
        (0 until maxFiles).forEach { logFile(it).delete() }
    }

    @Synchronized
    override fun totalBytes(): Long = (0 until maxFiles).sumOf { logFile(it).takeIf(File::isFile)?.length() ?: 0L }

    override fun flush() = Unit

    private fun rotate() {
        for (index in maxFiles - 2 downTo 0) {
            val source = logFile(index)
            if (!source.exists()) continue
            val destination = logFile(index + 1)
            destination.delete()
            if (!source.renameTo(destination)) {
                source.copyTo(destination, overwrite = true)
                source.delete()
            }
        }
    }

    private fun cleanupUnexpectedFiles() {
        val retainedNames = (0 until maxFiles).map { logFile(it).name }.toSet()
        directory.listFiles().orEmpty().filter { it.isFile && it.name !in retainedNames }.forEach(File::delete)
    }

    private fun logFile(index: Int): File = File(directory, "pagenest-$index.log")

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 512L * 1024L
        const val DEFAULT_MAX_FILES = 4
        const val DEFAULT_MAX_ENTRIES = 500
    }
}

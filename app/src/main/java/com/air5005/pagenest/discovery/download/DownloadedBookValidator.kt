package com.air5005.pagenest.discovery.download

import com.air5005.pagenest.discovery.model.OnlineBookFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class DownloadedBookValidator {
    fun validate(file: File, format: OnlineBookFormat, contentType: String?): Boolean {
        if (!file.isFile || file.length() <= 0L || !mimeMatches(format, contentType)) return false
        return try {
            when (format) {
                OnlineBookFormat.EPUB -> isEpub(file)
                OnlineBookFormat.PDF -> hasPrefix(file, PDF_PREFIX)
                OnlineBookFormat.TXT -> isUtf8Text(file)
                OnlineBookFormat.HTML,
                OnlineBookFormat.UNKNOWN,
                -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun mimeMatches(format: OnlineBookFormat, contentType: String?): Boolean {
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
            ?.takeIf(String::isNotEmpty) ?: return true
        if (mime == OCTET_STREAM) return true
        return when (format) {
            OnlineBookFormat.EPUB -> mime in EPUB_MIMES
            OnlineBookFormat.PDF -> mime == PDF_MIME
            OnlineBookFormat.TXT -> mime.startsWith("text/")
            else -> false
        }
    }

    private fun isEpub(file: File): Boolean = ZipFile(file).use { zip ->
        val first = zip.entries().asSequence().firstOrNull() ?: return false
        if (first.name != MIMETYPE_ENTRY || first.method != ZipEntry.STORED || first.size !in 1..64) {
            return false
        }
        val value = zip.getInputStream(first).use { it.readBytes() }.toString(StandardCharsets.US_ASCII)
        value == EPUB_MIME
    }

    private fun hasPrefix(file: File, prefix: ByteArray): Boolean = file.inputStream().use { input ->
        val actual = ByteArray(prefix.size)
        input.read(actual) == prefix.size && actual.contentEquals(prefix)
    }

    private fun isUtf8Text(file: File): Boolean {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(TEXT_SAMPLE_BYTES)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            buffer.copyOf(count)
        }
        if (bytes.isEmpty() || bytes.any { it == 0.toByte() }) return false
        val payload = if (bytes.size >= UTF8_BOM.size && bytes.copyOfRange(0, UTF8_BOM.size)
                .contentEquals(UTF8_BOM)
        ) bytes.copyOfRange(UTF8_BOM.size, bytes.size) else bytes
        if (payload.isEmpty()) return false
        try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(payload))
        } catch (_: Exception) {
            return false
        }
        val disallowedControls = payload.count { byte ->
            val value = byte.toInt() and 0xff
            value < 0x20 && value !in setOf(0x09, 0x0a, 0x0d)
        }
        return disallowedControls * 100 <= payload.size
    }

    companion object {
        private const val TEXT_SAMPLE_BYTES = 8 * 1024
        private const val MIMETYPE_ENTRY = "mimetype"
        private const val EPUB_MIME = "application/epub+zip"
        private const val PDF_MIME = "application/pdf"
        private const val OCTET_STREAM = "application/octet-stream"
        private val EPUB_MIMES = setOf(EPUB_MIME, "application/zip")
        private val PDF_PREFIX = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
        private val UTF8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
    }
}

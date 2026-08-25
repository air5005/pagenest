package com.air5005.pagenest.discovery.download

import com.air5005.pagenest.discovery.model.OnlineBookFormat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadedBookValidatorTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val validator = DownloadedBookValidator()

    @Test
    fun `valid EPUB requires stored mimetype entry`() {
        val valid = temporaryFolder.newFile("valid.epub")
        writeEpub(valid, "application/epub+zip", stored = true)
        assertTrue(validator.validate(valid, OnlineBookFormat.EPUB, "application/epub+zip"))

        val compressed = temporaryFolder.newFile("compressed.epub")
        writeEpub(compressed, "application/epub+zip", stored = false)
        assertFalse(validator.validate(compressed, OnlineBookFormat.EPUB, "application/epub+zip"))

        val wrong = temporaryFolder.newFile("wrong.epub")
        writeEpub(wrong, "application/zip", stored = true)
        assertFalse(validator.validate(wrong, OnlineBookFormat.EPUB, "application/epub+zip"))
    }

    @Test
    fun `PDF requires PDF header and rejects conflicting MIME`() {
        val valid = temporaryFolder.newFile("valid.pdf").apply { writeBytes("%PDF-1.7\nbody".toByteArray()) }
        assertTrue(validator.validate(valid, OnlineBookFormat.PDF, "application/pdf"))
        assertTrue(validator.validate(valid, OnlineBookFormat.PDF, "application/octet-stream"))
        assertFalse(validator.validate(valid, OnlineBookFormat.PDF, "text/plain"))

        val fake = temporaryFolder.newFile("fake.pdf").apply { writeText("not a pdf") }
        assertFalse(validator.validate(fake, OnlineBookFormat.PDF, "application/pdf"))
    }

    @Test
    fun `TXT permits UTF8 BOM but rejects NUL and binary MIME`() {
        val valid = temporaryFolder.newFile("valid.txt").apply {
            writeBytes(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "hello\nworld".toByteArray())
        }
        assertTrue(validator.validate(valid, OnlineBookFormat.TXT, "text/plain; charset=utf-8"))
        assertTrue(validator.validate(valid, OnlineBookFormat.TXT, null))
        assertFalse(validator.validate(valid, OnlineBookFormat.TXT, "application/pdf"))

        val binary = temporaryFolder.newFile("binary.txt").apply { writeBytes(byteArrayOf(1, 2, 0, 4)) }
        assertFalse(validator.validate(binary, OnlineBookFormat.TXT, "text/plain"))
    }

    @Test
    fun `unknown format and empty files are rejected`() {
        val empty = temporaryFolder.newFile("empty.bin")
        assertFalse(validator.validate(empty, OnlineBookFormat.UNKNOWN, null))
        assertFalse(validator.validate(empty, OnlineBookFormat.TXT, "text/plain"))
    }

    private fun writeEpub(file: File, mimetype: String, stored: Boolean) {
        FileOutputStream(file).use { output ->
            ZipOutputStream(output).use { zip ->
                val bytes = mimetype.toByteArray()
                val entry = ZipEntry("mimetype")
                if (stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = bytes.size.toLong()
                    entry.compressedSize = bytes.size.toLong()
                    entry.crc = CRC32().apply { update(bytes) }.value
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}

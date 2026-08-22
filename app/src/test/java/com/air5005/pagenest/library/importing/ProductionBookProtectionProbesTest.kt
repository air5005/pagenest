package com.air5005.pagenest.library.importing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.wxn.mobi.data.model.MetaInfo
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductionBookProtectionProbesTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun mobiProbeReadsTheMetadataEncryptionFlag() {
        assertTrue(ProductionBookProtectionProbes.mobiEncrypted(metaInfo(isEncrypted = true)))
        assertFalse(ProductionBookProtectionProbes.mobiEncrypted(metaInfo(isEncrypted = false)))
    }

    @Test
    fun mobiProbeCannotClearAFileWithoutMetadata() {
        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.mobiEncrypted(null)
        }
    }

    @Test
    fun pdfProbeReadsTheDocumentEncryptionFlag() {
        val clearPdf = temporaryFolder.newFile("clear.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(clearPdf)
        }
        val encryptedPdf = temporaryFolder.newFile("encrypted.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(
                StandardProtectionPolicy(
                    "owner-password",
                    "",
                    AccessPermission(),
                ),
            )
            document.save(encryptedPdf)
        }

        assertFalse(ProductionBookProtectionProbes.pdfEncrypted(clearPdf))
        assertTrue(ProductionBookProtectionProbes.pdfEncrypted(encryptedPdf))
    }

    @Test
    fun epubProbeRejectsAnLcplLicense() {
        val epub = epubWith("META-INF/license.lcpl" to "{}")

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeAllowsOnlyIdpfAndAdobeFontObfuscation() {
        val epub = epubWith(
            "META-INF/encryption.xml" to encryptionXml(
                "http://www.idpf.org/2008/embedding",
                "http://ns.adobe.com/pdf/enc#RC",
            ),
        )

        assertFalse(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeRejectsAnyOtherEncryptionAlgorithm() {
        val epub = epubWith(
            "META-INF/encryption.xml" to encryptionXml(
                "http://www.w3.org/2001/04/xmlenc#aes256-cbc",
            ),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeCannotClearMalformedEncryptionMetadata() {
        val epub = epubWith("META-INF/encryption.xml" to "<encryption>")

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(epub)
        }
    }

    private fun epubWith(vararg entries: Pair<String, String>): File {
        val file = temporaryFolder.newFile("book-${entries.size}-${System.nanoTime()}.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, contents) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray())
                zip.closeEntry()
            }
        }
        return file
    }

    private fun encryptionXml(vararg algorithms: String): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("""<encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">""")
        algorithms.forEach { algorithm ->
            append("<EncryptedData>")
            append("""<EncryptionMethod Algorithm="$algorithm"/>""")
            append("</EncryptedData>")
        }
        append("</encryption>")
    }

    private fun metaInfo(isEncrypted: Boolean) = MetaInfo(
        title = "title",
        author = "author",
        contributor = "contributor",
        subject = "subject",
        publisher = "publisher",
        date = "date",
        description = "description",
        review = "review",
        imprint = "imprint",
        copyright = "copyright",
        isbn = "isbn",
        asin = "asin",
        language = "language",
        isEncrypted = isEncrypted,
        coverPath = "cover",
    )
}

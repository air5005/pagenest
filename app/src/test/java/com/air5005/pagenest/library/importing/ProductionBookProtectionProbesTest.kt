package com.air5005.pagenest.library.importing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
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
    fun mobiFactoryRoutesTheActualFileToTheDedicatedHeaderProbe() {
        val mobi = temporaryFolder.newFile("protected.mobi")
        var inspectedPath: String? = null
        val inspector = ProductionBookProtectionProbes.create(
            mobiEncrypted = { path ->
                inspectedPath = path
                true
            },
        )

        assertEquals(
            ProtectionVerdict.PROTECTED,
            inspector.inspect(mobi, SupportedBookFormat.MOBI),
        )
        assertEquals(mobi.absolutePath, inspectedPath)
    }

    @Test
    fun productionMobiFactoryDefersNativeLoadingAndFailsClosedWhenUnavailable() {
        val mobi = temporaryFolder.newFile("native-unavailable.mobi")
        val inspector = ProductionBookProtectionProbes.create()

        assertEquals(
            ProtectionVerdict.UNREADABLE,
            inspector.inspect(mobi, SupportedBookFormat.MOBI),
        )
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
    fun pdfProbeTreatsANonEmptyUserPasswordAsProtected() {
        val encryptedPdf = temporaryFolder.newFile("password-protected.pdf")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.protect(
                StandardProtectionPolicy(
                    "owner-password",
                    "reader-password",
                    AccessPermission(),
                ),
            )
            document.save(encryptedPdf)
        }

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

    @Test
    fun epubProbeRejectsSecurityElementsInTheWrongNamespaces() {
        val epub = epubWith(
            "META-INF/encryption.xml" to """
                <encryption xmlns="urn:attacker:container" xmlns:enc="urn:attacker:xmlenc">
                    <enc:EncryptedData>
                        <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                    </enc:EncryptedData>
                </encryption>
            """.trimIndent(),
        )

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(epub)
        }
    }

    @Test
    fun epubProbeRejectsANestedEncryptionMethod() {
        val epub = epubWith(
            "META-INF/encryption.xml" to """
                <ocf:encryption
                    xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container"
                    xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                    <enc:EncryptedData>
                        <enc:CipherData>
                            <enc:EncryptionMethod Algorithm="http://www.idpf.org/2008/embedding"/>
                        </enc:CipherData>
                    </enc:EncryptedData>
                </ocf:encryption>
            """.trimIndent(),
        )

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(epub)
        }
    }

    @Test
    fun epubProbeRejectsDuplicateEncryptionMetadataEntries() {
        val epub = epubWithDuplicateEntry(
            "META-INF/encryption.xml",
            encryptionXml("http://www.idpf.org/2008/embedding"),
        )

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(epub)
        }
    }

    @Test
    fun epubProbeRejectsDuplicateLicenseEntries() {
        val epub = epubWithDuplicateEntry("META-INF/license.lcpl", "{}")

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(epub)
        }
    }

    @Test
    fun epubProbeRejectsOversizedEncryptionMetadata() {
        val oversizedXml = encryptionXml("http://www.idpf.org/2008/embedding") +
            " ".repeat(70 * 1024)
        val epub = epubWith("META-INF/encryption.xml" to oversizedXml)

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
        append(
            """<ocf:encryption xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container" """ +
                """xmlns:enc="http://www.w3.org/2001/04/xmlenc#">""",
        )
        algorithms.forEach { algorithm ->
            append("<enc:EncryptedData>")
            append("""<enc:EncryptionMethod Algorithm="$algorithm"/>""")
            append("""<enc:CipherData><enc:CipherReference URI="content.xhtml"/></enc:CipherData>""")
            append("</enc:EncryptedData>")
        }
        append("</ocf:encryption>")
    }

    private fun epubWithDuplicateEntry(name: String, contents: String): File {
        val alternateName = name.dropLast(1) + "_"
        val file = epubWith(name to contents, alternateName to contents)
        val bytes = file.readBytes()
        val alternateBytes = alternateName.toByteArray(StandardCharsets.UTF_8)
        val duplicateBytes = name.toByteArray(StandardCharsets.UTF_8)
        var replacements = 0
        for (offset in 0..bytes.size - alternateBytes.size) {
            if (bytes.copyOfRange(offset, offset + alternateBytes.size).contentEquals(alternateBytes)) {
                duplicateBytes.copyInto(bytes, destinationOffset = offset)
                replacements++
            }
        }
        check(replacements == 2) { "Expected local and central ZIP names" }
        file.writeBytes(bytes)
        return file
    }

}

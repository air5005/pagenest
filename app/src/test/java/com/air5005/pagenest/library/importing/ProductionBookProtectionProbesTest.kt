package com.air5005.pagenest.library.importing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import java.io.ByteArrayInputStream
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
        val epub = epubWithFontObfuscation(
            "http://www.idpf.org/2008/embedding" to "OEBPS/fonts/idpf.otf",
            "http://ns.adobe.com/pdf/enc#RC" to "OEBPS/fonts/adobe.woff",
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

    @Test
    fun epubProbeRejectsMissingOrWrongNamespaceCipherData() {
        val missingCipherData = epubWith(
            "META-INF/encryption.xml" to encryptionXml(
                "http://www.idpf.org/2008/embedding",
                includeCipherData = false,
            ),
        )
        val wrongNamespaceCipherData = epubWith(
            "META-INF/encryption.xml" to encryptionXml(
                "http://www.idpf.org/2008/embedding",
                cipherNamespace = "urn:attacker:xmlenc",
            ),
        )

        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(missingCipherData)
        }
        assertThrows(IOException::class.java) {
            ProductionBookProtectionProbes.epubProtected(wrongNamespaceCipherData)
        }
    }

    @Test
    fun epubProbeProtectsAnObfuscatedNonFontTarget() {
        val epub = epubWithPublication(
            encryptionXml("http://www.idpf.org/2008/embedding", target = "OEBPS/text.xhtml"),
            manifestItems = listOf("text.xhtml" to "application/xhtml+xml"),
            publicationEntries = listOf("OEBPS/text.xhtml" to "<html/>"),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeProtectsANonCoreLegacyFontMediaType() {
        val epub = epubWithPublication(
            encryptionXml("http://www.idpf.org/2008/embedding"),
            manifestItems = listOf("fonts/font.otf" to "application/vnd.ms-opentype"),
            publicationEntries = listOf("OEBPS/fonts/font.otf" to "font"),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeProtectsAnAbsentObfuscationTarget() {
        val epub = epubWithPublication(
            encryptionXml("http://www.idpf.org/2008/embedding", target = "OEBPS/fonts/missing.otf"),
            manifestItems = listOf("fonts/missing.otf" to "font/otf"),
            publicationEntries = emptyList(),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeProtectsEncryptedKeyMetadata() {
        val encryptedKeyXml = encryptionXml("http://www.idpf.org/2008/embedding").replace(
            "</enc:EncryptedData>",
            "<enc:KeyInfo><enc:EncryptedKey/></enc:KeyInfo></enc:EncryptedData>",
        )
        val epub = epubWithPublication(
            encryptedKeyXml,
            manifestItems = listOf("fonts/font.otf" to "font/otf"),
            publicationEntries = listOf("OEBPS/fonts/font.otf" to "font"),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun epubProbeProtectsAStandardsValidTopLevelEncryptedKey() {
        val epub = epubWith(
            "META-INF/encryption.xml" to """
                <ocf:encryption
                    xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container"
                    xmlns:enc="http://www.w3.org/2001/04/xmlenc#">
                    <enc:EncryptedKey>
                        <enc:EncryptionMethod Algorithm="http://www.w3.org/2001/04/xmlenc#kw-aes256"/>
                        <enc:CipherData>
                            <enc:CipherValue>AA==</enc:CipherValue>
                        </enc:CipherData>
                    </enc:EncryptedKey>
                </ocf:encryption>
            """.trimIndent(),
        )

        assertTrue(ProductionBookProtectionProbes.epubProtected(epub))
    }

    @Test
    fun securityMetadataBoundedStreamCountsActualDecompressedBytes() {
        val input = SecurityMetadataBoundedInputStream(
            ByteArrayInputStream(ByteArray(65_537)),
            maxBytes = 65_536,
        )

        assertThrows(IOException::class.java) {
            input.readBytes()
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

    private fun encryptionXml(
        vararg algorithms: String,
        target: String = "OEBPS/fonts/font.otf",
        includeCipherData: Boolean = true,
        cipherNamespace: String = "http://www.w3.org/2001/04/xmlenc#",
    ): String = encryptionXmlEntries(
        *algorithms.map { it to target }.toTypedArray(),
        includeCipherData = includeCipherData,
        cipherNamespace = cipherNamespace,
    )

    private fun encryptionXmlEntries(
        vararg entries: Pair<String, String>,
        includeCipherData: Boolean = true,
        cipherNamespace: String = "http://www.w3.org/2001/04/xmlenc#",
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append(
            """<ocf:encryption xmlns:ocf="urn:oasis:names:tc:opendocument:xmlns:container" """ +
                """xmlns:enc="http://www.w3.org/2001/04/xmlenc#">""",
        )
        entries.forEach { (algorithm, target) ->
            append("<enc:EncryptedData>")
            append("""<enc:EncryptionMethod Algorithm="$algorithm"/>""")
            if (includeCipherData) {
                append(
                    """<cipher:CipherData xmlns:cipher="$cipherNamespace">""" +
                        """<cipher:CipherReference URI="$target"/>""" +
                        "</cipher:CipherData>",
                )
            }
            append("</enc:EncryptedData>")
        }
        append("</ocf:encryption>")
    }

    private fun epubWithFontObfuscation(vararg targets: Pair<String, String>): File {
        val mediaTypes = mapOf("otf" to "font/otf", "woff" to "font/woff")
        return epubWithPublication(
            encryptionXmlEntries(*targets),
            manifestItems = targets.map { (_, path) ->
                path.removePrefix("OEBPS/") to mediaTypes.getValue(path.substringAfterLast('.'))
            },
            publicationEntries = targets.map { (_, path) -> path to "font" },
        )
    }

    private fun epubWithPublication(
        encryption: String,
        manifestItems: List<Pair<String, String>>,
        publicationEntries: List<Pair<String, String>>,
    ): File {
        val container = """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
                <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                </rootfiles>
            </container>
        """.trimIndent()
        val manifest = manifestItems.mapIndexed { index, (href, mediaType) ->
            """<item id="item$index" href="$href" media-type="$mediaType"/>"""
        }.joinToString("")
        val packageDocument = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
                <metadata/>
                <manifest>$manifest</manifest>
                <spine/>
            </package>
        """.trimIndent()
        return epubWith(
            "META-INF/encryption.xml" to encryption,
            "META-INF/container.xml" to container,
            "OEBPS/content.opf" to packageDocument,
            *publicationEntries.toTypedArray(),
        )
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

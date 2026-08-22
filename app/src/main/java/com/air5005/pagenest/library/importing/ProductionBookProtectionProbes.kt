package com.air5005.pagenest.library.importing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.wxn.mobi.inative.NativeLib
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object ProductionBookProtectionProbes {
    private const val EPUB_LICENSE_PATH = "META-INF/license.lcpl"
    private const val EPUB_ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val OCF_CONTAINER_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val XML_ENCRYPTION_NAMESPACE = "http://www.w3.org/2001/04/xmlenc#"
    private const val MAX_EPUB_SECURITY_METADATA_BYTES = 64 * 1024L
    private const val IDPF_FONT_OBFUSCATION = "http://www.idpf.org/2008/embedding"
    private const val ADOBE_FONT_OBFUSCATION = "http://ns.adobe.com/pdf/enc#RC"

    fun create(): BookProtectionInspector = create { path ->
        NativeLib.isMobiEncrypted(path)
    }

    internal fun create(mobiEncrypted: (String) -> Boolean): BookProtectionInspector =
        DefaultBookProtectionInspector(
            mobiEncrypted = { file -> mobiEncrypted(file.absolutePath) },
            pdfEncrypted = ::pdfEncrypted,
            epubProtected = ::epubProtected,
        )

    internal fun pdfEncrypted(file: File): Boolean = try {
        PDDocument.load(file).use(PDDocument::isEncrypted)
    } catch (_: InvalidPasswordException) {
        true
    }

    internal fun epubProtected(file: File): Boolean = ZipFile(file).use { epub ->
        if (epub.uniqueEntry(EPUB_LICENSE_PATH) != null) {
            return@use true
        }

        val encryptionEntry = epub.uniqueEntry(EPUB_ENCRYPTION_PATH) ?: return@use false
        if (encryptionEntry.size > MAX_EPUB_SECURITY_METADATA_BYTES ||
            encryptionEntry.compressedSize > MAX_EPUB_SECURITY_METADATA_BYTES
        ) {
            throw IOException("EPUB encryption metadata is too large")
        }
        val document = epub.getInputStream(encryptionEntry).use { input ->
            try {
                secureDocumentBuilderFactory().newDocumentBuilder().parse(
                    BoundedInputStream(input, MAX_EPUB_SECURITY_METADATA_BYTES),
                )
            } catch (exception: Exception) {
                throw IOException("EPUB encryption metadata is unreadable", exception)
            }
        }
        val root = document.documentElement
        if (root.localName != "encryption" || root.namespaceURI != OCF_CONTAINER_NAMESPACE) {
            throw IOException("EPUB encryption metadata has an unexpected root element")
        }

        val encryptedData = root.directElementChildren()
        if (encryptedData.isEmpty() || encryptedData.any {
                it.localName != "EncryptedData" || it.namespaceURI != XML_ENCRYPTION_NAMESPACE
            }
        ) {
            throw IOException("EPUB encryption metadata has an ambiguous encrypted-data structure")
        }

        for (entry in encryptedData) {
            val directMethods = entry.directElementChildren().filter {
                it.localName == "EncryptionMethod" &&
                    it.namespaceURI == XML_ENCRYPTION_NAMESPACE
            }
            val allMethods = entry.getElementsByTagNameNS("*", "EncryptionMethod")
            if (directMethods.size != 1 || allMethods.length != 1 ||
                allMethods.item(0) !== directMethods.single()
            ) {
                throw IOException("EPUB encrypted data has an ambiguous encryption method")
            }
            val method = directMethods.single()
            val algorithm = method.getAttribute("Algorithm")
            if (algorithm !in setOf(IDPF_FONT_OBFUSCATION, ADOBE_FONT_OBFUSCATION)) {
                return@use true
            }
        }

        false
    }

    private fun ZipFile.uniqueEntry(name: String): ZipEntry? {
        val matches = entries().asSequence().filter { it.name == name }.toList()
        if (matches.size > 1) {
            throw IOException("EPUB contains duplicate security metadata")
        }
        return matches.singleOrNull()
    }

    private fun Element.directElementChildren(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private class BoundedInputStream(
        input: InputStream,
        private val maxBytes: Long,
    ) : FilterInputStream(input) {
        private var bytesRead = 0L

        override fun read(): Int = super.read().also { value ->
            if (value != -1) accountFor(1)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { count ->
                if (count > 0) accountFor(count)
            }

        private fun accountFor(count: Int) {
            bytesRead += count
            if (bytesRead > maxBytes) {
                throw IOException("EPUB encryption metadata is too large")
            }
        }
    }

    private fun secureDocumentBuilderFactory(): DocumentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
}

package com.air5005.pagenest.library.importing

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.wxn.mobi.inative.NativeLib
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

object ProductionBookProtectionProbes {
    private const val EPUB_LICENSE_PATH = "META-INF/license.lcpl"
    private const val EPUB_ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val EPUB_CONTAINER_PATH = "META-INF/container.xml"
    private const val OCF_CONTAINER_NAMESPACE =
        "urn:oasis:names:tc:opendocument:xmlns:container"
    private const val XML_ENCRYPTION_NAMESPACE = "http://www.w3.org/2001/04/xmlenc#"
    private const val OPF_NAMESPACE = "http://www.idpf.org/2007/opf"
    private const val OPF_MEDIA_TYPE = "application/oebps-package+xml"
    private const val MAX_EPUB_SECURITY_METADATA_BYTES = 64 * 1024L
    private const val IDPF_FONT_OBFUSCATION = "http://www.idpf.org/2008/embedding"
    private const val ADOBE_FONT_OBFUSCATION = "http://ns.adobe.com/pdf/enc#RC"
    private val EPUB_FONT_MEDIA_TYPES = setOf(
        "font/otf",
        "font/ttf",
        "font/woff",
        "font/woff2",
    )

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
        val document = epub.parseBoundedXml(encryptionEntry)
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
        if (root.getElementsByTagNameNS("*", "EncryptedKey").length != 0) {
            return@use true
        }

        val obfuscatedTargets = mutableListOf<String>()
        for (entry in encryptedData) {
            val directChildren = entry.directElementChildren()
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

            val cipherData = directChildren.filter {
                it.localName == "CipherData" && it.namespaceURI == XML_ENCRYPTION_NAMESPACE
            }
            if (cipherData.size != 1 || directChildren.size != 2) {
                throw IOException("EPUB encrypted data has invalid cipher data")
            }
            val cipherChildren = cipherData.single().directElementChildren()
            if (cipherChildren.size != 1 ||
                cipherChildren.single().localName != "CipherReference" ||
                cipherChildren.single().namespaceURI != XML_ENCRYPTION_NAMESPACE
            ) {
                throw IOException("EPUB encrypted data has invalid cipher reference")
            }
            val target = normalizedZipPath(cipherChildren.single().getAttribute("URI"))
                ?: throw IOException("EPUB encrypted data has an invalid target URI")
            obfuscatedTargets += target
        }

        obfuscatedTargets.any { target -> !epub.isManifestFontTarget(target) }
    }

    private fun ZipFile.isManifestFontTarget(target: String): Boolean {
        val targetEntry = uniqueEntry(target) ?: return false
        if (targetEntry.isDirectory) return false

        val containerEntry = uniqueEntry(EPUB_CONTAINER_PATH) ?: return false
        val containerRoot = parseBoundedXml(containerEntry).documentElement
        if (containerRoot.localName != "container" ||
            containerRoot.namespaceURI != OCF_CONTAINER_NAMESPACE
        ) {
            throw IOException("EPUB container metadata has an unexpected root element")
        }
        val rootfiles = containerRoot.directElementChildren().filter {
            it.localName == "rootfiles" && it.namespaceURI == OCF_CONTAINER_NAMESPACE
        }
        if (rootfiles.size != 1) {
            throw IOException("EPUB container metadata has ambiguous rootfiles")
        }
        val roots = rootfiles.single().directElementChildren()
        if (roots.size != 1 || roots.single().localName != "rootfile" ||
            roots.single().namespaceURI != OCF_CONTAINER_NAMESPACE ||
            roots.single().getAttribute("media-type") != OPF_MEDIA_TYPE
        ) {
            throw IOException("EPUB container metadata has an ambiguous package document")
        }
        val packagePath = normalizedZipPath(roots.single().getAttribute("full-path"))
            ?: throw IOException("EPUB package path is invalid")
        val packageEntry = uniqueEntry(packagePath) ?: return false
        val packageRoot = parseBoundedXml(packageEntry).documentElement
        if (packageRoot.localName != "package" || packageRoot.namespaceURI != OPF_NAMESPACE) {
            throw IOException("EPUB package document has an unexpected root element")
        }
        val manifests = packageRoot.directElementChildren().filter {
            it.localName == "manifest" && it.namespaceURI == OPF_NAMESPACE
        }
        if (manifests.size != 1) {
            throw IOException("EPUB package document has an ambiguous manifest")
        }
        val packageDirectory = packagePath.substringBeforeLast('/', missingDelimiterValue = "")
        val matches = manifests.single().directElementChildren().filter { item ->
            item.localName == "item" && item.namespaceURI == OPF_NAMESPACE &&
                normalizedZipPath(item.getAttribute("href"), packageDirectory) == target
        }
        return matches.size == 1 && matches.single().getAttribute("media-type") in EPUB_FONT_MEDIA_TYPES
    }

    private fun ZipFile.parseBoundedXml(entry: ZipEntry): Document {
        if (entry.size > MAX_EPUB_SECURITY_METADATA_BYTES ||
            entry.compressedSize > MAX_EPUB_SECURITY_METADATA_BYTES
        ) {
            throw IOException("EPUB security metadata is too large")
        }
        return getInputStream(entry).use { input ->
            try {
                secureDocumentBuilderFactory().newDocumentBuilder().parse(
                    SecurityMetadataBoundedInputStream(
                        input,
                        MAX_EPUB_SECURITY_METADATA_BYTES,
                    ),
                )
            } catch (exception: Exception) {
                throw IOException("EPUB security metadata is unreadable", exception)
            }
        }
    }

    private fun normalizedZipPath(rawPath: String, baseDirectory: String = ""): String? = try {
        val uri = URI(rawPath)
        if (rawPath.isBlank() || rawPath.contains('\\') || uri.isAbsolute ||
            uri.rawAuthority != null || uri.rawQuery != null || uri.rawFragment != null
        ) {
            null
        } else {
            val path = uri.path ?: return null
            val combined = listOf(baseDirectory, path).filter(String::isNotEmpty).joinToString("/")
            val segments = combined.split('/')
            if (combined.startsWith('/') || segments.any { it.isEmpty() || it == "." || it == ".." }) {
                null
            } else {
                segments.joinToString("/")
            }
        }
    } catch (_: Exception) {
        null
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

internal class SecurityMetadataBoundedInputStream(
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
            throw IOException("EPUB security metadata is too large")
        }
    }
}

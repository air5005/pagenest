package com.air5005.pagenest.library.importing

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.wxn.mobi.MobiParser
import com.wxn.mobi.data.model.MetaInfo
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

object ProductionBookProtectionProbes {
    private const val EPUB_LICENSE_PATH = "META-INF/license.lcpl"
    private const val EPUB_ENCRYPTION_PATH = "META-INF/encryption.xml"
    private const val IDPF_FONT_OBFUSCATION = "http://www.idpf.org/2008/embedding"
    private const val ADOBE_FONT_OBFUSCATION = "http://ns.adobe.com/pdf/enc#RC"

    fun create(context: Context): BookProtectionInspector = DefaultBookProtectionInspector(
        mobiEncrypted = { file ->
            mobiEncrypted(MobiParser.getMobiInfo(context, file.absolutePath))
        },
        pdfEncrypted = ::pdfEncrypted,
        epubProtected = ::epubProtected,
    )

    internal fun mobiEncrypted(metaInfo: MetaInfo?): Boolean =
        metaInfo?.isEncrypted ?: throw IOException("MOBI metadata is unreadable")

    internal fun pdfEncrypted(file: File): Boolean =
        PDDocument.load(file).use(PDDocument::isEncrypted)

    internal fun epubProtected(file: File): Boolean = ZipFile(file).use { epub ->
        if (epub.getEntry(EPUB_LICENSE_PATH) != null) {
            return@use true
        }

        val encryptionEntry = epub.getEntry(EPUB_ENCRYPTION_PATH) ?: return@use false
        val document = epub.getInputStream(encryptionEntry).use { input ->
            try {
                secureDocumentBuilderFactory().newDocumentBuilder().parse(input)
            } catch (exception: Exception) {
                throw IOException("EPUB encryption metadata is unreadable", exception)
            }
        }
        val root = document.documentElement
        if (root.localName != "encryption") {
            throw IOException("EPUB encryption metadata has an unexpected root element")
        }

        val encryptionMethods = root.getElementsByTagNameNS("*", "EncryptionMethod")
        if (encryptionMethods.length == 0) {
            throw IOException("EPUB encryption metadata has no encryption method")
        }
        for (index in 0 until encryptionMethods.length) {
            val method = encryptionMethods.item(index) as? Element
                ?: throw IOException("EPUB encryption method is unreadable")
            val algorithm = method.getAttribute("Algorithm")
            if (algorithm !in setOf(IDPF_FONT_OBFUSCATION, ADOBE_FONT_OBFUSCATION)) {
                return@use true
            }
        }

        val encryptedData = root.getElementsByTagNameNS("*", "EncryptedData")
        if (encryptedData.length == 0) {
            throw IOException("EPUB encryption metadata has no encrypted data")
        }
        for (index in 0 until encryptedData.length) {
            val entry = encryptedData.item(index) as? Element
                ?: throw IOException("EPUB encrypted data is unreadable")
            if (entry.getElementsByTagNameNS("*", "EncryptionMethod").length != 1) {
                throw IOException("EPUB encrypted data has an ambiguous encryption method")
            }
        }

        false
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

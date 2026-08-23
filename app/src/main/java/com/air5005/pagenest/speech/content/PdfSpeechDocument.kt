package com.air5005.pagenest.speech.content

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal fun interface PdfPageTextExtractor {
    fun extract(document: PDDocument, pageIndex: Int): String
}

class PdfSpeechDocument internal constructor(
    private val document: PDDocument,
    private val ownedResource: Closeable? = null,
    private val dispatcher: CoroutineContext = Dispatchers.IO,
    private val extractor: PdfPageTextExtractor = PdfPageTextExtractor { value, pageIndex ->
        PDFTextStripper().apply {
            startPage = pageIndex + 1
            endPage = pageIndex + 1
        }.getText(value)
    },
) : Closeable {
    constructor(document: PDDocument) : this(document, null)

    val pageCount: Int
        get() {
            ensureOpen()
            return document.numberOfPages
        }

    val isClosed: Boolean
        get() = closed.get()

    suspend fun pageText(pageIndex: Int): String {
        ensureOpen()
        require(pageIndex in 0 until pageCount) { "PDF page index is out of bounds: $pageIndex" }
        return try {
            withContext(dispatcher) {
                coroutineContext.ensureActive()
                val extracted = extractor.extract(document, pageIndex)
                coroutineContext.ensureActive()
                normalizeWhitespace(extracted)
            }
        } catch (cancellation: CancellationException) {
            closeAfterCancellation(cancellation)
            throw cancellation
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        var failure: IOException? = null
        try {
            document.close()
        } catch (error: IOException) {
            failure = error
        }
        try {
            ownedResource?.close()
        } catch (error: IOException) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
        failure?.let { throw it }
    }

    private fun closeAfterCancellation(cancellation: CancellationException) {
        try {
            close()
        } catch (closeFailure: Throwable) {
            cancellation.addSuppressed(closeFailure)
        }
    }

    private fun ensureOpen() {
        check(!closed.get()) { "PDF speech document is closed" }
    }

    companion object {
        private val WHITESPACE = Regex("\\s+")

        fun open(context: Context, uri: Uri): PdfSpeechDocument {
            PDFBoxResourceLoader.init(context.applicationContext)
            if (uri.scheme == null || uri.scheme.equals("file", ignoreCase = true)) {
                return PdfSpeechDocument(PDDocument.load(File(URI(uri.toString()))))
            }

            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Unable to open PDF URI: $uri")
            val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            return try {
                PdfSpeechDocument(PDDocument.load(input), input)
            } catch (failure: Throwable) {
                try {
                    input.close()
                } catch (closeFailure: Throwable) {
                    failure.addSuppressed(closeFailure)
                }
                throw failure
            }
        }

        internal fun normalizeWhitespace(text: String): String =
            text.replace(WHITESPACE, " ").trim()
    }

    private val closed = AtomicBoolean(false)
}

package com.air5005.pagenest.speech.content

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PdfSpeechContentSourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Before
    fun initializePdfBox() {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `text PDF exposes normalized page segments and explicit close owns the document`() = runTest {
        val pdf = createPdf("  First   page  body  ", "Second\npage body")
        val document = PDDocument.load(pdf)
        val speechDocument = PdfSpeechDocument(document)
        val source = PdfSpeechContentSource(9, speechDocument, SpeechSegmenter())

        assertEquals(PdfSpeechAvailability.READABLE, source.availability())
        assertEquals(listOf("First page body", "Second page body"), collectText(source))
        assertFalse(document.document.isClosed)

        source.close()

        assertTrue(document.document.isClosed)
    }

    @Test
    fun `entirely image or blank PDF is scanned`() = runTest {
        val document = PDDocument.load(createPdf(null, "   "))
        val source = PdfSpeechContentSource(10, PdfSpeechDocument(document), SpeechSegmenter())

        assertEquals(PdfSpeechAvailability.SCANNED, source.availability())
        assertNull(source.current())

        source.close()
    }

    @Test
    fun `partial scanned PDF remains readable and skips empty pages`() = runTest {
        val source = PdfSpeechContentSource(
            12,
            PdfSpeechDocument(PDDocument.load(createPdf(null, "Visible text", null))),
            SpeechSegmenter(),
        )

        assertEquals(PdfSpeechAvailability.READABLE, source.availability())
        val segment = source.current()!!
        assertEquals("Visible text", segment.text)
        assertEquals(1, segment.position.pageIndex)
        assertNull(source.next())

        source.close()
    }

    @Test
    fun `cancellation during extraction closes document and owned descriptor`() = runTest {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owned = RecordingCloseable()
        val document = PDDocument().apply { addPage(PDPage()) }
        val speechDocument = PdfSpeechDocument(
            document = document,
            ownedResource = owned,
            dispatcher = Dispatchers.IO,
            extractor = PdfPageTextExtractor { _, _ ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                "late text"
            },
        )
        val source = PdfSpeechContentSource(14, speechDocument, SpeechSegmenter())
        val extraction = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            source.availability()
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        extraction.cancel()
        release.countDown()
        extraction.cancelAndJoin()

        assertTrue(document.document.isClosed)
        assertTrue(owned.closed.get())
    }

    @Test
    fun `private file URI is opened without a content resolver grant`() = runTest {
        val privatePdf = createPdf("Private book text")
        val privateUri = Uri.parse(privatePdf.toURI().toString())
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader::class.java.getDeclaredField("ASSET_MANAGER").apply {
            isAccessible = true
            set(null, null)
        }
        assertFalse(PDFBoxResourceLoader.isReady())

        val speechDocument = PdfSpeechDocument.open(context, privateUri)
        val source = PdfSpeechContentSource(16, speechDocument, SpeechSegmenter())

        assertTrue(PDFBoxResourceLoader.isReady())
        assertEquals("Private book text", source.current()?.text)

        source.close()
        assertTrue(speechDocument.isClosed)
    }

    @Test
    fun `seek and previous use PDF page locations`() = runTest {
        val source = PdfSpeechContentSource(
            18,
            PdfSpeechDocument(PDDocument.load(createPdf("One", "Two", "Three"))),
            SpeechSegmenter(),
        )

        val selected = source.seek(
            com.air5005.pagenest.speech.model.SpeechPosition(18, 0, 2, 0, 0),
        )

        assertEquals("Three", selected?.text)
        assertEquals("Two", source.previous()?.text)
        source.close()
    }

    private suspend fun collectText(source: SpeechContentSource): List<String> {
        val values = mutableListOf<String>()
        var segment = source.current()
        while (segment != null) {
            values += segment.text
            segment = source.next()
        }
        return values
    }

    private fun createPdf(vararg pageText: String?): File {
        val file = temporaryFolder.newFile("${System.nanoTime()}.pdf")
        PDDocument().use { document ->
            pageText.forEach { text ->
                val page = PDPage()
                document.addPage(page)
                if (text != null) {
                    PDPageContentStream(document, page).use { stream ->
                        stream.beginText()
                        stream.setFont(PDType1Font.HELVETICA, 12f)
                        stream.newLineAtOffset(72f, 720f)
                        text.split('\n').forEachIndexed { index, line ->
                            if (index > 0) stream.newLineAtOffset(0f, -16f)
                            stream.showText(line)
                        }
                        stream.endText()
                    }
                }
            }
            document.save(file)
        }
        return file
    }

    private class RecordingCloseable : Closeable {
        val closed = AtomicBoolean(false)
        override fun close() {
            closed.set(true)
        }
    }

}

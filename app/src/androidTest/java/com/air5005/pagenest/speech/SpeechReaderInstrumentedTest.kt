package com.air5005.pagenest.speech

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.air5005.pagenest.speech.content.LoadedSpeechPage
import com.air5005.pagenest.speech.content.PdfSpeechAvailability
import com.air5005.pagenest.speech.content.PdfSpeechContentSource
import com.air5005.pagenest.speech.content.PdfSpeechDocument
import com.air5005.pagenest.speech.content.ReflowableSpeechContentSource
import com.air5005.pagenest.speech.content.SpeechLineSnapshot
import com.air5005.pagenest.speech.content.SpeechPageNavigator
import com.air5005.pagenest.speech.content.SpeechPageSnapshot
import com.air5005.pagenest.speech.content.SpeechSegmenter
import com.air5005.pagenest.speech.engine.SpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import com.air5005.pagenest.speech.session.SpeechHighlightSink
import com.air5005.pagenest.speech.session.SpeechOptions
import com.air5005.pagenest.speech.session.SpeechSession
import com.air5005.pagenest.speech.ui.SpeechControlPolicy
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeechReaderInstrumentedTest {
    @Test
    fun userPageSeekCancelsOldGenerationAndMovesReflowableHighlight() = runTest {
        val navigator = FixtureNavigator(page(0, "old paragraph"), page(1, "target paragraph"))
        val source = ReflowableSpeechContentSource(7, navigator, SpeechSegmenter())
        val engine = ControllableEngine()
        val highlighted = mutableListOf<SpeechSegment>()
        val session = SpeechSession(
            engine = engine,
            progressCommitter = SpeechProgressCommitter { },
            highlightSink = SpeechHighlightSink { highlighted += it },
            ownerScope = this,
        )
        session.start(source, options())
        runCurrent()
        val oldGeneration = engine.requests.single().generationId

        session.seek(SpeechPosition(7, 2, 1, 0, 0))
        runCurrent()

        assertEquals(listOf("old paragraph", "target paragraph"), engine.requests.map { it.segment.text })
        assertTrue(engine.requests.last().generationId > oldGeneration)
        assertEquals("target paragraph", highlighted.last().text)
        assertEquals(1, navigator.activePage)
        assertTrue(engine.cancelledGenerations.contains(oldGeneration))
        session.closeAndJoin()
    }

    @Test
    fun textPdfAdvancesByPageAndScannedPdfUsesExactReaderMessage() = runTest {
        PDFBoxResourceLoader.init(ApplicationProvider.getApplicationContext())
        val readableDocument = PDDocument().apply {
            addTextPage("first PDF page")
            addTextPage("second PDF page")
        }
        val readable = PdfSpeechContentSource(11, PdfSpeechDocument(readableDocument), SpeechSegmenter())

        assertEquals(0, readable.current()?.position?.pageIndex)
        assertEquals(1, readable.next()?.position?.pageIndex)
        assertNull(readable.next())
        readable.close()

        val scannedDocument = PDDocument().apply {
            addPage(PDPage())
            addPage(PDPage())
        }
        val scanned = PdfSpeechContentSource(12, PdfSpeechDocument(scannedDocument), SpeechSegmenter())
        assertEquals(PdfSpeechAvailability.SCANNED, scanned.availability())
        assertNull(scanned.current())
        assertEquals(
            "此 PDF 为扫描版，暂不支持语音朗读",
            SpeechControlPolicy.messageFor(SpeechError.NoExtractableText),
        )
        scanned.close()
    }

    private fun PDDocument.addTextPage(text: String) {
        val page = PDPage()
        addPage(page)
        PDPageContentStream(this, page).use { stream ->
            stream.beginText()
            stream.setFont(PDType1Font.HELVETICA, 12f)
            stream.newLineAtOffset(72f, 720f)
            stream.showText(text)
            stream.endText()
        }
    }

    private class FixtureNavigator(private vararg val pages: SpeechPageSnapshot) : SpeechPageNavigator {
        var activePage = 0
        override suspend fun currentSpeechPage() = pages[activePage]
        override suspend fun nextSpeechPage() = pages.getOrNull(activePage + 1)?.also { activePage++ }
        override suspend fun previousSpeechPage() = pages.getOrNull(activePage - 1)?.also { activePage-- }
        override suspend fun loadSpeechPage(chapterIndex: Int, pageIndex: Int): LoadedSpeechPage? =
            pages.getOrNull(pageIndex)?.takeIf { it.chapterIndex == chapterIndex }?.let { snapshot ->
                object : LoadedSpeechPage { override val snapshot = snapshot }
            }

        override suspend fun activateSpeechPage(candidate: LoadedSpeechPage): Boolean {
            activePage = pages.indexOf(candidate.snapshot)
            return activePage >= 0
        }

        override fun close() = Unit
    }

    private class ControllableEngine : SpeechEngine {
        data class Pending(val request: SpeechRequest, val result: CompletableDeferred<SpeechEngineResult>)
        override val id = "fixture"
        val requests = mutableListOf<SpeechRequest>()
        val cancelledGenerations = mutableListOf<Long>()
        private val pending = mutableListOf<Pending>()

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()
        override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
            requests += request
            val call = Pending(request, CompletableDeferred())
            pending += call
            return try {
                withContext(NonCancellable) { call.result.await() }
            } finally {
                if (!call.result.isCompleted) cancelledGenerations += request.generationId
            }
        }

        override suspend fun stop() {
            pending.lastOrNull { !it.result.isCompleted }?.let {
                cancelledGenerations += it.request.generationId
                it.result.complete(SpeechEngineResult.Cancelled)
            }
        }

        override fun close() = Unit
    }

    private companion object {
        fun options() = SpeechOptions(SpeechMode.OFFLINE, "zh-CN", null, 1f, 1f)
        fun page(index: Int, text: String) = SpeechPageSnapshot(
            chapterIndex = 2,
            pageIndex = index,
            progression = index / 2.0,
            lines = listOf(SpeechLineSnapshot(0, text, 0, text.length, false, false)),
        )
    }
}

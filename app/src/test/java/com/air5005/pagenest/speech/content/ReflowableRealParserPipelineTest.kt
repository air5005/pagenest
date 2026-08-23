package com.air5005.pagenest.speech.content

import com.air5005.pagenest.library.importing.SupportedBookFormat
import com.air5005.pagenest.speech.model.SpeechPosition
import com.wxn.mobi.EpubParser
import com.wxn.mobi.MobiParser
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReflowableRealParserPipelineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        unmockkObject(EpubParser, MobiParser)
        Dispatchers.resetMain()
    }

    @Test
    fun `real EPUB parser pipeline exposes a stable controller speech location`() = runTest {
        val pipeline = RealParserSpeechHarness(temporaryFolder, testScheduler)
            .open(SupportedBookFormat.EPUB)

        val segment = pipeline.source.current()

        assertEquals("EPUB literal body", segment?.text)
        assertEquals(SpeechPosition(201, 0, 0, 0, 0), segment?.position)
        verify(exactly = 1) {
            EpubParser.getEpubChapter(any(), 201, pipeline.file.absolutePath)
        }
        verify(exactly = 1) {
            EpubParser.getEpubChapterData(
                any(),
                pipeline.file.absolutePath,
                match { it.bookId == 201L && it.chapterIndex == 0 && it.chaptersSize == 2 },
            )
        }
        pipeline.source.close()
    }

    @Test
    fun `real TXT parser pipeline reads the fixture and exposes a stable controller speech location`() = runTest {
        val pipeline = RealParserSpeechHarness(temporaryFolder, testScheduler)
            .open(SupportedBookFormat.TXT)

        val title = pipeline.source.current()
        val body = pipeline.source.next()

        assertEquals("TXT literal title", title?.text)
        assertEquals(SpeechPosition(202, 0, 0, 0, 0), title?.position)
        assertEquals("TXT literal body", body?.text)
        assertEquals(1, body?.position?.paragraphIndex)
        pipeline.source.close()
    }

    @Test
    fun `real MOBI parser pipeline exposes a stable controller speech location`() = runTest {
        val pipeline = RealParserSpeechHarness(temporaryFolder, testScheduler)
            .open(SupportedBookFormat.MOBI)

        val segment = pipeline.source.current()

        assertEquals("MOBI literal body", segment?.text)
        assertEquals(SpeechPosition(203, 0, 0, 0, 0), segment?.position)
        verify(exactly = 1) {
            MobiParser.getMobiChapter(any(), 203, pipeline.file.absolutePath)
        }
        verify(exactly = 1) {
            MobiParser.getMobiChapterData(
                any(),
                pipeline.file.absolutePath,
                match { it.bookId == 203L && it.chapterIndex == 0 && it.chaptersSize == 2 },
            )
        }
        pipeline.source.close()
    }

    @Test
    fun `real AZW3 dispatch uses the MOBI parser pipeline with a stable controller speech location`() = runTest {
        val pipeline = RealParserSpeechHarness(temporaryFolder, testScheduler)
            .open(SupportedBookFormat.AZW3)

        val segment = pipeline.source.current()

        assertEquals("AZW3 literal body", segment?.text)
        assertEquals(SpeechPosition(204, 0, 0, 0, 0), segment?.position)
        verify(exactly = 1) {
            MobiParser.getMobiChapter(any(), 204, pipeline.file.absolutePath)
        }
        verify(exactly = 1) {
            MobiParser.getMobiChapterData(
                any(),
                pipeline.file.absolutePath,
                match { it.bookId == 204L && it.chapterIndex == 0 && it.chaptersSize == 2 },
            )
        }
        pipeline.source.close()
    }

    @Test
    fun `real EPUB pipeline crosses a rendered page and seeks an unloaded chapter`() = runTest {
        val pipeline = RealParserSpeechHarness(temporaryFolder, testScheduler)
            .openEpubNavigationFixture()

        val firstPage = pipeline.source.current()
        val secondPage = pipeline.source.next()
        val sought = pipeline.source.seek(SpeechPosition(205, 1, 0, 0, 0))

        assertEquals("EPUB page zero", firstPage?.text)
        assertEquals(SpeechPosition(205, 0, 0, 0, 0), firstPage?.position)
        assertEquals("EPUB page one", secondPage?.text)
        assertEquals(SpeechPosition(205, 0, 1, 2, 0), secondPage?.position)
        assertEquals("EPUB unloaded target", sought?.text)
        assertEquals(SpeechPosition(205, 1, 0, 0, 0), sought?.position)
        pipeline.source.close()
    }
}

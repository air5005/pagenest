package com.air5005.pagenest.speech.session

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import com.wxn.bookread.data.model.TextChapter
import com.wxn.bookread.data.model.TextLine
import com.wxn.bookread.data.model.TextPage
import com.wxn.bookread.ui.PageCallback
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageViewControllerSpeechHighlightTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `speech highlight marks only overlapping reader lines and clear preserves selection state`() = runTest {
        val first = line(paragraph = 4, start = 0, end = 5)
        val second = line(paragraph = 4, start = 5, end = 10)
        val unrelated = line(paragraph = 5, start = 0, end = 8).apply {
            textChars += com.wxn.bookread.data.model.TextChar("x", start = 0f, end = 1f, selected = true)
        }
        val callback = mockk<PageCallback>(relaxed = true)
        val controller = controller(TextPage(index = 2, textLines = arrayListOf(first, second, unrelated)))
        controller.callBack = callback

        val sink = controller.speechHighlightSink()
        sink.show(segment(start = 2, end = 7))

        assertTrue(first.isReadAloud)
        assertTrue(second.isReadAloud)
        assertFalse(unrelated.isReadAloud)
        assertTrue(unrelated.textChars.single().selected)
        verify { callback.upContent(resetPageOffset = false) }

        sink.clear()

        assertFalse(first.isReadAloud)
        assertFalse(second.isReadAloud)
        assertFalse(unrelated.isReadAloud)
        assertTrue(unrelated.textChars.single().selected)
    }

    private fun controller(page: TextPage): PageViewController = PageViewController(
        context = mockk(relaxed = true),
        getChapterByIdUserCase = mockk(relaxed = true),
        getChapterCountByBookIdUserCase = mockk(relaxed = true),
        getAnnotationsUseCase = mockk(relaxed = true),
        getNotesForBookUseCase = mockk(relaxed = true),
        getBookmarksForBookUseCase = mockk(relaxed = true),
        updateChapterWordCountUserCase = mockk(relaxed = true),
        updateBookUseCase = mockk(relaxed = true),
        appPreferencesUtil = mockk(relaxed = true),
        textParser = mockk(relaxed = true),
    ).apply {
        durChapterIndex = 1
        durPageIndex = 0
        curTextChapter = TextChapter(
            position = 1,
            title = "Chapter",
            chapterId = 1,
            pages = listOf(page),
            pageLines = listOf(page.textLines.size),
            pageLengths = listOf(10),
            chaptersSize = 2,
        )
    }

    private fun line(paragraph: Int, start: Int, end: Int) = TextLine(
        text = "x".repeat(end - start),
        paragraphIndex = paragraph,
        charStartOffset = start,
        charEndOffset = end,
    )

    private fun segment(start: Int, end: Int) = SpeechSegment(
        id = "highlight",
        position = SpeechPosition(7, 1, 2, 4, 0),
        partIndex = 0,
        text = "range",
        locator = Locator(
            chapterIndex = 1,
            startParagraphIndex = 4,
            startTextOffset = start,
            endParagraphIndex = 4,
            endTextOffset = end,
            text = "range",
            progression = 0.25,
        ),
    )
}

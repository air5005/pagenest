package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechPosition
import com.wxn.base.bean.Book
import com.wxn.base.bean.BookChapter
import com.wxn.base.bean.ReaderText
import com.wxn.bookread.data.model.TextChapter
import com.wxn.bookread.data.model.TextLine
import com.wxn.bookread.data.model.TextPage
import com.wxn.bookread.provider.ChapterProvider
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class ReflowableSpeechContentSourceTest {
    @Test
    fun `reflowable source groups lines by paragraph and crosses chapter`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(
                chapter = 0,
                page = 0,
                line(paragraph = 4, offset = 0, text = "第一行"),
                line(paragraph = 4, offset = 3, text = "第二行"),
            ),
            page(chapter = 1, page = 0, line(paragraph = 0, offset = 0, text = "下一章")),
        )
        val source = ReflowableSpeechContentSource(7, pages, SpeechSegmenter())

        assertEquals("第一行第二行", source.current()?.text)
        assertEquals("下一章", source.next()?.text)
        assertNull(source.next())
        assertEquals("第一行第二行", source.previous()?.text)
    }

    @Test
    fun `image and rule lines never become speech`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(
                chapter = 2,
                page = 3,
                line(paragraph = 0, offset = 0, text = "cover.jpg", image = true),
                line(paragraph = 1, offset = 0, text = "-----", rule = true),
                line(paragraph = 2, offset = 8, text = "  Read me  "),
            ),
        )
        val source = ReflowableSpeechContentSource(11, pages, SpeechSegmenter())

        val segment = source.current()

        assertEquals("Read me", segment?.text)
        assertEquals(10, segment?.locator?.startTextOffset)
        assertNull(source.next())
    }

    @Test
    fun `same paragraph on adjacent pages keeps each visible fragment stable`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(chapter = 3, page = 4, line(paragraph = 7, offset = 0, text = "page tail")),
            page(chapter = 3, page = 5, line(paragraph = 7, offset = 9, text = "page head")),
        )
        val source = ReflowableSpeechContentSource(13, pages, SpeechSegmenter())

        val first = source.current()!!
        val second = source.next()!!

        assertEquals("page tail", first.text)
        assertEquals(SpeechPosition(13, 3, 4, 7, 0), first.position)
        assertEquals("page head", second.text)
        assertEquals(SpeechPosition(13, 3, 5, 7, 9), second.position)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `seek selects the split child containing the requested UTF-16 offset`() = runTest {
        val longText = "a".repeat(500) + "😀" + "b"
        val pages = FakeSpeechPageNavigator(
            page(chapter = 4, page = 2, line(paragraph = 6, offset = 20, text = longText)),
        )
        val source = ReflowableSpeechContentSource(17, pages, SpeechSegmenter())

        val selected = source.seek(SpeechPosition(17, 4, 2, 6, 522))

        assertEquals("😀b", selected?.text)
        assertEquals(520, selected?.locator?.startTextOffset)
    }

    private class FakeSpeechPageNavigator(
        vararg pages: SpeechPageSnapshot,
    ) : SpeechPageNavigator {
        private val values = pages.toList()
        private var index = 0

        override suspend fun currentSpeechPage(): SpeechPageSnapshot? = values.getOrNull(index)

        override suspend fun nextSpeechPage(): SpeechPageSnapshot? {
            if (index >= values.lastIndex) return null
            index++
            return values[index]
        }

        override suspend fun previousSpeechPage(): SpeechPageSnapshot? {
            if (index <= 0) return null
            index--
            return values[index]
        }

        override suspend fun previewSpeechPage(chapterIndex: Int, pageIndex: Int): SpeechPageSnapshot? =
            values.firstOrNull { it.chapterIndex == chapterIndex && it.pageIndex == pageIndex }

        override suspend fun seekSpeechPage(chapterIndex: Int, pageIndex: Int): SpeechPageSnapshot? {
            val target = values.indexOfFirst {
                it.chapterIndex == chapterIndex && it.pageIndex == pageIndex
            }
            if (target < 0) return null
            index = target
            return values[index]
        }

        override fun close() = Unit
    }

    companion object {
        private fun line(
            paragraph: Int,
            offset: Int,
            text: String,
            image: Boolean = false,
            rule: Boolean = false,
        ) = SpeechLineSnapshot(
            paragraphIndex = paragraph,
            text = text,
            charStartOffset = offset,
            charEndOffset = offset + text.length,
            isImage = image,
            isLine = rule,
        )

        private fun page(
            chapter: Int,
            page: Int,
            vararg lines: SpeechLineSnapshot,
        ) = SpeechPageSnapshot(
            chapterIndex = chapter,
            pageIndex = page,
            progression = (chapter * 10 + page) / 100.0,
            lines = lines.toList(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageViewControllerSpeechSnapshotTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        unmockkObject(ChapterProvider)
        Dispatchers.resetMain()
    }

    @Test
    fun `controller snapshot is immutable after parser TextLine mutation`() = runTest {
        val textLine = TextLine(
            text = "immutable",
            paragraphIndex = 2,
            charStartOffset = 4,
            charEndOffset = 13,
        )
        val controller = controllerWith(TextPage(index = 6, textLines = arrayListOf(textLine)))

        val snapshot = controller.currentSpeechPage()
        textLine.text = "mutated"
        textLine.paragraphIndex = 99

        assertEquals("immutable", snapshot?.lines?.single()?.text)
        assertEquals(2, snapshot?.lines?.single()?.paragraphIndex)
        assertEquals(6, snapshot?.pageIndex)
    }

    @Test
    fun `next speech page awaits an unloaded chapter before returning its first page`() = runTest {
        val parsed = CompletableDeferred<Unit>()
        val target = textChapter(1, TextPage(index = 0, textLines = arrayListOf(textLine("next"))))
        val controller = controllerWith(TextPage(index = 0, textLines = arrayListOf(textLine("current"))))
        controller.durChapterIndex = 0
        controller.curTextChapter = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
        )
        controller.chapterSize = 2
        controller.book = book()
        every { controller.getChapterByIdUserCase(1, 1) } returns flowOf(bookChapter(1))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            parsed.await()
            listOf(ReaderText.Text(line = "next"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns target

        val moving = async { controller.nextSpeechPage() }
        testScheduler.runCurrent()

        assertTrue("navigation must wait for parsing", !moving.isCompleted)
        parsed.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("next", moving.await()?.lines?.single()?.text)
        assertEquals(1, controller.durChapterIndex)
        assertEquals(target, controller.curTextChapter)
    }

    @Test
    fun `speech seek loads a valid unloaded chapter before selecting its page`() = runTest {
        val target = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("first"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("sought"))),
        )
        val controller = controllerWith(TextPage(index = 0, textLines = arrayListOf(textLine("current"))))
        controller.chapterSize = 3
        controller.book = book()
        every { controller.getChapterByIdUserCase(1, 2) } returns flowOf(bookChapter(2))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } returns
            listOf(ReaderText.Text(line = "first sought"))
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns target

        val snapshot = controller.seekSpeechPage(chapterIndex = 2, pageIndex = 1)

        assertEquals("sought", snapshot?.lines?.single()?.text)
        assertEquals(2, controller.durChapterIndex)
        assertEquals(1, controller.durPageIndex)
    }

    @Test
    fun `invalid locator on an existing page leaves real controller and source position unchanged`() = runTest {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("next"))),
        )
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val current = requireNotNull(source.current())
        val before = requireNotNull(controller.currentSpeechPage())

        val result = source.seek(SpeechPosition(1, 3, 1, paragraphIndex = 99, textOffset = 50))

        assertNull(result)
        assertEquals(3, controller.durChapterIndex)
        assertEquals(0, controller.durPageIndex)
        assertEquals(before, controller.currentSpeechPage())
        assertEquals(current, source.current())
        assertEquals("next", source.next()?.text)
        assertNull(source.next())
    }

    @Test
    fun `valid locator on an existing page commits controller and source position once`() = runTest {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("target"))),
        )
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        requireNotNull(source.current())

        val result = source.seek(SpeechPosition(1, 3, 1, paragraphIndex = 0, textOffset = 0))

        assertEquals("target", result?.text)
        assertEquals(3, controller.durChapterIndex)
        assertEquals(1, controller.durPageIndex)
        assertEquals(result, source.current())
    }

    private fun controllerWith(vararg pages: TextPage): PageViewController {
        val controller = PageViewController(
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
        )
        controller.durChapterIndex = 3
        controller.durPageIndex = 0
        controller.chapterSize = 4
        controller.curTextChapter = TextChapter(
            position = 3,
            title = "Chapter",
            chapterId = 1,
            pages = pages.toList(),
            pageLines = pages.map { it.textLines.size },
            pageLengths = pages.map { it.text.length },
            chaptersSize = 1,
        )
        return controller
    }

    private fun textLine(text: String) = TextLine(
        text = text,
        paragraphIndex = 0,
        charStartOffset = 0,
        charEndOffset = text.length,
    )

    private fun textChapter(position: Int, vararg pages: TextPage) = TextChapter(
        position = position,
        title = "Chapter $position",
        chapterId = position.toLong(),
        pages = pages.toList(),
        pageLines = pages.map { it.textLines.size },
        pageLengths = pages.map { it.text.length },
        chaptersSize = 3,
    )

    private fun bookChapter(index: Int) = BookChapter(
        bookId = 1,
        chapterIndex = index,
        chapterName = "Chapter $index",
        chaptersSize = 3,
    )

    private fun book() = Book(
        id = 1,
        title = "Fixture",
        author = "Author",
        description = null,
        filePath = "file:///fixture.epub",
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 0f,
        lastOpened = null,
        category = null,
        fileType = "epub",
    )
}

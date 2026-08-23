package com.air5005.pagenest.speech.content

import com.air5005.pagenest.library.importing.SupportedBookFormat
import com.air5005.pagenest.speech.model.SpeechPosition
import com.wxn.bookread.data.model.TextChapter
import com.wxn.bookread.data.model.TextLine
import com.wxn.bookread.data.model.TextPage
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.junit.runners.Parameterized

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

@RunWith(Parameterized::class)
class ReflowableFormatSpeechAdapterTest(
    private val format: SupportedBookFormat,
    private val parserText: String,
    private val expectedId: String,
) {
    @Test
    fun `parser page snapshots have stable speech locations for every reflowable format`() = runTest {
        val snapshot = SpeechPageSnapshot(
            chapterIndex = 5,
            pageIndex = 8,
            progression = 0.58,
            lines = listOf(
                SpeechLineSnapshot(9, parserText, 12, 12 + parserText.length, false, false),
            ),
        )
        val source = ReflowableSpeechContentSource(
            bookId = 23,
            navigator = SinglePageNavigator(snapshot),
            segmenter = SpeechSegmenter(),
        )

        val segment = source.current()!!

        assertEquals("${format.extension} body", segment.text)
        assertEquals(SpeechPosition(23, 5, 8, 9, 12), segment.position)
        assertEquals(0.58, segment.locator.progression, 0.0)
        assertEquals(expectedId, segment.id)
    }

    private class SinglePageNavigator(
        private val page: SpeechPageSnapshot,
    ) : SpeechPageNavigator {
        override suspend fun currentSpeechPage() = page
        override suspend fun nextSpeechPage(): SpeechPageSnapshot? = null
        override suspend fun previousSpeechPage(): SpeechPageSnapshot? = null
        override suspend fun seekSpeechPage(chapterIndex: Int, pageIndex: Int) =
            page.takeIf { it.chapterIndex == chapterIndex && it.pageIndex == pageIndex }
        override fun close() = Unit
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun formats(): List<Array<Any>> = listOf(
            arrayOf(SupportedBookFormat.EPUB, "epub body", "23:5:8:9:12:0:12:21"),
            arrayOf(SupportedBookFormat.TXT, "txt body", "23:5:8:9:12:0:12:20"),
            arrayOf(SupportedBookFormat.MOBI, "mobi body", "23:5:8:9:12:0:12:21"),
            arrayOf(SupportedBookFormat.AZW3, "azw3 body", "23:5:8:9:12:0:12:21"),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PageViewControllerSpeechSnapshotTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
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

    private fun controllerWith(page: TextPage): PageViewController {
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
        controller.curTextChapter = TextChapter(
            position = 3,
            title = "Chapter",
            chapterId = 1,
            pages = listOf(page),
            pageLines = listOf(1),
            pageLengths = listOf(page.text.length),
            chaptersSize = 1,
        )
        return controller
    }
}

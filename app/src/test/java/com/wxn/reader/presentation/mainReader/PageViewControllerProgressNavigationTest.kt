package com.wxn.reader.presentation.mainReader

import com.wxn.bookparser.TextParser
import com.wxn.bookread.data.model.TextChapter
import com.wxn.reader.data.source.local.AppPreferencesUtil
import com.wxn.reader.domain.use_case.annotations.GetAnnotationsUseCase
import com.wxn.reader.domain.use_case.bookmarks.GetBookmarksForBookUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.chapters.GetChapterByIdUserCase
import com.wxn.reader.domain.use_case.chapters.GetChapterCountByBookIdUserCase
import com.wxn.reader.domain.use_case.chapters.UpdateChapterWordCountUserCase
import com.wxn.reader.domain.use_case.notes.GetNotesForBookUseCase
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PageViewControllerProgressNavigationTest {
    @Test
    fun `rejected progress jump preserves chapter and page indices`() {
        val controller = controller().apply {
            durChapterIndex = 3
            durPageIndex = 7
            curTextChapter = null
        }

        assertFalse(controller.changeChapter(newChapterIndex = 8, newProgress = 0.8))
        assertEquals(3, controller.durChapterIndex)
        assertEquals(7, controller.durPageIndex)
    }

    @Test
    fun `missing word counts preserve chapter and page indices`() {
        val controller = controller().apply {
            durChapterIndex = 3
            durPageIndex = 7
            curTextChapter = TextChapter(
                position = 3,
                title = "Chapter",
                chapterId = 3,
                totalWordCount = 0,
                wordCount = 0,
                pages = emptyList(),
                pageLines = emptyList(),
                pageLengths = emptyList(),
                chaptersSize = 9,
            )
        }

        assertFalse(controller.changeChapter(newChapterIndex = 8, newProgress = 0.8))
        assertEquals(3, controller.durChapterIndex)
        assertEquals(7, controller.durPageIndex)
    }

    private fun controller() = PageViewController(
        context = mockk(relaxed = true),
        getChapterByIdUserCase = mockk<GetChapterByIdUserCase>(relaxed = true),
        getChapterCountByBookIdUserCase = mockk<GetChapterCountByBookIdUserCase>(relaxed = true),
        getAnnotationsUseCase = mockk<GetAnnotationsUseCase>(relaxed = true),
        getNotesForBookUseCase = mockk<GetNotesForBookUseCase>(relaxed = true),
        getBookmarksForBookUseCase = mockk<GetBookmarksForBookUseCase>(relaxed = true),
        updateChapterWordCountUserCase = mockk<UpdateChapterWordCountUserCase>(relaxed = true),
        updateBookUseCase = mockk<UpdateBookUseCase>(relaxed = true),
        appPreferencesUtil = mockk<AppPreferencesUtil>(relaxed = true),
        textParser = mockk<TextParser>(relaxed = true),
    )
}

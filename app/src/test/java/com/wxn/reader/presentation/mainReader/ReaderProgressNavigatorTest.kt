package com.wxn.reader.presentation.mainReader

import com.wxn.base.bean.BookChapter
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressNavigatorTest {
    private val chapters = listOf(
        chapter(index = 0, progress = 0.0f),
        chapter(index = 1, progress = 0.5f),
    )

    @Test
    fun `missing target rejects navigation without invoking controller`() {
        var invoked = false

        val accepted = ReaderProgressNavigator.navigate(
            newProgress = -0.1,
            chapters = chapters,
        ) { _, _ ->
            invoked = true
            true
        }

        assertFalse(accepted)
        assertFalse(invoked)
    }

    @Test
    fun `controller rejection is returned to the progress panel`() {
        val accepted = ReaderProgressNavigator.navigate(
            newProgress = 0.75,
            chapters = chapters,
        ) { chapterIndex, progress ->
            chapterIndex == 0 && progress == 0.75
        }

        assertFalse(accepted)
    }

    @Test
    fun `matching chapter delegates exactly once and returns success`() {
        var calls = 0

        val accepted = ReaderProgressNavigator.navigate(
            newProgress = 0.75,
            chapters = chapters,
        ) { chapterIndex, progress ->
            calls++
            chapterIndex == 1 && progress == 0.75
        }

        assertTrue(accepted)
        assertTrue(calls == 1)
    }

    private fun chapter(index: Int, progress: Float) = BookChapter(
        bookId = 1,
        chapterIndex = index,
        chapterName = "Chapter $index",
        chapterProgress = progress,
    )
}

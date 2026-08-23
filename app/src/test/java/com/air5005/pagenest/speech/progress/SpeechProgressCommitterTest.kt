package com.air5005.pagenest.speech.progress

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Book
import com.wxn.base.bean.Locator
import com.wxn.reader.domain.repository.BooksRepository
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.reading_progress.SetReadingProgressUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechProgressCommitterTest {
    @Test
    fun `reflowable completion delegates locator JSON through the actual reading progress use case`() = runTest {
        val repository = mockk<BooksRepository>(relaxed = true)
        coEvery { repository.getBookById(41) } returns book(fileType = "epub")
        val committer = committer(repository)
        val completed = segment(bookId = 41, page = 3, progression = 0.42)

        committer.commitCompleted(completed)

        coVerify(exactly = 1) {
            repository.setReadingProgress(41, completed.locator.toJsonString(), 0f)
        }
        coVerify(exactly = 0) { repository.updateBook(any()) }
    }

    @Test
    fun `PDF completion delegates the completed page through the existing update book path`() = runTest {
        val repository = mockk<BooksRepository>(relaxed = true)
        coEvery { repository.getBookById(42) } returns book(id = 42, fileType = "pdf")
        val updated = slot<Book>()
        coEvery { repository.updateBook(capture(updated)) } returns Unit
        val committer = committer(repository)

        committer.commitCompleted(segment(bookId = 42, page = 6, progression = 0.6))

        assertEquals("6", updated.captured.locator)
        assertEquals(60f, updated.captured.progress)
        coVerify(exactly = 1) { repository.updateBook(any()) }
        coVerify(exactly = 0) { repository.setReadingProgress(any(), any(), any()) }
    }

    @Test
    fun `PDF completion saves the last fully completed page rather than the next page`() = runTest {
        val repository = mockk<BooksRepository>(relaxed = true)
        coEvery { repository.getBookById(43) } returns book(id = 43, fileType = "PDF")
        val updated = slot<Book>()
        coEvery { repository.updateBook(capture(updated)) } returns Unit

        committer(repository).commitCompleted(segment(bookId = 43, page = 0, progression = 0.0))

        assertEquals("0", updated.captured.locator)
        assertEquals(0f, updated.captured.progress)
    }

    private fun committer(repository: BooksRepository) = RoomSpeechProgressCommitter(
        getBookByIdUseCase = GetBookByIdUseCase(repository),
        setReadingProgressUseCase = SetReadingProgressUseCase(repository),
        updateBookUseCase = UpdateBookUseCase(repository),
    )

    private fun segment(bookId: Long, page: Int, progression: Double) = SpeechSegment(
        id = "completed-$page",
        position = SpeechPosition(bookId, 0, page, 0, 0),
        partIndex = 0,
        text = "page $page",
        locator = Locator(
            id = "completed-$page",
            chapterIndex = 0,
            startParagraphIndex = 0,
            startTextOffset = 0,
            endParagraphIndex = 0,
            endTextOffset = 6,
            text = "page $page",
            progression = progression,
        ),
    )

    private fun book(id: Long = 41, fileType: String) = Book(
        id = id,
        title = "Fixture",
        author = "Author",
        description = null,
        filePath = "file:///fixture.$fileType",
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 10f,
        lastOpened = null,
        category = null,
        fileType = fileType,
        locator = "old",
    )
}

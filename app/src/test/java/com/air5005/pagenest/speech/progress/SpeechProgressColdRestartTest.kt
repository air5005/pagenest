package com.air5005.pagenest.speech.progress

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.air5005.pagenest.speech.content.ReflowableSpeechContentSource
import com.air5005.pagenest.speech.content.SpeechSegmenter
import com.wxn.base.bean.Book
import com.wxn.base.bean.Locator
import com.wxn.bookread.data.model.TextChapter
import com.wxn.bookread.data.model.TextLine
import com.wxn.bookread.data.model.TextPage
import com.wxn.reader.data.mapper.annotation.BookAnnotationMapperImpl
import com.wxn.reader.data.mapper.book.BookMapperImpl
import com.wxn.reader.data.mapper.bookmark.BookmarkMapperImpl
import com.wxn.reader.data.mapper.bookshelf.BookShelfMapperImpl
import com.wxn.reader.data.mapper.note.NoteMapperImpl
import com.wxn.reader.data.mapper.readingactive.ReadingActiveMapperImpl
import com.wxn.reader.data.mapper.shelf.ShelfMapperImpl
import com.wxn.reader.data.repository.BooksRepositoryImpl
import com.wxn.reader.data.source.local.AppDatabase
import com.wxn.reader.domain.use_case.annotations.GetAnnotationsUseCase
import com.wxn.reader.domain.use_case.bookmarks.GetBookmarksForBookUseCase
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.chapters.GetChapterCountByBookIdUserCase
import com.wxn.reader.domain.use_case.notes.GetNotesForBookUseCase
import com.wxn.reader.domain.use_case.reading_progress.SetReadingProgressUseCase
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechProgressColdRestartTest {
    private lateinit var database: AppDatabase
    private lateinit var repository: BooksRepositoryImpl
    private val bookMapper = BookMapperImpl()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = BooksRepositoryImpl(
            appDb = database,
            bookDao = database.bookDao(),
            annotationDao = database.annotationDao(),
            noteDao = database.noteDao(),
            bookmarkDao = database.bookmarkDao(),
            readingActivityDao = database.readingActivityDao(),
            bookMapper = bookMapper,
            annotaionMapper = BookAnnotationMapperImpl(),
            bookmarkMapper = BookmarkMapperImpl(),
            noteMapper = NoteMapperImpl(),
            readingActiveMapper = ReadingActiveMapperImpl(),
            shelfMapper = ShelfMapperImpl(),
            bookShelfMapper = BookShelfMapperImpl(),
        )
    }

    @After
    fun tearDown() {
        database.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `cold restart restores only the last completed speech locator progression and page`() = runTest {
        val initial = book(scrollChapter = 0, scrollPage = 0, locator = "old", progress = 3f)
        database.bookDao().insertBook(bookMapper.toBookEntity(initial))
        val opened = requireNotNull(repository.getBookById(BOOK_ID))
        val controller = spyk(controller(opened), recordPrivateCalls = true)
        controller.durChapterIndex = 2
        controller.durPageIndex = 0
        controller.chapterSize = 3
        controller.curTextChapter = chapter()
        val source = ReflowableSpeechContentSource(BOOK_ID, controller, SpeechSegmenter())
        val committer = RoomSpeechProgressCommitter(
            getBookByIdUseCase = GetBookByIdUseCase(repository),
            setReadingProgressUseCase = SetReadingProgressUseCase(repository),
            updateBookUseCase = UpdateBookUseCase(repository),
        )

        val completed = requireNotNull(source.current())
        committer.commitCompleted(completed)
        val startedButNotCompleted = requireNotNull(source.next())
        assertEquals(1, startedButNotCompleted.position.pageIndex)
        verify(exactly = 0) { controller["saveRead"]() }

        val afterProcessDeath = requireNotNull(repository.getBookById(BOOK_ID))
        val restoredLocator = requireNotNull(Locator.fromJsonString(afterProcessDeath.locator))
        assertEquals(completed.locator, restoredLocator)
        assertEquals(40f, afterProcessDeath.progress)
        assertEquals(2, afterProcessDeath.scrollIndex)
        assertEquals(0, afterProcessDeath.scrollOffset)

        val restartedController = controller(afterProcessDeath)
        restartedController.resetBook(afterProcessDeath) {}
        assertEquals(2, restartedController.durChapterIndex)
        assertEquals(0, restartedController.durPageIndex)
    }

    private fun controller(openedBook: Book): PageViewController {
        val chapterCount = mockk<GetChapterCountByBookIdUserCase>()
        every { chapterCount(BOOK_ID) } returns flowOf(3)
        return PageViewController(
            context = ApplicationProvider.getApplicationContext(),
            getChapterByIdUserCase = mockk(relaxed = true),
            getChapterCountByBookIdUserCase = chapterCount,
            getAnnotationsUseCase = GetAnnotationsUseCase(repository),
            getNotesForBookUseCase = GetNotesForBookUseCase(repository),
            getBookmarksForBookUseCase = GetBookmarksForBookUseCase(repository),
            updateChapterWordCountUserCase = mockk(relaxed = true),
            updateBookUseCase = UpdateBookUseCase(repository),
            appPreferencesUtil = mockk(relaxed = true),
            textParser = mockk(relaxed = true),
        ).apply {
            book = openedBook
        }
    }

    private fun chapter() = TextChapter(
        position = 2,
        title = "Chapter 2",
        chapterId = 2,
        pages = listOf(page(0, "completed"), page(1, "started")),
        pageLines = listOf(1, 1),
        pageLengths = listOf(9, 7),
        chaptersSize = 3,
    ).apply {
        chapterProgress = 0.4f
        wordCount = 100
        totalWordCount = 100
    }

    private fun page(index: Int, text: String) = TextPage(
        index = index,
        textLines = arrayListOf(
            TextLine(
                text = text,
                paragraphIndex = index,
                charStartOffset = 0,
                charEndOffset = text.length,
            ),
        ),
    )

    private fun book(
        scrollChapter: Int,
        scrollPage: Int,
        locator: String,
        progress: Float,
    ) = Book(
        id = BOOK_ID,
        title = "Fixture",
        author = "Author",
        description = null,
        filePath = "file:///fixture.epub",
        coverImage = null,
        scrollIndex = scrollChapter,
        scrollOffset = scrollPage,
        progress = progress,
        lastOpened = null,
        category = null,
        fileType = "epub",
        locator = locator,
    )

    private companion object {
        const val BOOK_ID = 71L
    }
}

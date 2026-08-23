package com.air5005.pagenest.speech.progress

import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.reading_progress.SetReadingProgressUseCase
import javax.inject.Inject

class RoomSpeechProgressCommitter @Inject constructor(
    private val getBookByIdUseCase: GetBookByIdUseCase,
    private val setReadingProgressUseCase: SetReadingProgressUseCase,
    private val updateBookUseCase: UpdateBookUseCase,
) : SpeechProgressCommitter {
    override suspend fun commitCompleted(segment: SpeechSegment) {
        val book = getBookByIdUseCase(segment.position.bookId) ?: return
        if (book.fileType.equals(PDF_FILE_TYPE, ignoreCase = true)) {
            if (!segment.completesPage) return
            val completedPage = segment.position.pageIndex ?: return
            updateBookUseCase(
                book.copy(
                    locator = completedPage.toString(),
                    progress = (segment.locator.progression * 100.0).toFloat(),
                ),
            )
        } else {
            setReadingProgressUseCase(
                bookId = segment.position.bookId,
                locator = segment.locator.toJsonString(),
                scrollIndex = segment.position.chapterIndex,
                scrollOffset = segment.position.pageIndex,
            )
        }
    }

    private companion object {
        const val PDF_FILE_TYPE = "pdf"
    }
}

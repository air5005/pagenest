package com.wxn.reader.domain.use_case.reading_progress

import com.wxn.reader.data.dto.ReadingStatus
import com.wxn.reader.domain.repository.BooksRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

class SetReadingProgressUseCase @Inject constructor(private val repository: BooksRepository) {
    suspend operator fun invoke(
        bookId: Long,
        locator: String,
        scrollIndex: Int? = null,
        scrollOffset: Int? = null,
    ) = withContext(Dispatchers.IO) {
        val progression = getProgressionFromLocator(locator)


        updateReadingStatus(bookId, progression)

        repository.setReadingProgress(bookId, locator, progression, scrollIndex, scrollOffset)

    }

    private suspend fun updateReadingStatus(bookId: Long, progression: Float) {
        when {
            progression >= 99f -> {
                repository.setReadingStatus(bookId, ReadingStatus.FINISHED)
            }
            progression > 2f -> repository.setReadingStatus(bookId, ReadingStatus.IN_PROGRESS)
        }
    }

    private fun getProgressionFromLocator(locatorJson: String): Float {
        return try {
            val locator = JSONObject(locatorJson)
            val progression = locator.optJSONObject("locations")
                ?.takeIf { it.has("totalProgression") }
                ?.optDouble("totalProgression")
                ?: locator.optDouble("progression", 0.0)
            progression.toFloat() * 100f
        } catch (e: Exception) {
            0f
        }
    }
}

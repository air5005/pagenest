package com.wxn.reader.presentation.home.dashboard

import com.wxn.base.bean.Book
import com.wxn.reader.data.dto.FileType
import com.wxn.reader.data.dto.FileType.Companion.stringToFileType
import com.wxn.reader.domain.model.ReadingActive
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

class HomeDashboardCalculator {
    fun calculate(
        books: List<Book>,
        activities: List<ReadingActive>,
        now: Instant,
        zoneId: ZoneId,
        weeklyGoalMinutes: Int = DEFAULT_WEEKLY_GOAL_MINUTES,
    ): HomeDashboardModel {
        val today = now.atZone(zoneId).toLocalDate()
        val readingMillisByDate = activities
            .groupBy { Instant.ofEpochMilli(it.date).atZone(zoneId).toLocalDate() }
            .mapValues { (_, dailyActivities) -> dailyActivities.sumOf { it.readingTime.coerceAtLeast(0) } }
        val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val todayMinutes = readingMillisByDate[today].orZero().toMinutes()
        val weekMinutes = readingMillisByDate
            .filterKeys { date -> date in weekStart..today }
            .values
            .sum()
            .toMinutes()

        return HomeDashboardModel(
            todayMinutes = todayMinutes,
            streakDays = calculateStreak(readingMillisByDate, today),
            weekMinutes = weekMinutes,
            weeklyGoalMinutes = weeklyGoalMinutes,
            weekProgress = calculateWeekProgress(weekMinutes, weeklyGoalMinutes),
            totalBookCount = books.size,
            recentBooks = books.asSequence()
                .filterNot { it.isAudioBook() }
                .filter { it.lastOpened != null }
                .sortedByDescending { it.lastOpened }
                .take(RECENT_BOOK_LIMIT)
                .map { it.toRecentBookModel() }
                .toList(),
        )
    }

    private fun calculateStreak(
        readingMillisByDate: Map<LocalDate, Long>,
        today: LocalDate,
    ): Int {
        fun isValidReadingDay(date: LocalDate): Boolean =
            readingMillisByDate[date].orZero() >= MINIMUM_STREAK_DAY_MILLIS

        var date = when {
            isValidReadingDay(today) -> today
            isValidReadingDay(today.minusDays(1)) -> today.minusDays(1)
            else -> return 0
        }
        var streak = 0
        while (isValidReadingDay(date)) {
            streak += 1
            date = date.minusDays(1)
        }
        return streak
    }

    private fun calculateWeekProgress(weekMinutes: Int, weeklyGoalMinutes: Int): Float =
        if (weeklyGoalMinutes <= 0) 0f
        else (weekMinutes.toFloat() / weeklyGoalMinutes).coerceIn(0f, 1f)

    private fun Book.isAudioBook(): Boolean =
        fileType.equals(FileType.AUDIOBOOK.name, ignoreCase = true) ||
            stringToFileType(fileType) == FileType.AUDIOBOOK

    private fun Book.toRecentBookModel(): RecentBookModel {
        val clampedProgress = progress.coerceIn(0f, 100f)
        return RecentBookModel(
            id = id,
            title = title,
            author = author,
            coverImage = coverImage,
            progressPercent = clampedProgress,
            locationLabel = locator.takeIf { it.isNotBlank() } ?: "${clampedProgress.toInt()}%",
            lastOpenedEpochMillis = requireNotNull(lastOpened),
        )
    }

    private fun Long?.orZero(): Long = this ?: 0L

    private fun Long.toMinutes(): Int = (this / MILLIS_PER_MINUTE).toInt()

    companion object {
        const val DEFAULT_WEEKLY_GOAL_MINUTES = 150
        const val MINIMUM_STREAK_DAY_MILLIS = 60_000L
        const val RECENT_BOOK_LIMIT = 3
        private const val MILLIS_PER_MINUTE = 60_000L
    }
}

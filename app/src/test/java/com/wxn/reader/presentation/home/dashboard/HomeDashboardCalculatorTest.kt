package com.wxn.reader.presentation.home.dashboard

import com.wxn.base.bean.Book
import com.wxn.reader.domain.model.ReadingActive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class HomeDashboardCalculatorTest {
    private val calculator = HomeDashboardCalculator()
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val now = Instant.parse("2026-08-25T04:00:00Z")

    @Test
    fun `today minutes aggregate duplicate records and week progress caps`() {
        val activities = listOf(
            activityAt("2026-08-25T01:00:00Z", 90_000),
            activityAt("2026-08-25T02:00:00Z", 180_000),
            activityAt("2026-08-24T02:00:00Z", 20_000_000),
        )

        val result = calculator.calculate(emptyList(), activities, now, zoneId, 150)

        assertEquals(4, result.todayMinutes)
        assertEquals(337, result.weekMinutes)
        assertEquals(150, result.weeklyGoalMinutes)
        assertEquals(1f, result.weekProgress)
    }

    @Test
    fun `streak continues from yesterday when today has no valid minute`() {
        val activities = listOf(
            activityAt("2026-08-24T04:00:00Z", 60_000),
            activityAt("2026-08-23T04:00:00Z", 60_000),
            activityAt("2026-08-25T02:00:00Z", 59_999),
        )

        val result = calculator.calculate(emptyList(), activities, now, zoneId)

        assertEquals(2, result.streakDays)
    }

    @Test
    fun `streak resets after two day gap`() {
        val activities = listOf(
            activityAt("2026-08-22T04:00:00Z", 120_000),
            activityAt("2026-08-21T04:00:00Z", 120_000),
        )

        val result = calculator.calculate(emptyList(), activities, now, zoneId)

        assertEquals(0, result.streakDays)
    }

    @Test
    fun `week begins Monday and excludes Sunday`() {
        val activities = listOf(
            activityAt("2026-08-23T04:00:00Z", 600_000),
            activityAt("2026-08-24T04:00:00Z", 120_000),
        )

        val result = calculator.calculate(emptyList(), activities, now, zoneId)

        assertEquals(2, result.weekMinutes)
    }

    @Test
    fun `recent books are readable newest three with clamped progress`() {
        val books = listOf(
            book(id = 1, lastOpened = 1_000, progress = -4f),
            book(id = 2, lastOpened = 2_000, progress = 20f, locator = "第 3 章"),
            book(id = 3, lastOpened = 3_000, progress = 45.5f),
            book(id = 4, lastOpened = 4_000, progress = 180f),
            book(id = 5, lastOpened = 5_000, fileType = "AUDIOBOOK"),
            book(id = 6, lastOpened = null),
        )

        val result = calculator.calculate(books, emptyList(), now, zoneId)

        assertEquals(6, result.totalBookCount)
        assertEquals(listOf(4L, 3L, 2L), result.recentBooks.map { it.id })
        assertEquals(100f, result.recentBooks[0].progressPercent)
        assertEquals("45%", result.recentBooks[1].locationLabel)
        assertEquals("第 3 章", result.recentBooks[2].locationLabel)
    }

    private fun activityAt(instant: String, readingTime: Long) = ReadingActive(
        date = Instant.parse(instant).toEpochMilli(),
        readingTime = readingTime,
    )

    private fun book(
        id: Long,
        lastOpened: Long?,
        progress: Float = 0f,
        locator: String = "",
        fileType: String = "epub",
    ) = Book(
        id = id,
        title = "Book $id",
        author = "Author $id",
        description = null,
        filePath = "file:///book-$id.epub",
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = progress,
        lastOpened = lastOpened,
        category = null,
        fileType = fileType,
        locator = locator,
    )
}

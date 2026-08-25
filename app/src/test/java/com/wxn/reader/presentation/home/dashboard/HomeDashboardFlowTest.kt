package com.wxn.reader.presentation.home.dashboard

import com.wxn.base.bean.Book
import com.wxn.reader.domain.model.ReadingActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class HomeDashboardFlowTest {
    private val zoneId = ZoneId.of("Asia/Shanghai")
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-25T04:00:00Z"), zoneId)

    @Test
    fun `combines books and reading activities`() = runTest {
        val result = observeHomeDashboard(
            books = flowOf(listOf(book(id = 7, lastOpened = 10L))),
            activities = flowOf(
                listOf(
                    ReadingActive(
                        date = Instant.parse("2026-08-25T02:00:00Z").toEpochMilli(),
                        readingTime = 180_000,
                    ),
                ),
            ),
            calculator = HomeDashboardCalculator(),
            clock = fixedClock,
            zoneId = zoneId,
        ).first()

        assertEquals(1, result.totalBookCount)
        assertEquals(3, result.todayMinutes)
        assertEquals(listOf(7L), result.recentBooks.map { it.id })
    }

    @Test
    fun `activity failure keeps books and zeroes reading metrics`() = runTest {
        val result = observeHomeDashboard(
            books = flowOf(listOf(book(id = 7, lastOpened = 10L))),
            activities = flow { throw IOException("read failed") },
            calculator = HomeDashboardCalculator(),
            clock = fixedClock,
            zoneId = zoneId,
        ).first()

        assertEquals(1, result.totalBookCount)
        assertEquals(0, result.todayMinutes)
        assertEquals(0, result.streakDays)
    }

    @Test
    fun `activity cancellation is rethrown`() = runTest {
        try {
            observeHomeDashboard(
                books = flowOf(emptyList()),
                activities = flow { throw CancellationException("cancelled") },
                calculator = HomeDashboardCalculator(),
                clock = fixedClock,
                zoneId = zoneId,
            ).first()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancelled", expected.message)
        }
    }

    private fun book(id: Long, lastOpened: Long?) = Book(
        id = id,
        title = "Book $id",
        author = "Author $id",
        description = null,
        filePath = "file:///book-$id.epub",
        coverImage = null,
        scrollIndex = 0,
        scrollOffset = 0,
        progress = 25f,
        lastOpened = lastOpened,
        category = null,
        fileType = "epub",
    )
}

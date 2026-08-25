package com.wxn.reader.presentation.home.dashboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.wxn.reader.ui.theme.PageNestTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HomeDashboardContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun showsReadingSummaryAndOpensRecentBook() {
        var openedBookId: Long? = null
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                HomeDashboardContent(
                    model = populatedModel(),
                    expanded = false,
                    onRecentBookClick = { openedBookId = it },
                    onImportClick = {},
                    onAllBooksClick = {},
                )
            }
        }

        compose.onNodeWithTag("home_dashboard").assertIsDisplayed()
        compose.onNodeWithTag("reading_summary").assertIsDisplayed()
        compose.onNodeWithText("12 分钟").assertIsDisplayed()
        compose.onNodeWithText("围城").assertIsDisplayed()
        compose.onNodeWithTag("recent_book_42").performClick()

        assertEquals(42L, openedBookId)
    }

    @Test
    fun emptyLibraryGuidesImportAndInvokesCallback() {
        var imported = false
        compose.setContent {
            PageNestTheme(darkTheme = false) {
                HomeDashboardContent(
                    model = HomeDashboardModel(),
                    expanded = false,
                    onRecentBookClick = {},
                    onImportClick = { imported = true },
                    onAllBooksClick = {},
                )
            }
        }

        compose.onNodeWithTag("empty_library").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("import_books").performScrollTo().performClick()

        assertTrue(imported)
    }

    @Test
    fun allBooksActionInvokesCallback() {
        var openedAllBooks = false
        compose.setContent {
            PageNestTheme(darkTheme = true) {
                HomeDashboardContent(
                    model = populatedModel(),
                    expanded = false,
                    onRecentBookClick = {},
                    onImportClick = {},
                    onAllBooksClick = { openedAllBooks = true },
                )
            }
        }

        compose.onNodeWithTag("all_books").performScrollTo().performClick()

        assertTrue(openedAllBooks)
    }

    private fun populatedModel() = HomeDashboardModel(
        todayMinutes = 12,
        streakDays = 5,
        weekMinutes = 80,
        weeklyGoalMinutes = 150,
        weekProgress = 80f / 150f,
        totalBookCount = 4,
        recentBooks = listOf(
            RecentBookModel(
                id = 42,
                title = "围城",
                author = "钱钟书",
                coverImage = null,
                progressPercent = 36f,
                locationLabel = "第 6 章",
                lastOpenedEpochMillis = 1_787_632_800_000,
            ),
        ),
    )
}

package com.air5005.pagenest.discovery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiscoveryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun content_exposes_discovery_sections_and_selection() {
        var selected: OnlineBook? = null
        val book = testBook()
        compose.setContent {
            MaterialTheme {
                DiscoveryScreen(
                    state = DiscoveryUiState(
                        isLoading = false,
                        sections = DiscoverySections(listOf(book), listOf(book)),
                    ),
                    onBookSelected = { selected = it },
                )
            }
        }

        compose.onNodeWithText("在线发现").assertIsDisplayed()
        compose.onNodeWithText("编辑推荐").assertIsDisplayed()
        compose.onNodeWithText("热门榜单").assertIsDisplayed()
        compose.onNodeWithText("Pride and Prejudice", useUnmergedTree = true).performClick()
        assertEquals(book, selected)
    }

    @Test
    fun stale_and_empty_states_are_visible() {
        compose.setContent {
            MaterialTheme {
                DiscoveryScreen(
                    state = DiscoveryUiState(
                        isLoading = false,
                        fromStaleCache = true,
                        hasLoadError = true,
                    ),
                )
            }
        }

        compose.onNodeWithText("当前显示离线缓存").assertIsDisplayed()
        compose.onNodeWithText("暂时没有找到书籍").assertIsDisplayed()
    }

    private fun testBook() = OnlineBook(
        stableKey = "gutenberg:1342",
        title = "Pride and Prejudice",
        authors = listOf("Jane Austen"),
        summary = null,
        languages = listOf("en"),
        subjects = listOf("Fiction"),
        coverUrl = null,
        sourceRank = 1,
        popularity = 99.0,
        catalogUpdatedAtEpochMillis = null,
        rightsStatus = RightsStatus.PUBLIC_DOMAIN,
        sourceReferences = listOf(SourceReference("gutendex", "1342")),
        acquisitions = listOf(
            OnlineAcquisition(
                "gutendex", OnlineBookFormat.EPUB, "https://example.com/1342.epub",
                AcquisitionAccess.FREE_FULL, 1,
            ),
        ),
    )
}

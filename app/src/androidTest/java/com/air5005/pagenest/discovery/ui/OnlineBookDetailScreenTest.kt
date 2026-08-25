package com.air5005.pagenest.discovery.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.SourceReference
import org.junit.Rule
import org.junit.Test

class OnlineBookDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun detail_shows_rights_formats_and_safe_phase_three_notice() {
        compose.setContent {
            MaterialTheme {
                OnlineBookDetailScreen(book = testBook())
            }
        }

        compose.onNodeWithText("Pride and Prejudice").assertIsDisplayed()
        compose.onNodeWithText("公共领域").assertIsDisplayed()
        compose.onNodeWithText("EPUB").assertIsDisplayed()
        compose.onNodeWithText("安全下载与导入将在下一阶段开放").assertIsDisplayed()
        compose.onNodeWithText("查看来源").assertIsDisplayed()
    }

    private fun testBook() = OnlineBook(
        "gutenberg:1342", "Pride and Prejudice", listOf("Jane Austen"), null,
        listOf("en"), emptyList(), null, 1, null, null, RightsStatus.PUBLIC_DOMAIN,
        listOf(SourceReference("gutendex", "1342")),
        listOf(OnlineAcquisition("gutendex", OnlineBookFormat.EPUB,
            "https://example.com/1342.epub", AcquisitionAccess.FREE_FULL, 1)),
    )
}

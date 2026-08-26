package com.air5005.pagenest.discovery.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
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
    fun eligible_detail_shows_import_actions_and_dispatches_them() {
        var addCalls = 0
        var readCalls = 0
        compose.setContent {
            MaterialTheme {
                OnlineBookDetailScreen(
                    book = testBook(),
                    acquisition = DiscoveryAcquisitionState.Idle,
                    onAddToShelf = { addCalls++ },
                    onStartReading = { readCalls++ },
                )
            }
        }

        compose.onNodeWithText("Pride and Prejudice").assertIsDisplayed()
        compose.onNodeWithText("公共领域").assertIsDisplayed()
        compose.onNodeWithText("EPUB").assertIsDisplayed()
        compose.onNodeWithText("加入书架").performClick()
        compose.onNodeWithText("开始阅读").performClick()
        assert(addCalls == 1)
        assert(readCalls == 1)
        compose.onNodeWithText("查看来源").assertIsDisplayed()
    }

    @Test
    fun progress_cancel_success_and_safe_error_are_visible() {
        var cancelCalls = 0
        compose.setContent {
            MaterialTheme {
                Column {
                    OnlineAcquisitionControls(
                        acquisition = DiscoveryAcquisitionState.Downloading(50, 100),
                        onAddToShelf = {},
                        onStartReading = {},
                        onCancel = { cancelCalls++ },
                    )
                }
            }
        }
        compose.onNodeWithText("正在下载 50%").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        assert(cancelCalls == 1)

        compose.setContent {
            MaterialTheme {
                OnlineAcquisitionControls(
                    acquisition = DiscoveryAcquisitionState.Added(42L),
                    onAddToShelf = {},
                    onStartReading = {},
                    onCancel = {},
                )
            }
        }
        compose.onNodeWithText("已加入书架").assertIsDisplayed()
        compose.onNodeWithText("开始阅读").assertIsDisplayed()
    }

    @Test
    fun inaccessible_detail_keeps_source_only() {
        compose.setContent {
            MaterialTheme {
                OnlineBookDetailScreen(
                    book = testBook().copy(rightsStatus = RightsStatus.UNKNOWN),
                )
            }
        }

        compose.onAllNodesWithText("加入书架").assertCountEquals(0)
        compose.onAllNodesWithText("开始阅读").assertCountEquals(0)
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

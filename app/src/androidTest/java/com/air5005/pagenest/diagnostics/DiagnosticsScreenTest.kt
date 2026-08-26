package com.air5005.pagenest.diagnostics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wxn.base.diagnostics.DiagnosticLevel
import com.wxn.base.diagnostics.DiagnosticLogEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class DiagnosticsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun screenShowsBoundedStorageFiltersAndLogEntry() {
        var selected: DiagnosticsFilter? = null
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = DiagnosticsUiState(
                        entries = listOf(
                            DiagnosticLogEntry(1_725_000_000_000L, DiagnosticLevel.WARNING, "NETWORK", "Connection unavailable"),
                        ),
                        totalBytes = 321L,
                        isLoading = false,
                    ),
                    onFilterSelected = { selected = it },
                )
            }
        }

        compose.onNodeWithText("运行与错误日志").assertIsDisplayed()
        compose.onNodeWithText("最近 500 条 · 最多 2 MB").assertIsDisplayed()
        compose.onNodeWithText("全部").assertIsDisplayed()
        compose.onNodeWithText("运行").assertIsDisplayed()
        compose.onNodeWithText("告警").performClick()
        assertEquals(DiagnosticsFilter.WARNING, selected)
        compose.onNodeWithText("错误").assertIsDisplayed()
        compose.onNodeWithText("NETWORK").assertIsDisplayed()
        compose.onNodeWithText("Connection unavailable").assertIsDisplayed()
        compose.onNodeWithText("已使用 321 B").assertIsDisplayed()
    }

    @Test
    fun clearRequiresConfirmationBeforeCallback() {
        var clearCalls = 0
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = DiagnosticsUiState(isLoading = false),
                    onClear = { clearCalls++ },
                )
            }
        }

        compose.onNodeWithContentDescription("清空").performClick()
        assertEquals(0, clearCalls)
        compose.onNodeWithText("确认清空日志？").assertIsDisplayed()
        compose.onNodeWithText("确认清空").performClick()
        assertEquals(1, clearCalls)
    }

    @Test
    fun emptyAndSafeReadFailureStatesAreVisible() {
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = DiagnosticsUiState(
                        isLoading = false,
                        failure = DiagnosticsFailure.READ,
                    ),
                )
            }
        }

        compose.onNodeWithText("还没有运行或错误日志").assertIsDisplayed()
        compose.onNodeWithText("日志读取失败，请刷新重试").assertIsDisplayed()
    }
}

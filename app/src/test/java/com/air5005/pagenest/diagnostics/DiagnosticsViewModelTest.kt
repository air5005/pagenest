package com.air5005.pagenest.diagnostics

import com.wxn.base.diagnostics.DiagnosticLevel
import com.wxn.base.diagnostics.DiagnosticLogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial load publishes newest entries and storage size`() = runTest(dispatcher) {
        val repository = FakeRepository(
            entries = listOf(entry(20, DiagnosticLevel.ERROR), entry(10, DiagnosticLevel.RUNNING)),
            bytes = 321,
        )

        val viewModel = DiagnosticsViewModel(repository)
        advanceUntilIdle()

        assertEquals(listOf(20L, 10L), viewModel.state.value.entries.map { it.timestampEpochMillis })
        assertEquals(321L, viewModel.state.value.totalBytes)
        assertFalse(viewModel.state.value.isLoading)
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `filters expose only selected level without reloading files`() = runTest(dispatcher) {
        val repository = FakeRepository(
            entries = DiagnosticLevel.entries.mapIndexed { index, level -> entry(index.toLong(), level) },
        )
        val viewModel = DiagnosticsViewModel(repository)
        advanceUntilIdle()

        viewModel.selectFilter(DiagnosticsFilter.WARNING)

        assertEquals(listOf(DiagnosticLevel.WARNING), viewModel.state.value.visibleEntries.map { it.level })
        assertEquals(1, repository.readCalls)
    }

    @Test
    fun `refresh replaces entries and clears prior failure`() = runTest(dispatcher) {
        val repository = FakeRepository(failRead = true)
        val viewModel = DiagnosticsViewModel(repository)
        advanceUntilIdle()
        assertEquals(DiagnosticsFailure.READ, viewModel.state.value.failure)
        repository.failRead = false
        repository.entries = listOf(entry(30, DiagnosticLevel.WARNING))

        viewModel.refresh()
        advanceUntilIdle()

        assertEquals(listOf(30L), viewModel.state.value.entries.map { it.timestampEpochMillis })
        assertNull(viewModel.state.value.failure)
    }

    @Test
    fun `clear removes files then refreshes empty state`() = runTest(dispatcher) {
        val repository = FakeRepository(entries = listOf(entry(1, DiagnosticLevel.ERROR)), bytes = 50)
        val viewModel = DiagnosticsViewModel(repository)
        advanceUntilIdle()

        viewModel.clear()
        advanceUntilIdle()

        assertTrue(repository.clearCalls == 1)
        assertTrue(viewModel.state.value.entries.isEmpty())
        assertEquals(0L, viewModel.state.value.totalBytes)
    }

    @Test
    fun `clear failure keeps entries and exposes safe failure enum`() = runTest(dispatcher) {
        val repository = FakeRepository(entries = listOf(entry(1, DiagnosticLevel.ERROR)), failClear = true)
        val viewModel = DiagnosticsViewModel(repository)
        advanceUntilIdle()

        viewModel.clear()
        advanceUntilIdle()

        assertEquals(DiagnosticsFailure.CLEAR, viewModel.state.value.failure)
        assertEquals(1, viewModel.state.value.entries.size)
    }

    private fun entry(timestamp: Long, level: DiagnosticLevel) = DiagnosticLogEntry(
        timestampEpochMillis = timestamp,
        level = level,
        category = "TEST",
        message = "message-$timestamp",
    )

    private class FakeRepository(
        var entries: List<DiagnosticLogEntry> = emptyList(),
        var bytes: Long = 0,
        var failRead: Boolean = false,
        var failClear: Boolean = false,
    ) : DiagnosticsRepository {
        var readCalls = 0
        var clearCalls = 0

        override suspend fun readRecent(): DiagnosticsSnapshot {
            readCalls++
            if (failRead) error("private path must not surface")
            return DiagnosticsSnapshot(entries, bytes)
        }

        override suspend fun clear() {
            clearCalls++
            if (failClear) error("disk error")
            entries = emptyList()
            bytes = 0
        }
    }
}

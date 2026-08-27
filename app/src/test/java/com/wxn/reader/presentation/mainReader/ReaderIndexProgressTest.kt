package com.wxn.reader.presentation.mainReader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderIndexProgressTest {

    @Test
    fun `starting an index exposes non determinate progress`() {
        val progress = ReaderIndexProgress()

        progress.start()

        assertEquals(ReaderIndexUiState.Building(completed = 0, total = null), progress.state.value)
    }

    @Test
    fun `chapter progress is clamped to the discovered total`() {
        val progress = ReaderIndexProgress()
        progress.start()

        progress.update(completed = 9, total = 7)

        assertEquals(ReaderIndexUiState.Building(completed = 7, total = 7), progress.state.value)
    }

    @Test
    fun `completing an index removes the progress notice`() {
        val progress = ReaderIndexProgress()
        progress.start()

        progress.complete()

        assertEquals(ReaderIndexUiState.Idle, progress.state.value)
    }

    @Test
    fun `a failed index shows a dismissible non blocking notice`() {
        val progress = ReaderIndexProgress()
        progress.start()

        progress.fail()
        assertEquals(ReaderIndexUiState.Failed, progress.state.value)

        progress.dismissFailure()
        assertEquals(ReaderIndexUiState.Idle, progress.state.value)
    }

    @Test
    fun `an old failure dismissal cannot hide a restarted index`() {
        val progress = ReaderIndexProgress()
        progress.fail()
        progress.start()

        progress.dismissFailure()

        assertEquals(ReaderIndexUiState.Building(completed = 0, total = null), progress.state.value)
    }
}

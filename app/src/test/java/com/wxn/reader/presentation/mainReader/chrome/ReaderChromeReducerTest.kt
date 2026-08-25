package com.wxn.reader.presentation.mainReader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderChromeReducerTest {
    private val initial = ReaderChromeState()

    @Test
    fun `reader starts immersive and center tap toggles controls`() {
        assertFalse(initial.controlsVisible)

        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)

        assertTrue(shown.controlsVisible)
        assertEquals(1L, shown.interactionGeneration)
        assertFalse(
            ReaderChromeReducer.reduce(shown, ReaderChromeEvent.CenterTapped).controlsVisible,
        )
    }

    @Test
    fun `stale timeout cannot hide newly interacted controls`() {
        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)
        val refreshed = ReaderChromeReducer.reduce(shown, ReaderChromeEvent.Interacted)

        assertTrue(
            ReaderChromeReducer.reduce(
                refreshed,
                ReaderChromeEvent.AutoHide(generation = 1L),
            ).controlsVisible,
        )
        assertFalse(
            ReaderChromeReducer.reduce(
                refreshed,
                ReaderChromeEvent.AutoHide(generation = 2L),
            ).controlsVisible,
        )
    }

    @Test
    fun `overlay blocks timeout until it closes`() {
        val shown = ReaderChromeReducer.reduce(initial, ReaderChromeEvent.CenterTapped)
        val blocked = ReaderChromeReducer.reduce(
            shown,
            ReaderChromeEvent.BlockingOverlayChanged(visible = true),
        )

        assertFalse(ReaderChromeReducer.shouldScheduleAutoHide(blocked))
        assertTrue(
            ReaderChromeReducer.reduce(
                blocked,
                ReaderChromeEvent.AutoHide(blocked.interactionGeneration),
            ).controlsVisible,
        )

        val unblocked = ReaderChromeReducer.reduce(
            blocked,
            ReaderChromeEvent.BlockingOverlayChanged(visible = false),
        )

        assertTrue(ReaderChromeReducer.shouldScheduleAutoHide(unblocked))
        assertTrue(unblocked.interactionGeneration > blocked.interactionGeneration)
    }

    @Test
    fun `speech session keeps mini player separate from expanded panel`() {
        val active = ReaderChromeReducer.reduce(
            initial,
            ReaderChromeEvent.SpeechSessionChanged(active = true),
        )

        assertTrue(active.speechMiniPlayerVisible)

        val expanded = ReaderChromeReducer.reduce(
            active,
            ReaderChromeEvent.SpeechPanelChanged(expanded = true),
        )

        assertTrue(expanded.speechPanelExpanded)
        assertFalse(expanded.speechMiniPlayerVisible)

        val stopped = ReaderChromeReducer.reduce(
            expanded,
            ReaderChromeEvent.SpeechSessionChanged(active = false),
        )

        assertFalse(stopped.speechPanelExpanded)
        assertFalse(stopped.speechMiniPlayerVisible)
    }
}

package com.wxn.reader.presentation.mainReader.chrome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderProgressScrubStateTest {
    @Test
    fun previewingProgressDoesNotChangeCommittedProgress() {
        val state = ReaderProgressScrubState(committedProgress = 0.27)

        val preview = state.preview(0.64)

        assertEquals(0.27, preview.committedProgress, 0.0)
        assertEquals(0.64, preview.previewProgress, 0.0)
        assertTrue(preview.isScrubbing)
    }

    @Test
    fun finishingScrubCommitsOnlyTheFinalPreview() {
        val preview = ReaderProgressScrubState(0.27)
            .preview(0.31)
            .preview(0.48)

        val finished = preview.finish()

        assertEquals(0.48, finished.committedProgress, 0.0)
        assertEquals(0.48, finished.previewProgress, 0.0)
        assertFalse(finished.isScrubbing)
    }

    @Test
    fun readerUpdatesDoNotOverwriteAnActiveDrag() {
        val dragging = ReaderProgressScrubState(0.27).preview(0.48)

        val synchronized = dragging.synchronize(0.28)

        assertEquals(0.27, synchronized.committedProgress, 0.0)
        assertEquals(0.48, synchronized.previewProgress, 0.0)
        assertTrue(synchronized.isScrubbing)
    }

    @Test
    fun rejectedCommitReturnsToTheReaderProgress() {
        val dragging = ReaderProgressScrubState(0.27).preview(0.48)

        val cancelled = dragging.cancel(authoritativeProgress = 0.27)

        assertEquals(0.27, cancelled.committedProgress, 0.0)
        assertEquals(0.27, cancelled.previewProgress, 0.0)
        assertFalse(cancelled.isScrubbing)
    }

    @Test
    fun progressValuesAreKeptInsideReaderBounds() {
        assertEquals(0.0, ReaderProgressScrubState(0.4).preview(-1.0).previewProgress, 0.0)
        assertEquals(1.0, ReaderProgressScrubState(0.4).preview(2.0).previewProgress, 0.0)
    }
}

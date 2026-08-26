package com.wxn.reader.presentation.mainReader

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterWordCalculationStatusTest {
    @Test
    fun failureAlwaysReleasesTheCalculationState() = runTest {
        val status = ChapterWordCalculationStatus()

        runCatching {
            status.track {
                assertTrue(status.isRunning)
                error("broken parser")
            }
        }

        assertFalse(status.isRunning)
    }
}

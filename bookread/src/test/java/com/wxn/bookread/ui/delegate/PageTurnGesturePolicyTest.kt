package com.wxn.bookread.ui.delegate

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTurnGesturePolicyTest {
    @Test
    fun `next page remains committed after small reverse jitter`() {
        assertFalse(
            PageTurnGesturePolicy.shouldCancel(
                direction = PageDelegate.Direction.NEXT,
                start = 900f,
                current = 280f,
                pageExtent = 1_000,
            ),
        )
    }

    @Test
    fun `short drag cancels instead of jumping a page`() {
        assertTrue(
            PageTurnGesturePolicy.shouldCancel(
                direction = PageDelegate.Direction.NEXT,
                start = 900f,
                current = 860f,
                pageExtent = 1_000,
            ),
        )
    }

    @Test
    fun `previous page uses the same net distance rule`() {
        assertFalse(
            PageTurnGesturePolicy.shouldCancel(
                direction = PageDelegate.Direction.PREV,
                start = 100f,
                current = 700f,
                pageExtent = 1_000,
            ),
        )
    }
}

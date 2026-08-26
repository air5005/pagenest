package com.wxn.bookread.ui.delegate

internal object PageTurnGesturePolicy {
    private const val COMMIT_FRACTION = 0.08f

    fun shouldCancel(
        direction: PageDelegate.Direction,
        start: Float,
        current: Float,
        pageExtent: Int,
    ): Boolean {
        if (pageExtent <= 0) return true
        val forwardDistance = when (direction) {
            PageDelegate.Direction.NEXT -> start - current
            PageDelegate.Direction.PREV -> current - start
            PageDelegate.Direction.NONE -> return true
        }
        return forwardDistance < pageExtent * COMMIT_FRACTION
    }
}

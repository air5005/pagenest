package com.wxn.reader.presentation.mainReader.chrome

data class ReaderProgressScrubState(
    val committedProgress: Double,
    val previewProgress: Double = committedProgress,
    val isScrubbing: Boolean = false,
) {
    fun preview(progress: Double): ReaderProgressScrubState = copy(
        previewProgress = progress.coerceIn(0.0, 1.0),
        isScrubbing = true,
    )

    fun finish(): ReaderProgressScrubState = copy(
        committedProgress = previewProgress,
        isScrubbing = false,
    )

    fun cancel(authoritativeProgress: Double): ReaderProgressScrubState {
        val safeProgress = authoritativeProgress.coerceIn(0.0, 1.0)
        return ReaderProgressScrubState(
            committedProgress = safeProgress,
            previewProgress = safeProgress,
        )
    }

    fun synchronize(authoritativeProgress: Double): ReaderProgressScrubState =
        if (isScrubbing) this else cancel(authoritativeProgress)
}

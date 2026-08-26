package com.wxn.reader.presentation.mainReader.chrome

data class ReaderChromeState(
    val controlsVisible: Boolean = false,
    val blockingOverlayVisible: Boolean = false,
    val speechSessionActive: Boolean = false,
    val speechPanelExpanded: Boolean = false,
    val progressPanelExpanded: Boolean = false,
    val interactionGeneration: Long = 0L,
) {
    val speechMiniPlayerVisible: Boolean
        get() = speechSessionActive && !speechPanelExpanded && !progressPanelExpanded
}

sealed interface ReaderChromeEvent {
    data object CenterTapped : ReaderChromeEvent
    data object Interacted : ReaderChromeEvent
    data class ControlsVisibilityChanged(val visible: Boolean) : ReaderChromeEvent
    data class BlockingOverlayChanged(val visible: Boolean) : ReaderChromeEvent
    data class SpeechSessionChanged(val active: Boolean) : ReaderChromeEvent
    data class SpeechPanelChanged(val expanded: Boolean) : ReaderChromeEvent
    data class ProgressPanelChanged(val expanded: Boolean) : ReaderChromeEvent
    data class AutoHide(val generation: Long) : ReaderChromeEvent
}

object ReaderChromeReducer {
    const val AUTO_HIDE_MILLIS = 4_000L

    fun reduce(
        state: ReaderChromeState,
        event: ReaderChromeEvent,
    ): ReaderChromeState = when (event) {
        ReaderChromeEvent.CenterTapped -> state.copy(
            controlsVisible = !state.controlsVisible,
            progressPanelExpanded = state.progressPanelExpanded && !state.controlsVisible,
            interactionGeneration = state.interactionGeneration + 1,
        )

        ReaderChromeEvent.Interacted -> state.copy(
            interactionGeneration = state.interactionGeneration + 1,
        )

        is ReaderChromeEvent.ControlsVisibilityChanged -> state.copy(
            controlsVisible = event.visible,
            progressPanelExpanded = state.progressPanelExpanded && event.visible,
            interactionGeneration = state.interactionGeneration + 1,
        )

        is ReaderChromeEvent.BlockingOverlayChanged -> state.copy(
            blockingOverlayVisible = event.visible,
            interactionGeneration = state.interactionGeneration + 1,
        )

        is ReaderChromeEvent.SpeechSessionChanged -> state.copy(
            speechSessionActive = event.active,
            speechPanelExpanded = state.speechPanelExpanded && event.active,
        )

        is ReaderChromeEvent.SpeechPanelChanged -> state.copy(
            speechPanelExpanded = event.expanded && state.speechSessionActive,
            progressPanelExpanded = state.progressPanelExpanded && !event.expanded,
            interactionGeneration = state.interactionGeneration + 1,
        )

        is ReaderChromeEvent.ProgressPanelChanged -> state.copy(
            progressPanelExpanded = event.expanded && state.controlsVisible,
            speechPanelExpanded = state.speechPanelExpanded && !event.expanded,
            interactionGeneration = state.interactionGeneration + 1,
        )

        is ReaderChromeEvent.AutoHide -> if (
            event.generation == state.interactionGeneration && shouldScheduleAutoHide(state)
        ) {
            state.copy(controlsVisible = false, progressPanelExpanded = false)
        } else {
            state
        }
    }

    fun shouldScheduleAutoHide(state: ReaderChromeState): Boolean =
        state.controlsVisible &&
            !state.blockingOverlayVisible &&
            !state.speechPanelExpanded
}

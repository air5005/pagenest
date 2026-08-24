package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.model.SpeechPlaybackState

/** Distinguishes an initial idle service from Idle reached by an explicit session Stop. */
internal class SpeechPlaybackServiceStopPolicy {
    private var observedSession = false

    fun onPlaybackState(state: SpeechPlaybackState): Boolean {
        if (state !is SpeechPlaybackState.Idle) {
            observedSession = true
            return false
        }
        return observedSession.also { shouldStop ->
            if (shouldStop) observedSession = false
        }
    }
}

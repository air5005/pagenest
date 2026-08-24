package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.model.SpeechPlaybackState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechPlaybackServiceStopPolicyTest {
    @Test
    fun `reader navigation does not terminate service but explicit session stop does`() {
        val policy = SpeechPlaybackServiceStopPolicy()

        assertFalse(policy.onPlaybackState(SpeechPlaybackState.Idle))
        assertFalse(policy.onPlaybackState(SpeechPlaybackState.Completed))
        assertTrue(policy.onPlaybackState(SpeechPlaybackState.Idle))
    }
}

package com.air5005.pagenest.speech.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechPlaybackServiceLifecycleTest {
    @Test
    fun `foreground-started service posts its notification during creation`() {
        val service = Robolectric.buildService(SpeechPlaybackService::class.java).create().get()

        val shadowService = shadowOf(service)
        assertEquals(5005, shadowService.lastForegroundNotificationId)
        assertNotNull(shadowService.lastForegroundNotification)

        service.onDestroy()
    }

    @Test
    fun `destroying only the service object does not stop the process speech session`() {
        val service = Robolectric.buildService(SpeechPlaybackService::class.java).get()
        val controller = RecordingController()
        SpeechPlaybackService::class.java.getDeclaredField("controller").apply {
            isAccessible = true
            set(service, controller)
        }

        service.onDestroy()

        assertEquals(1, controller.pauseCalls)
        assertEquals(0, controller.stopCalls)
    }

    private class RecordingController : SpeechController {
        var pauseCalls = 0
        var stopCalls = 0
        override fun resume() = Unit
        override fun pause() { pauseCalls++ }
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() { stopCalls++ }
    }
}

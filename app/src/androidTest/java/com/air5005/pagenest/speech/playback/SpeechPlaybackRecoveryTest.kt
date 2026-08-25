package com.air5005.pagenest.speech.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechPlaybackRecoveryTest {
    @Test
    fun serviceRecreationDoesNotAutoPlayAndKeepsNotificationActions() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val component = ComponentName(context, SpeechPlaybackService::class.java)
        val first = connect(context, component)
        try {
            assertFalse(first.playWhenReady)
            assertNotificationCommandsWork(first)
        } finally {
            first.release()
            context.stopService(Intent(context, SpeechPlaybackService::class.java))
        }

        val recreated = connect(context, component)
        try {
            assertFalse(recreated.playWhenReady)
            assertNotificationCommandsWork(recreated)
        } finally {
            recreated.release()
            context.stopService(Intent(context, SpeechPlaybackService::class.java))
        }
    }

    @Test
    fun focusLossAndNoisyRoutePauseWhileFocusGainDoesNotResume() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = RecordingController()
        val focus = SpeechAudioFocusController(context, controller)
        val noisy = BecomingNoisyReceiver(controller)

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        noisy.onReceive(context, Intent("fixture-unrelated"))
        noisy.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        assertEquals(2, controller.pauseCalls)
        assertEquals(0, controller.resumeCalls)
    }

    private fun assertNotificationCommandsWork(controller: MediaController) {
        val actions = controller.customLayout.mapNotNull { it.sessionCommand?.customAction }
        assertTrue(
            actions.containsAll(
                listOf(
                    SpeechMediaPlayer.ACTION_PREVIOUS,
                    SpeechMediaPlayer.ACTION_NEXT,
                    SpeechMediaPlayer.ACTION_STOP,
                ),
            ),
        )
        actions.forEach { action ->
            assertEquals(
                SessionResult.RESULT_SUCCESS,
                controller.sendCustomCommand(SessionCommand(action, android.os.Bundle.EMPTY), android.os.Bundle.EMPTY)
                    .get(10, TimeUnit.SECONDS)
                    .resultCode,
            )
        }
    }

    private fun connect(context: Context, component: ComponentName): MediaController =
        MediaController.Builder(context, SessionToken(context, component)).buildAsync().get(10, TimeUnit.SECONDS)

    private class RecordingController : SpeechController {
        var pauseCalls = 0
        var resumeCalls = 0
        override fun pause() { pauseCalls++ }
        override fun resume() { resumeCalls++ }
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() = Unit
    }
}

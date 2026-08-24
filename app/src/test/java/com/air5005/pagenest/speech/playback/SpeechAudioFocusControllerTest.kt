package com.air5005.pagenest.speech.playback

import android.content.Intent
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechAudioFocusControllerTest {
    @Test
    fun `permanent focus loss pauses and never auto resumes`() {
        val commands = RecordingSpeechController()
        val focus = SpeechAudioFocusController(
            context = ApplicationProvider.getApplicationContext(),
            controller = commands,
        )

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)

        assertEquals(listOf("pause"), commands.values)
    }

    @Test
    fun `becoming noisy pauses current session`() {
        val commands = RecordingSpeechController()
        val receiver = BecomingNoisyReceiver(commands)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        assertEquals(listOf("pause"), commands.values)
    }

    @Test
    fun `focus loss delegates interruption cleanup to the media bridge`() {
        val commands = RecordingSpeechController()
        val cleanup = mutableListOf<String>()
        val focus = SpeechAudioFocusController(
            context = ApplicationProvider.getApplicationContext(),
            controller = commands,
            onInterruption = { cleanup += "media bridge" },
        )

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertEquals(listOf("media bridge"), cleanup)
        assertEquals(emptyList<String>(), commands.values)
    }

    @Test
    fun `focus loss pauses Media3 and marks playback inactive`() {
        val commands = RecordingSpeechController()
        val activeStates = mutableListOf<Boolean>()
        val player = SpeechMediaPlayer(Looper.getMainLooper(), commands, activeStates::add)
        player.setPlayWhenReady(true)
        val focus = SpeechAudioFocusController(
            context = ApplicationProvider.getApplicationContext(),
            controller = commands,
            onInterruption = player::pauseForInterruption,
        )

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)

        assertFalse(player.playWhenReady)
        assertEquals(listOf("resume", "pause"), commands.values)
        assertEquals(listOf(true, false), activeStates)
        player.release()
    }

    @Test
    fun `becoming noisy pauses Media3 and marks playback inactive`() {
        val commands = RecordingSpeechController()
        val activeStates = mutableListOf<Boolean>()
        val player = SpeechMediaPlayer(Looper.getMainLooper(), commands, activeStates::add)
        player.setPlayWhenReady(true)
        val receiver = BecomingNoisyReceiver(player::pauseForInterruption)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()

        receiver.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        assertFalse(player.playWhenReady)
        assertEquals(listOf("resume", "pause"), commands.values)
        assertEquals(listOf(true, false), activeStates)
        player.release()
    }

    private class RecordingSpeechController : SpeechController {
        val values = mutableListOf<String>()
        override fun resume() { values += "resume" }
        override fun pause() { values += "pause" }
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() = Unit
    }
}

package com.air5005.pagenest.speech.playback

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechMediaPlayerTest {
    @Test
    fun `new player exposes a paused ready media item for a reconstructed session`() {
        val player = SpeechMediaPlayer(Looper.getMainLooper(), RecordingSpeechController())

        assertEquals(androidx.media3.common.Player.STATE_READY, player.playbackState)
        assertFalse(player.playWhenReady)
        assertNotNull(player.currentMediaItem)

        player.release()
    }

    @Test
    fun `transport play pause and skip commands are forwarded to the speech controller`() {
        val commands = RecordingSpeechController()
        val player = SpeechMediaPlayer(Looper.getMainLooper(), commands)

        player.setPlayWhenReady(true)
        player.setPlayWhenReady(false)
        player.handleCustomCommand(SpeechMediaPlayer.ACTION_NEXT)
        player.handleCustomCommand(SpeechMediaPlayer.ACTION_PREVIOUS)
        player.handleCustomCommand(SpeechMediaPlayer.ACTION_STOP)

        assertEquals(
            listOf("resume", "pause", "next", "previous", "stop"),
            commands.values,
        )
        player.release()
    }

    @Test
    fun `now playing metadata exposes book and chapter to media controllers`() {
        val player = SpeechMediaPlayer(Looper.getMainLooper(), RecordingSpeechController())

        player.updateNowPlaying(bookTitle = "The Book", chapterTitle = "Chapter 3", paused = true)

        assertEquals("The Book", player.mediaMetadata.title)
        assertEquals("Chapter 3", player.mediaMetadata.artist)
        assertEquals(false, player.playWhenReady)
        player.release()
    }

    @Test
    fun `coordinator snapshot refreshes paused metadata without auto playback`() {
        val player = SpeechMediaPlayer(Looper.getMainLooper(), RecordingSpeechController())

        player.updateFromSnapshot(
            SpeechControllerSnapshot(
                nowPlaying = SpeechNowPlaying("The Book", "Chapter 4"),
            ),
        )

        assertEquals("The Book", player.mediaMetadata.title)
        assertEquals("Chapter 4", player.mediaMetadata.artist)
        assertFalse(player.playWhenReady)
        assertEquals(androidx.media3.common.Player.STATE_READY, player.playbackState)
        player.release()
    }

    @Test
    fun `interruption pauses the session and clears media playback activity`() {
        val commands = RecordingSpeechController()
        val activeStates = mutableListOf<Boolean>()
        val player = SpeechMediaPlayer(Looper.getMainLooper(), commands, activeStates::add)
        player.setPlayWhenReady(true)

        player.pauseForInterruption()

        assertEquals(listOf("resume", "pause"), commands.values)
        assertFalse(player.playWhenReady)
        assertEquals(listOf(true, false), activeStates)
        player.release()
    }

    @Test
    fun `denied focus leaves the session paused without issuing resume`() {
        val commands = RecordingSpeechController()
        val player = SpeechMediaPlayer(
            Looper.getMainLooper(),
            commands,
            canStartPlayback = { false },
        )

        player.setPlayWhenReady(true)

        assertEquals(emptyList<String>(), commands.values)
        assertFalse(player.playWhenReady)
        player.release()
    }

    private class RecordingSpeechController : SpeechController {
        val values = mutableListOf<String>()
        override fun resume() { values += "resume" }
        override fun pause() { values += "pause" }
        override fun next() { values += "next" }
        override fun previous() { values += "previous" }
        override fun stop() { values += "stop" }
    }
}

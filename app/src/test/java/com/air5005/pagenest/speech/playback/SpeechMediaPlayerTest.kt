package com.air5005.pagenest.speech.playback

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SpeechMediaPlayerTest {
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

    private class RecordingSpeechController : SpeechController {
        val values = mutableListOf<String>()
        override fun resume() { values += "resume" }
        override fun pause() { values += "pause" }
        override fun next() { values += "next" }
        override fun previous() { values += "previous" }
        override fun stop() { values += "stop" }
    }
}

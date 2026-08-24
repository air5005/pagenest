package com.air5005.pagenest.speech.playback

import android.os.Looper
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** A Media3 projection of the active speech session, rather than a second playback engine. */
@AndroidxOptIn(markerClass = [UnstableApi::class])
class SpeechMediaPlayer(
    looper: Looper,
    private val controller: SpeechController,
    private val onPlaybackActiveChanged: (Boolean) -> Unit = {},
    private val canStartPlayback: () -> Boolean = { true },
) : SimpleBasePlayer(looper) {
    private val placeholderMetadata = MediaMetadata.Builder()
        .setTitle(DEFAULT_TITLE)
        .build()
    private val placeholderItem = MediaItem.Builder()
        .setMediaId(MEDIA_ID)
        .setMediaMetadata(placeholderMetadata)
        .build()

    private var state = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .add(Player.COMMAND_PLAY_PAUSE)
                .add(Player.COMMAND_STOP)
                .build(),
        )
        .setPlaylist(
            listOf(
                MediaItemData.Builder(MEDIA_ID)
                    .setMediaItem(placeholderItem)
                    .setMediaMetadata(placeholderMetadata)
                    .build(),
            ),
        )
        .setCurrentMediaItemIndex(0)
        .setPlaylistMetadata(placeholderMetadata)
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .build()

    override fun getState(): State = state

    fun updateNowPlaying(bookTitle: String, chapterTitle: String, paused: Boolean) {
        val metadata = MediaMetadata.Builder()
            .setTitle(bookTitle)
            .setArtist(chapterTitle)
            .build()
        val item = MediaItem.Builder()
            .setMediaId(MEDIA_ID)
            .setMediaMetadata(metadata)
            .build()
        state = state.buildUpon()
            .setPlaylist(
                listOf(
                    MediaItemData.Builder(MEDIA_ID)
                        .setMediaItem(item)
                        .setMediaMetadata(metadata)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaylistMetadata(metadata)
            .setPlayWhenReady(!paused, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        invalidateState()
    }

    /** Mirrors the process-wide session without reconstructing or auto-starting speech. */
    fun updateFromSnapshot(snapshot: SpeechControllerSnapshot) {
        val playing = snapshot.playbackState is com.air5005.pagenest.speech.model.SpeechPlaybackState.Playing
        updateNowPlaying(
            bookTitle = snapshot.nowPlaying.bookTitle,
            chapterTitle = snapshot.nowPlaying.chapterTitle,
            paused = !playing,
        )
        onPlaybackActiveChanged(playing)
    }

    /** Applies an Android interruption to both the session and Media3 foreground state. */
    fun pauseForInterruption() {
        controller.pause()
        state = state.buildUpon()
            .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
            .build()
        onPlaybackActiveChanged(false)
        invalidateState()
    }

    fun handleCustomCommand(action: String): Boolean = when (action) {
        ACTION_NEXT -> {
            controller.next()
            true
        }
        ACTION_PREVIOUS -> {
            controller.previous()
            true
        }
        ACTION_STOP -> {
            controller.stop()
            state = state.buildUpon()
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .build()
            onPlaybackActiveChanged(false)
            invalidateState()
            true
        }
        else -> false
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        if (playWhenReady && !canStartPlayback()) {
            state = state.buildUpon()
                .setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS)
                .build()
            onPlaybackActiveChanged(false)
            invalidateState()
            return Futures.immediateVoidFuture()
        }
        if (playWhenReady) controller.resume() else controller.pause()
        state = state.buildUpon()
            .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()
        onPlaybackActiveChanged(playWhenReady)
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        controller.stop()
        onPlaybackActiveChanged(false)
        return Futures.immediateVoidFuture()
    }

    companion object {
        const val ACTION_NEXT = "com.air5005.pagenest.speech.NEXT"
        const val ACTION_PREVIOUS = "com.air5005.pagenest.speech.PREVIOUS"
        const val ACTION_STOP = "com.air5005.pagenest.speech.STOP"
        private const val MEDIA_ID = "active-speech-session"
        private const val DEFAULT_TITLE = "Speech playback"
    }
}

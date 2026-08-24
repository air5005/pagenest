package com.air5005.pagenest.speech.playback

import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Looper
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.wxn.reader.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/** Foreground MediaSession host for the single speech session. */
@AndroidxOptIn(markerClass = [UnstableApi::class])
class SpeechPlaybackService : MediaSessionService() {
    private lateinit var controller: SpeechController
    private lateinit var focus: SpeechAudioFocusController
    private lateinit var noisyReceiver: BecomingNoisyReceiver
    private lateinit var player: SpeechMediaPlayer
    private lateinit var mediaSession: MediaSession
    private var noisyRegistered = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    getString(R.string.speech_playback_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                DefaultMediaNotificationProvider.NotificationIdProvider { NOTIFICATION_ID },
                NOTIFICATION_CHANNEL_ID,
                R.string.speech_playback_channel_name,
            ),
        )
        controller = createSpeechController()
        focus = SpeechAudioFocusController(this, controller)
        noisyReceiver = BecomingNoisyReceiver(controller)
        player = SpeechMediaPlayer(Looper.getMainLooper(), controller, ::setPlaybackActive)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback(player))
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        setPlaybackActive(false)
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        if (::controller.isInitialized) controller.stop()
        super.onDestroy()
    }

    /** Task 7 wires the process-owned [SessionSpeechController] here. */
    internal fun createSpeechController(): SpeechController = NoOpSpeechController

    private fun setPlaybackActive(active: Boolean) {
        if (active) {
            if (!focus.requestFocus()) {
                controller.pause()
                return
            }
            if (!noisyRegistered) {
                registerReceiver(noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
                noisyRegistered = true
            }
        } else {
            if (noisyRegistered) {
                unregisterReceiver(noisyReceiver)
                noisyRegistered = false
            }
            focus.abandonFocus()
        }
    }

    private class SessionCallback(private val player: SpeechMediaPlayer) : MediaSession.Callback {
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val customCommands = SessionCommands.Builder()
                .add(SessionCommand(SpeechMediaPlayer.ACTION_PREVIOUS, android.os.Bundle.EMPTY))
                .add(SessionCommand(SpeechMediaPlayer.ACTION_NEXT, android.os.Bundle.EMPTY))
                .add(SessionCommand(SpeechMediaPlayer.ACTION_STOP, android.os.Bundle.EMPTY))
                .build()
            return MediaSession.ConnectionResult.accept(customCommands, session.player.availableCommands)
        }

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: android.os.Bundle,
        ): ListenableFuture<SessionResult> = Futures.immediateFuture(
            SessionResult(
                if (player.handleCustomCommand(customCommand.customAction)) {
                    SessionResult.RESULT_SUCCESS
                } else {
                    SessionResult.RESULT_ERROR_NOT_SUPPORTED
                },
            ),
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "speech_playback"
        private const val NOTIFICATION_ID = 5005
    }
}

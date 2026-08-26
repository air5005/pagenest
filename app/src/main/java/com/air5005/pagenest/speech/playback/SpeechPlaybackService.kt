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
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionCommands
import androidx.media3.session.SessionResult
import androidx.media3.common.util.UnstableApi
import com.wxn.reader.R
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.wxn.base.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Foreground MediaSession host for the single speech session. */
@AndroidxOptIn(markerClass = [UnstableApi::class])
class SpeechPlaybackService : MediaSessionService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var controller: SpeechController
    private lateinit var focus: SpeechAudioFocusController
    private lateinit var noisyReceiver: BecomingNoisyReceiver
    private lateinit var player: SpeechMediaPlayer
    private lateinit var mediaSession: MediaSession
    private var noisyRegistered = false
    private var playbackActive = false
    private var snapshotObserver: Job? = null
    private val stopPolicy = SpeechPlaybackServiceStopPolicy()

    override fun onCreate() {
        super.onCreate()
        Logger.running("SPEECH_SERVICE", "Background playback service created")
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
        controller = AppSpeechController
        player = SpeechMediaPlayer(
            Looper.getMainLooper(),
            controller,
            ::setPlaybackActive,
            ::requestPlaybackActivation,
        )
        focus = SpeechAudioFocusController(this, controller, player::pauseForInterruption)
        noisyReceiver = BecomingNoisyReceiver(player::pauseForInterruption)
        mediaSession = MediaSession.Builder(this, player)
            .setCallback(SessionCallback(player))
            .build()
        mediaSession.setCustomLayout(customLayout())
        player.updateFromSnapshot(AppSpeechController.snapshot.value)
        snapshotObserver = serviceScope.launch {
            AppSpeechController.snapshot.collect { snapshot ->
                player.updateFromSnapshot(snapshot)
                if (stopPolicy.onPlaybackState(snapshot.playbackState)) stopSelf()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onDestroy() {
        Logger.running("SPEECH_SERVICE", "Background playback service destroyed")
        snapshotObserver?.cancel()
        serviceScope.cancel()
        setPlaybackActive(false)
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        // Explicit Stop already terminates the session. Unexpected service destruction pauses
        // the process-wide session so recreation never auto-speaks or loses its position.
        if (::controller.isInitialized) controller.pause()
        super.onDestroy()
    }

    private fun setPlaybackActive(active: Boolean) {
        if (active == playbackActive) return
        if (active) {
            if (!requestPlaybackActivation()) player.pauseForInterruption()
        } else {
            if (noisyRegistered) {
                unregisterReceiver(noisyReceiver)
                noisyRegistered = false
            }
            focus.abandonFocus()
            playbackActive = false
        }
    }

    private fun requestPlaybackActivation(): Boolean {
        if (playbackActive) return true
        if (!focus.requestFocus()) {
            Logger.warning("SPEECH_SERVICE", "Audio focus request denied")
            return false
        }
        if (!noisyRegistered) {
            registerReceiver(noisyReceiver, IntentFilter(android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY))
            noisyRegistered = true
        }
        playbackActive = true
        return true
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

        private fun customLayout(): List<CommandButton> = listOf(
            commandButton(SpeechMediaPlayer.ACTION_PREVIOUS, "Previous", CommandButton.ICON_PREVIOUS),
            commandButton(SpeechMediaPlayer.ACTION_NEXT, "Next", CommandButton.ICON_NEXT),
            commandButton(SpeechMediaPlayer.ACTION_STOP, "Stop", CommandButton.ICON_STOP),
        )

        private fun commandButton(action: String, name: String, icon: Int): CommandButton =
            CommandButton.Builder(icon)
                .setSessionCommand(SessionCommand(action, android.os.Bundle.EMPTY))
                .setDisplayName(name)
                .build()
    }
}

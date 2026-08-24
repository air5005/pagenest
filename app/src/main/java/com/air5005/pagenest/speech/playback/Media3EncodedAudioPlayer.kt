package com.air5005.pagenest.speech.playback

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.model.SpeechError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

internal interface EncodedAudioBackend {
    suspend fun play(bytes: ByteArray): SpeechEngineResult
    fun stop()
    fun release()
}

/**
 * Bounds private cloud audio before handing it to Media3, and releases the player when playback
 * is cancelled or the owning speech engine is closed.
 */
class Media3EncodedAudioPlayer internal constructor(
    private val maxAudioBytes: Int,
    private val backend: EncodedAudioBackend,
) : EncodedAudioPlayer {
    constructor(context: Context, maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES) : this(
        maxAudioBytes = maxAudioBytes,
        backend = ExoPlayerEncodedAudioBackend(context.applicationContext),
    )

    private val closed = AtomicBoolean(false)

    override suspend fun playMp3(bytes: ByteArray): SpeechEngineResult {
        if (closed.get() || bytes.isEmpty() || bytes.size > maxAudioBytes) {
            return SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure)
        }
        return try {
            backend.play(bytes.copyOf())
        } catch (cancelled: CancellationException) {
            backend.stop()
            releaseOnce()
            throw cancelled
        }
    }

    override suspend fun stop() {
        backend.stop()
    }

    override fun close() {
        backend.stop()
        releaseOnce()
    }

    private fun releaseOnce() {
        if (closed.compareAndSet(false, true)) backend.release()
    }

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 5 * 1024 * 1024
    }
}

@AndroidxOptIn(markerClass = [UnstableApi::class])
private class ExoPlayerEncodedAudioBackend(context: Context) : EncodedAudioBackend {
    private val appHandler = Handler(Looper.getMainLooper())
    private val player = ExoPlayer.Builder(context)
        .setLooper(Looper.getMainLooper())
        .setHandleAudioBecomingNoisy(false)
        .build()

    override suspend fun play(bytes: ByteArray): SpeechEngineResult = suspendCancellableCoroutine { continuation ->
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && continuation.isActive) {
                    player.removeListener(this)
                    continuation.resume(SpeechEngineResult.Completed)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (continuation.isActive) {
                    player.removeListener(this)
                    continuation.resume(SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure))
                }
            }
        }
        continuation.invokeOnCancellation {
            appHandler.post {
                player.removeListener(listener)
                player.stop()
            }
        }
        appHandler.post {
            if (!continuation.isActive) return@post
            player.addListener(listener)
            val source = ProgressiveMediaSource.Factory { ByteArrayDataSource(bytes) }
                .createMediaSource(
                    MediaItem.Builder()
                        .setUri(Uri.parse("memory://pagenest/speech.mp3"))
                        .setMimeType(MimeTypes.AUDIO_MPEG)
                        .build(),
                )
            player.setMediaSource(source)
            player.prepare()
            player.playWhenReady = true
        }
    }

    override fun stop() {
        appHandler.post { player.stop() }
    }

    override fun release() {
        appHandler.post { player.release() }
    }
}

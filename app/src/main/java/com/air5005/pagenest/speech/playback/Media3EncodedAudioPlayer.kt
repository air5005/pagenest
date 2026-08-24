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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation

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
    private val playbackScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : EncodedAudioPlayer {
    constructor(context: Context, maxAudioBytes: Int = DEFAULT_MAX_AUDIO_BYTES) : this(
        maxAudioBytes = maxAudioBytes,
        backend = ExoPlayerEncodedAudioBackend(context.applicationContext),
    )

    private val closed = AtomicBoolean(false)
    private val released = AtomicBoolean(false)
    private val activePlaybackLock = Any()
    private var activePlayback: ActivePlayback? = null

    override suspend fun playMp3(bytes: ByteArray): SpeechEngineResult {
        if (closed.get() || bytes.isEmpty() || bytes.size > maxAudioBytes || hasActivePlayback()) {
            return SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure)
        }
        val copiedBytes = bytes.copyOf()
        return suspendCancellableCoroutine { continuation ->
            val playback = ActivePlayback(continuation)
            synchronized(activePlaybackLock) {
                if (closed.get() || activePlayback != null) {
                    continuation.resume(SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure))
                    return@suspendCancellableCoroutine
                }
                activePlayback = playback
            }
            playback.worker = playbackScope.launch {
                try {
                    finishPlayback(playback, backend.play(copiedBytes))
                } catch (cancelled: CancellationException) {
                    // A caller cancellation or stop has already completed the public result.
                } catch (_: Throwable) {
                    finishPlayback(
                        playback,
                        SpeechEngineResult.Failed(SpeechError.AudioDecodeFailure),
                    )
                }
            }
            continuation.invokeOnCancellation {
                cancelPlayback(playback, completeAsCancelled = false)
            }
        }
    }

    override suspend fun stop() {
        closed.set(true)
        val playback = synchronized(activePlaybackLock) { activePlayback }
        if (playback != null) {
            cancelPlayback(playback, completeAsCancelled = true)
        } else {
            backend.stop()
            releaseOnce()
        }
    }

    override fun close() {
        closed.set(true)
        val playback = synchronized(activePlaybackLock) { activePlayback }
        if (playback != null) {
            cancelPlayback(playback, completeAsCancelled = true)
        } else {
            backend.stop()
            releaseOnce()
        }
        playbackScope.cancel()
    }

    private fun hasActivePlayback(): Boolean = synchronized(activePlaybackLock) { activePlayback != null }

    private fun finishPlayback(playback: ActivePlayback, result: SpeechEngineResult) {
        synchronized(activePlaybackLock) {
            if (activePlayback !== playback) return
            activePlayback = null
            if (playback.continuation.isActive) playback.continuation.resume(result)
        }
    }

    private fun cancelPlayback(playback: ActivePlayback, completeAsCancelled: Boolean) {
        synchronized(activePlaybackLock) {
            if (activePlayback !== playback) return
            activePlayback = null
        }
        backend.stop()
        playback.worker?.cancel()
        if (completeAsCancelled && playback.continuation.isActive) {
            playback.continuation.resume(SpeechEngineResult.Cancelled)
        }
        closed.set(true)
        releaseOnce()
    }

    private fun releaseOnce() {
        if (released.compareAndSet(false, true)) backend.release()
    }

    companion object {
        const val DEFAULT_MAX_AUDIO_BYTES = 5 * 1024 * 1024
    }

    private class ActivePlayback(
        val continuation: CancellableContinuation<SpeechEngineResult>,
        var worker: Job? = null,
    )
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

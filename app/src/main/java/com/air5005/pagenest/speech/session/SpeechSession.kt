package com.air5005.pagenest.speech.session

import android.os.SystemClock
import com.air5005.pagenest.speech.content.SpeechContentSource
import com.air5005.pagenest.speech.engine.SpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.model.currentSegment
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

fun interface SpeechHighlightSink {
    suspend fun show(segment: SpeechSegment)

    suspend fun clear() = Unit
}

interface SpeechClock {
    fun elapsedRealtime(): Long
    suspend fun awaitUntil(deadlineElapsedMillis: Long)
}

object MonotonicSpeechClock : SpeechClock {
    override fun elapsedRealtime(): Long = SystemClock.elapsedRealtime()

    override suspend fun awaitUntil(deadlineElapsedMillis: Long) {
        delay((deadlineElapsedMillis - elapsedRealtime()).coerceAtLeast(0))
    }
}

class SpeechSession(
    private val engine: SpeechEngine,
    private val progressCommitter: SpeechProgressCommitter,
    private val highlightSink: SpeechHighlightSink,
    private val clock: SpeechClock = MonotonicSpeechClock,
    ownerScope: CoroutineScope,
) : AutoCloseable {
    private val sessionJob = SupervisorJob(ownerScope.coroutineContext[Job])
    private val scope = CoroutineScope(ownerScope.coroutineContext.minusKey(Job) + sessionJob)
    private val messages = Channel<Message>(Channel.UNLIMITED)
    private val admissionLock = ReentrantLock()
    private val closing = AtomicBoolean(false)
    private val _state = MutableStateFlow<SpeechPlaybackState>(SpeechPlaybackState.Idle)
    val state: StateFlow<SpeechPlaybackState> = _state.asStateFlow()
    private val _sleepTimerDeadline = MutableStateFlow<Long?>(null)
    val sleepTimerDeadline: StateFlow<Long?> = _sleepTimerDeadline.asStateFlow()

    private var source: SpeechContentSource? = null
    private var options: SpeechOptions? = null
    private var generation = 0L
    private var playbackJob: Job? = null
    private var timerJob: Job? = null
    private var timerVersion = 0L
    private val ownerJob = scope.launch { runOwner() }

    suspend fun start(source: SpeechContentSource, options: SpeechOptions) =
        submit(SpeechSessionCommand.Start(source, options))

    suspend fun pause() = submit(SpeechSessionCommand.Pause)
    suspend fun resume() = submit(SpeechSessionCommand.Resume)
    suspend fun next() = submit(SpeechSessionCommand.Next)
    suspend fun previous() = submit(SpeechSessionCommand.Previous)
    suspend fun seek(position: SpeechPosition) = submit(SpeechSessionCommand.Seek(position))
    suspend fun setSleepTimer(deadlineElapsedMillis: Long?) =
        submit(SpeechSessionCommand.SetSleepTimer(deadlineElapsedMillis))
    suspend fun stop() = submit(SpeechSessionCommand.Stop)

    suspend fun closeAndJoin() {
        requestClose()
        ownerJob.join()
    }

    override fun close() {
        requestClose()
    }

    private suspend fun submit(command: SpeechSessionCommand) {
        val acknowledged = CompletableDeferred<Unit>()
        admissionLock.withLock {
            check(!closing.get()) { "Speech session is closed" }
            check(messages.trySend(Message.Command(command, acknowledged)).isSuccess) {
                "Speech session is closed"
            }
        }
        withContext(NonCancellable) {
            acknowledged.await()
        }
    }

    private fun requestClose() {
        admissionLock.withLock {
            if (closing.compareAndSet(false, true)) {
                messages.trySend(Message.Close)
            }
        }
    }

    private suspend fun runOwner() {
        try {
            for (message in messages) {
                when (message) {
                    is Message.Command -> {
                        try {
                            handle(message.command)
                            message.acknowledged.complete(Unit)
                        } catch (failure: Throwable) {
                            message.acknowledged.completeExceptionally(failure)
                        }
                    }

                    is Message.PlaybackFinished -> handlePlaybackFinished(message)
                    is Message.TimerExpired -> handleTimerExpired(message.version)
                    Message.Close -> break
                }
            }
        } finally {
            generation++
            playbackJob?.cancel()
            timerJob?.cancel()
            runCatching { engine.stop() }
            source?.close()
            source = null
            runCatching { highlightSink.clear() }
            _sleepTimerDeadline.value = null
            _state.value = SpeechPlaybackState.Idle
            messages.close()
            sessionJob.cancel()
        }
    }

    private suspend fun handle(command: SpeechSessionCommand) {
        when (command) {
            is SpeechSessionCommand.Start -> startOnOwner(command.source, command.options)
            SpeechSessionCommand.Pause -> pauseOnOwner()
            SpeechSessionCommand.Resume -> resumeOnOwner()
            SpeechSessionCommand.Next -> moveOnOwner(forward = true)
            SpeechSessionCommand.Previous -> moveOnOwner(forward = false)
            is SpeechSessionCommand.Seek -> seekOnOwner(command.position)
            is SpeechSessionCommand.SetSleepTimer -> setTimerOnOwner(command.deadlineElapsedMillis)
            SpeechSessionCommand.Stop -> stopOnOwner()
        }
    }

    private suspend fun startOnOwner(newSource: SpeechContentSource, newOptions: SpeechOptions) {
        if (source != null || playbackJob != null || _state.value != SpeechPlaybackState.Idle) {
            invalidatePlayback()
        } else {
            generation++
        }
        if (source !== newSource) source?.close()
        source = newSource
        options = newOptions
        beginOrComplete(newSource.current())
    }

    private suspend fun pauseOnOwner() {
        val segment = _state.value.currentSegment() ?: return
        invalidatePlayback()
        _state.value = SpeechPlaybackState.Paused(segment)
    }

    private suspend fun resumeOnOwner() {
        val paused = _state.value as? SpeechPlaybackState.Paused ?: return
        generation++
        beginPlayback(paused.segment)
    }

    private suspend fun moveOnOwner(forward: Boolean) {
        val activeSource = source ?: return
        invalidatePlayback()
        beginOrComplete(if (forward) activeSource.next() else activeSource.previous())
    }

    private suspend fun seekOnOwner(position: SpeechPosition) {
        val activeSource = source ?: return
        invalidatePlayback()
        beginOrComplete(activeSource.seek(position))
    }

    private fun setTimerOnOwner(deadlineElapsedMillis: Long?) {
        timerVersion++
        timerJob?.cancel()
        timerJob = null
        _sleepTimerDeadline.value = deadlineElapsedMillis
        if (deadlineElapsedMillis == null) return
        val version = timerVersion
        timerJob = scope.launch {
            clock.awaitUntil(deadlineElapsedMillis)
            messages.send(Message.TimerExpired(version))
        }
    }

    private suspend fun handleTimerExpired(version: Long) {
        if (version != timerVersion || _sleepTimerDeadline.value == null) return
        timerVersion++
        timerJob = null
        _sleepTimerDeadline.value = null
        stopOnOwner()
    }

    private suspend fun stopOnOwner() {
        invalidatePlayback()
        timerVersion++
        timerJob?.cancel()
        timerJob = null
        _sleepTimerDeadline.value = null
        source?.close()
        source = null
        options = null
        highlightSink.clear()
        _state.value = SpeechPlaybackState.Idle
    }

    private suspend fun invalidatePlayback() {
        generation++
        playbackJob?.cancel()
        playbackJob = null
        engine.stop()
    }

    private suspend fun beginOrComplete(segment: SpeechSegment?) {
        if (segment == null) {
            highlightSink.clear()
            _state.value = SpeechPlaybackState.Completed
        } else {
            beginPlayback(segment)
        }
    }

    private suspend fun beginPlayback(segment: SpeechSegment) {
        val activeOptions = checkNotNull(options) { "Speech options are not configured" }
        val activeGeneration = generation
        _state.value = SpeechPlaybackState.Preparing(segment)
        highlightSink.show(segment)
        _state.value = SpeechPlaybackState.Playing(segment, engine.id)
        val request = SpeechRequest(
            generationId = activeGeneration,
            segment = segment,
            localeTag = activeOptions.localeTag,
            voiceId = activeOptions.voiceId,
            rate = activeOptions.rate,
            pitch = activeOptions.pitch,
        )
        playbackJob = scope.launch {
            val result = engine.speak(request)
            messages.send(Message.PlaybackFinished(activeGeneration, segment, result))
        }
    }

    private suspend fun handlePlaybackFinished(message: Message.PlaybackFinished) {
        if (message.generation != generation) return
        playbackJob = null
        when (val result = message.result) {
            SpeechEngineResult.Completed -> {
                progressCommitter.commitCompleted(message.segment)
                beginOrComplete(source?.next())
            }

            SpeechEngineResult.Cancelled -> {
                _state.value = SpeechPlaybackState.Paused(message.segment)
            }

            is SpeechEngineResult.Failed -> {
                highlightSink.clear()
                _state.value = SpeechPlaybackState.Error(result.error, message.segment)
            }
        }
    }

    private sealed interface Message {
        data class Command(
            val command: SpeechSessionCommand,
            val acknowledged: CompletableDeferred<Unit>,
        ) : Message

        data class PlaybackFinished(
            val generation: Long,
            val segment: SpeechSegment,
            val result: SpeechEngineResult,
        ) : Message

        data class TimerExpired(val version: Long) : Message
        data object Close : Message
    }
}

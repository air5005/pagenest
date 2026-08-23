package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume

class SystemTtsEngine internal constructor(
    factory: PlatformTextToSpeechFactory,
    ownerScope: CoroutineScope,
) : SpeechEngine {
    override val id: String = "system"

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closeRequested = AtomicBoolean(false)
    private val invocationNonce = AtomicLong(0)
    private val initializationJob: Job = ownerScope.launch {
        val platform = try {
            factory.create()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        commands.send(Command.Initialized(platform))
    }
    private val ownerJob: Job = ownerScope.launch {
        runOwnerLoop()
    }

    override suspend fun voices(localeTag: String): List<SpeechVoice> {
        if (closeRequested.get()) return emptyList()
        val reply = CompletableDeferred<List<SpeechVoice>>()
        if (!commands.trySend(Command.Voices(localeTag, reply)).isSuccess) return emptyList()
        return reply.await()
    }

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
        if (closeRequested.get()) {
            return SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable)
        }
        return suspendCancellableCoroutine { continuation ->
            val call = SpeechCall(
                request = request,
                utteranceId = buildString {
                    append(request.generationId)
                    append(':')
                    append(request.segment.id)
                    append(':')
                    append(invocationNonce.incrementAndGet())
                },
                continuation = continuation,
            )
            continuation.invokeOnCancellation {
                call.cancelByCaller()
                commands.trySend(Command.CancelCaller(call))
            }
            if (!commands.trySend(Command.Speak(call)).isSuccess) {
                call.complete(initializationFailure())
            }
        }
    }

    override suspend fun stop() {
        if (closeRequested.get()) return
        val reply = CompletableDeferred<Unit>()
        if (commands.trySend(Command.Stop(reply)).isSuccess) reply.await()
    }

    override fun close() {
        if (!closeRequested.compareAndSet(false, true)) return
        commands.trySend(Command.Close)
    }

    private suspend fun runOwnerLoop() {
        var platform: PlatformTextToSpeech? = null
        var initializationFinished = false
        var pendingSpeech: SpeechCall? = null
        var activeSpeech: SpeechCall? = null
        val pendingVoiceQueries = mutableListOf<Command.Voices>()
        var closed = false

        try {
            while (!closed) {
                when (val command = commands.receive()) {
                    is Command.Initialized -> {
                        initializationFinished = true
                        platform = command.platform
                        val initialized = platform
                        if (initialized == null) {
                            pendingSpeech?.complete(initializationFailure())
                            pendingSpeech = null
                            pendingVoiceQueries.forEach { it.reply.complete(emptyList()) }
                            pendingVoiceQueries.clear()
                        } else {
                            initialized.setProgressListener(progressListener)
                            pendingVoiceQueries.forEach { query ->
                                query.reply.complete(offlineVoices(initialized, query.localeTag))
                            }
                            pendingVoiceQueries.clear()
                            pendingSpeech?.let { call ->
                                pendingSpeech = null
                                activeSpeech = start(call, initialized)
                            }
                        }
                    }

                    is Command.Voices -> {
                        val initialized = platform
                        when {
                            initialized != null ->
                                command.reply.complete(offlineVoices(initialized, command.localeTag))
                            initializationFinished -> command.reply.complete(emptyList())
                            else -> pendingVoiceQueries += command
                        }
                    }

                    is Command.Speak -> {
                        if (command.call.isCallerCancelled()) {
                            command.call.markTerminal()
                            continue
                        }
                        pendingSpeech?.complete(SpeechEngineResult.Cancelled)
                        pendingSpeech = null
                        activeSpeech?.let { active ->
                            active.complete(SpeechEngineResult.Cancelled)
                            platform?.stop()
                        }
                        activeSpeech = null

                        val initialized = platform
                        when {
                            initialized != null -> activeSpeech = start(command.call, initialized)
                            initializationFinished -> command.call.complete(initializationFailure())
                            else -> pendingSpeech = command.call
                        }
                    }

                    is Command.CancelCaller -> {
                        if (pendingSpeech === command.call) {
                            pendingSpeech = null
                            command.call.markTerminal()
                        }
                        if (activeSpeech === command.call) {
                            activeSpeech = null
                            command.call.markTerminal()
                            platform?.stop()
                        }
                    }

                    is Command.Progress -> {
                        val active = activeSpeech
                        if (active != null && active.utteranceId == command.utteranceId) {
                            activeSpeech = null
                            active.complete(command.result)
                        }
                    }

                    is Command.Stop -> {
                        pendingSpeech?.complete(SpeechEngineResult.Cancelled)
                        pendingSpeech = null
                        activeSpeech?.complete(SpeechEngineResult.Cancelled)
                        activeSpeech = null
                        platform?.stop()
                        command.reply.complete(Unit)
                    }

                    Command.Close -> {
                        closed = true
                        pendingSpeech?.complete(SpeechEngineResult.Cancelled)
                        pendingSpeech = null
                        activeSpeech?.complete(SpeechEngineResult.Cancelled)
                        activeSpeech = null
                        pendingVoiceQueries.forEach { it.reply.complete(emptyList()) }
                        pendingVoiceQueries.clear()
                        initializationJob.cancel()
                    }
                }
            }
        } finally {
            initializationJob.cancel()
            pendingSpeech?.complete(SpeechEngineResult.Cancelled)
            activeSpeech?.complete(SpeechEngineResult.Cancelled)
            pendingVoiceQueries.forEach { it.reply.complete(emptyList()) }
            platform?.let { initialized ->
                initialized.stop()
                initialized.shutdown()
            }
        }
    }

    private fun start(
        call: SpeechCall,
        platform: PlatformTextToSpeech,
    ): SpeechCall? {
        if (call.isCallerCancelled()) {
            call.markTerminal()
            return null
        }
        when (platform.setLanguage(Locale.forLanguageTag(call.request.localeTag))) {
            TextToSpeech.LANG_MISSING_DATA -> {
                call.complete(SpeechEngineResult.Failed(SpeechError.MissingLanguageData))
                return null
            }
            TextToSpeech.LANG_NOT_SUPPORTED -> {
                call.complete(SpeechEngineResult.Failed(SpeechError.UnsupportedLocale))
                return null
            }
        }

        val localeTag = Locale.forLanguageTag(call.request.localeTag).toLanguageTag()
        val offlineVoice = platform.voices().firstOrNull { voice ->
            !voice.networkConnectionRequired &&
                voice.localeTag.equals(localeTag, ignoreCase = true) &&
                (call.request.voiceId == null || voice.id == call.request.voiceId)
        }
        if (offlineVoice == null || !platform.selectVoice(offlineVoice.id)) {
            call.complete(SpeechEngineResult.Failed(SpeechError.NoOfflineVoiceAvailable))
            return null
        }

        platform.setRate(call.request.rate.coerceIn(MIN_RATE_OR_PITCH, MAX_RATE_OR_PITCH))
        platform.setPitch(call.request.pitch.coerceIn(MIN_RATE_OR_PITCH, MAX_RATE_OR_PITCH))
        val startResult = call.startIfLive {
            platform.speak(
                call.request.segment.text,
                TextToSpeech.QUEUE_FLUSH,
                call.utteranceId,
            )
        } ?: run {
            call.markTerminal()
            return null
        }
        if (startResult == TextToSpeech.ERROR) {
            call.complete(SpeechEngineResult.Failed(SpeechError.SystemTtsStartFailed))
            return null
        }
        return call
    }

    private fun offlineVoices(
        platform: PlatformTextToSpeech,
        localeTag: String,
    ): List<SpeechVoice> {
        val requestedTag = Locale.forLanguageTag(localeTag).toLanguageTag()
        return platform.voices()
            .filter { voice ->
                !voice.networkConnectionRequired &&
                    voice.localeTag.equals(requestedTag, ignoreCase = true)
            }
            .map { voice -> SpeechVoice(voice.id, voice.displayName, voice.localeTag) }
    }

    private val progressListener = object : PlatformUtteranceProgressListener {
        override fun onDone(utteranceId: String) {
            commands.trySend(Command.Progress(utteranceId, SpeechEngineResult.Completed))
        }

        override fun onError(utteranceId: String) {
            commands.trySend(
                Command.Progress(
                    utteranceId,
                    SpeechEngineResult.Failed(SpeechError.SystemTtsPlaybackFailed),
                ),
            )
        }

        override fun onStop(utteranceId: String) {
            commands.trySend(Command.Progress(utteranceId, SpeechEngineResult.Cancelled))
        }
    }

    private class SpeechCall(
        val request: SpeechRequest,
        val utteranceId: String,
        private val continuation: CancellableContinuation<SpeechEngineResult>,
    ) {
        private val lock = Any()
        private val terminal = AtomicBoolean(false)
        private var callerCancelled = false

        fun cancelByCaller() = synchronized(lock) {
            callerCancelled = true
        }

        fun isCallerCancelled(): Boolean = synchronized(lock) {
            callerCancelled || !continuation.isActive
        }

        fun startIfLive(start: () -> Int): Int? = synchronized(lock) {
            if (callerCancelled || !continuation.isActive || terminal.get()) null else start()
        }

        fun markTerminal() {
            terminal.compareAndSet(false, true)
        }

        fun complete(result: SpeechEngineResult) {
            if (terminal.compareAndSet(false, true) && continuation.isActive) {
                continuation.resume(result)
            }
        }
    }

    private sealed interface Command {
        data class Initialized(val platform: PlatformTextToSpeech?) : Command
        data class Voices(
            val localeTag: String,
            val reply: CompletableDeferred<List<SpeechVoice>>,
        ) : Command
        data class Speak(val call: SpeechCall) : Command
        data class CancelCaller(val call: SpeechCall) : Command
        data class Progress(
            val utteranceId: String,
            val result: SpeechEngineResult,
        ) : Command
        data class Stop(val reply: CompletableDeferred<Unit>) : Command
        data object Close : Command
    }

    private companion object {
        const val MIN_RATE_OR_PITCH = 0.25f
        const val MAX_RATE_OR_PITCH = 2f

        fun initializationFailure() =
            SpeechEngineResult.Failed(SpeechError.SystemTtsInitializationFailed)
    }
}

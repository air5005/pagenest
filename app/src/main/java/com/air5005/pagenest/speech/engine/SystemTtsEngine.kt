package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechError
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.coroutines.resume
import kotlin.concurrent.withLock

@OptIn(InternalCoroutinesApi::class)
class SystemTtsEngine internal constructor(
    factory: PlatformTextToSpeechFactory,
    ownerScope: CoroutineScope,
    private val admissionLock: ReentrantLock = ReentrantLock(true),
) : SpeechEngine {
    override val id: String = "system"

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val closeRequested = AtomicBoolean(false)
    private val closeFinished = CountDownLatch(1)
    private val ownerCloseFinished = AtomicBoolean(false)
    private val initializationCompleted = AtomicBoolean(false)
    private val invocationNonce = AtomicLong(0)
    private val ownerState = OwnerState()
    private val initializationStartState = AtomicInteger(NOT_STARTED)
    private val ownerLoopStartState = AtomicInteger(NOT_STARTED)
    private val fallbackOwnerScope = CoroutineScope(
        SupervisorJob() + ownerScope.coroutineContext.minusKey(Job),
    )

    @Volatile
    private var ownerThread: Thread? = null

    // Owner coroutines can run immediately, so initialize their callbacks before launch.
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

    private val initializationJob: Job = ownerScope.launch {
        if (!initializationStartState.compareAndSet(NOT_STARTED, STARTED)) return@launch
        val platform = try {
            factory.create()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (!commands.trySend(Command.Initialized(platform)).isSuccess && platform != null) {
            releaseOrphanPlatform(platform)
        }
    }
    private val ownerJob: Job = ownerScope.launch {
        if (!ownerLoopStartState.compareAndSet(NOT_STARTED, STARTED)) return@launch
        runOwnerLoop()
    }

    init {
        initializationJob.invokeOnCompletion {
            initializationCompleted.set(true)
            acknowledgeCloseIfFinished()
        }
        ownerJob.invokeOnCompletion(onCancelling = true, invokeImmediately = true) {
            if (ownerLoopStartState.compareAndSet(NOT_STARTED, SUPPRESSED)) {
                if (initializationStartState.compareAndSet(NOT_STARTED, SUPPRESSED)) {
                    finishCloseBeforeOwnerStart()
                } else {
                    fallbackOwnerScope.launch {
                        ownerThread = Thread.currentThread()
                        try {
                            admissionLock.withLock {
                                closeRequested.set(true)
                                finishCloseOnOwner()
                            }
                        } finally {
                            fallbackOwnerScope.cancel()
                        }
                    }
                }
            }
        }
    }

    override suspend fun voices(localeTag: String): List<SpeechVoice> {
        val reply = CompletableDeferred<List<SpeechVoice>>()
        val admitted = admissionLock.withLock {
            !closeRequested.get() && commands.trySend(Command.Voices(localeTag, reply)).isSuccess
        }
        if (!admitted) return emptyList()
        return reply.await()
    }

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
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
            val admitted = admissionLock.withLock {
                !closeRequested.get() && commands.trySend(Command.Speak(call)).isSuccess
            }
            if (!admitted) {
                call.complete(
                    if (closeRequested.get()) {
                        SpeechEngineResult.Failed(SpeechError.SystemTtsUnavailable)
                    } else {
                        initializationFailure()
                    },
                )
            }
        }
    }

    override suspend fun stop() {
        val reply = CompletableDeferred<Unit>()
        val admitted = admissionLock.withLock {
            !closeRequested.get() && commands.trySend(Command.Stop(reply)).isSuccess
        }
        if (admitted) reply.await()
    }

    override fun close() {
        admissionLock.withLock {
            if (closeFinished.count > 0L && Thread.currentThread() === ownerThread) {
                closeRequested.set(true)
                finishCloseOnOwner()
            } else if (closeRequested.compareAndSet(false, true)) {
                commands.trySend(Command.Close)
            }
        }
        if (closeFinished.count > 0L && Thread.currentThread() !== ownerThread) {
            closeFinished.await()
        }
    }

    private suspend fun runOwnerLoop() {
        ownerThread = Thread.currentThread()

        try {
            while (!ownerState.closed) {
                val command = commands.receiveCatching().getOrNull() ?: break
                when (command) {
                    is Command.Initialized -> {
                        initializeOnOwner(command.platform)
                    }

                    is Command.Voices -> {
                        val initialized = ownerState.platform
                        when {
                            initialized != null ->
                                command.reply.complete(safeOfflineVoices(initialized, command.localeTag))
                            ownerState.initializationFinished -> command.reply.complete(emptyList())
                            else -> ownerState.pendingVoiceQueries += command
                        }
                    }

                    is Command.Speak -> {
                        if (command.call.isCallerCancelled()) {
                            command.call.markTerminal()
                            continue
                        }
                        ownerState.pendingSpeech?.complete(SpeechEngineResult.Cancelled)
                        ownerState.pendingSpeech = null
                        ownerState.activeSpeech?.let { active ->
                            active.complete(SpeechEngineResult.Cancelled)
                            safeStop(ownerState.platform)
                        }
                        ownerState.activeSpeech = null

                        val initialized = ownerState.platform
                        when {
                            initialized != null -> ownerState.activeSpeech = start(command.call, initialized)
                            ownerState.initializationFinished -> command.call.complete(initializationFailure())
                            else -> ownerState.pendingSpeech = command.call
                        }
                    }

                    is Command.CancelCaller -> {
                        if (ownerState.pendingSpeech === command.call) {
                            ownerState.pendingSpeech = null
                            command.call.markTerminal()
                        }
                        if (ownerState.activeSpeech === command.call) {
                            ownerState.activeSpeech = null
                            command.call.markTerminal()
                            safeStop(ownerState.platform)
                        }
                    }

                    is Command.Progress -> {
                        val active = ownerState.activeSpeech
                        if (active != null && active.utteranceId == command.utteranceId) {
                            ownerState.activeSpeech = null
                            active.complete(command.result)
                        }
                    }

                    is Command.Stop -> {
                        ownerState.pendingSpeech?.complete(SpeechEngineResult.Cancelled)
                        ownerState.pendingSpeech = null
                        ownerState.activeSpeech?.complete(SpeechEngineResult.Cancelled)
                        ownerState.activeSpeech = null
                        safeStop(ownerState.platform)
                        command.reply.complete(Unit)
                    }

                    Command.Close -> {
                        admissionLock.withLock { finishCloseOnOwner() }
                    }
                }
            }
        } finally {
            admissionLock.withLock {
                closeRequested.set(true)
                finishCloseOnOwner()
            }
        }
    }

    private fun initializeOnOwner(initialized: PlatformTextToSpeech?) {
        ownerState.initializationFinished = true
        ownerState.platform = initialized
        if (initialized == null) {
            failPendingInitialization()
            return
        }
        try {
            initialized.setProgressListener(progressListener)
            ownerState.pendingVoiceQueries.forEach { query ->
                query.reply.complete(safeOfflineVoices(initialized, query.localeTag))
            }
            ownerState.pendingVoiceQueries.clear()
            ownerState.pendingSpeech?.let { call ->
                ownerState.pendingSpeech = null
                ownerState.activeSpeech = start(call, initialized)
            }
        } catch (_: Throwable) {
            failPendingInitialization()
            releasePlatform()
        }
    }

    private fun failPendingInitialization() {
        ownerState.pendingSpeech?.complete(initializationFailure())
        ownerState.pendingSpeech = null
        ownerState.pendingVoiceQueries.forEach { it.reply.complete(emptyList()) }
        ownerState.pendingVoiceQueries.clear()
    }

    private fun finishCloseOnOwner() {
        if (ownerState.closed) return
        ownerState.closed = true
        initializationJob.cancel()
        commands.close()
        val drainedCommands = drainClosedCommandsForRelease()
        releasePlatform()
        ownerState.pendingSpeech?.complete(SpeechEngineResult.Cancelled)
        ownerState.pendingSpeech = null
        ownerState.activeSpeech?.complete(SpeechEngineResult.Cancelled)
        ownerState.activeSpeech = null
        ownerState.pendingVoiceQueries.forEach { it.reply.complete(emptyList()) }
        ownerState.pendingVoiceQueries.clear()
        terminalizeDrainedCommands(drainedCommands)
        ownerCloseFinished.set(true)
        acknowledgeCloseIfFinished()
    }

    private fun acknowledgeCloseIfFinished() {
        if (ownerCloseFinished.get() && initializationCompleted.get()) {
            closeFinished.countDown()
        }
    }

    private fun finishCloseBeforeOwnerStart() {
        admissionLock.withLock {
            if (ownerCloseFinished.get()) return
            closeRequested.set(true)
            initializationJob.cancel()
            initializationCompleted.set(true)
            commands.close()
            ownerState.closed = true
            val drainedCommands = drainClosedCommandsForRelease()
            ownerCloseFinished.set(true)
            acknowledgeCloseIfFinished()
            terminalizeDrainedCommands(drainedCommands)
        }
    }

    private fun drainClosedCommandsForRelease(): List<Command> {
        val drained = mutableListOf<Command>()
        while (true) {
            when (val command = commands.tryReceive().getOrNull() ?: return drained) {
                is Command.Initialized -> {
                    if (ownerState.platform == null) ownerState.platform = command.platform
                }
                else -> drained += command
            }
        }
    }

    private fun terminalizeDrainedCommands(commands: List<Command>) {
        commands.forEach { command ->
            when (command) {
                is Command.Initialized -> Unit
                is Command.Voices -> command.reply.complete(emptyList())
                is Command.Speak -> command.call.complete(SpeechEngineResult.Cancelled)
                is Command.CancelCaller -> command.call.markTerminal()
                is Command.Progress -> Unit
                is Command.Stop -> command.reply.complete(Unit)
                Command.Close -> Unit
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
        try {
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
            val platformVoices = platform.voices()
            val localeOfflineVoices = platformVoices.filter { voice ->
                !voice.networkConnectionRequired &&
                    voice.localeTag.equals(localeTag, ignoreCase = true)
            }
            val offlineVoice = localeOfflineVoices.firstOrNull { voice ->
                voice.id == call.request.voiceId
            } ?: localeOfflineVoices.firstOrNull()
            when {
                offlineVoice != null && !platform.selectVoice(offlineVoice.id) -> {
                    call.complete(SpeechEngineResult.Failed(SpeechError.NoOfflineVoiceAvailable))
                    return null
                }
                offlineVoice == null && platformVoices.isNotEmpty() -> {
                    call.complete(SpeechEngineResult.Failed(SpeechError.NoOfflineVoiceAvailable))
                    return null
                }
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
        } catch (_: Throwable) {
            call.complete(SpeechEngineResult.Failed(SpeechError.SystemTtsStartFailed))
            return null
        }
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

    private fun safeOfflineVoices(
        platform: PlatformTextToSpeech,
        localeTag: String,
    ): List<SpeechVoice> = try {
        offlineVoices(platform, localeTag)
    } catch (_: Throwable) {
        emptyList()
    }

    private fun safeStop(platform: PlatformTextToSpeech?) {
        try {
            platform?.stop()
        } catch (_: Throwable) {
            // Caller state is terminal before platform stop is attempted.
        }
    }

    private fun releasePlatform() {
        if (ownerState.platformReleased) return
        val platform = ownerState.platform ?: return
        ownerState.platformReleased = true
        safeStop(platform)
        try {
            platform.shutdown()
        } catch (_: Throwable) {
            // Shutdown is best-effort after all engine callers are terminal.
        }
        ownerState.platform = null
    }

    private fun releaseOrphanPlatform(platform: PlatformTextToSpeech) {
        safeStop(platform)
        try {
            platform.shutdown()
        } catch (_: Throwable) {
            // The channel is already closed; no caller can depend on this platform.
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

    private class OwnerState {
        var platform: PlatformTextToSpeech? = null
        var initializationFinished = false
        var pendingSpeech: SpeechCall? = null
        var activeSpeech: SpeechCall? = null
        val pendingVoiceQueries = mutableListOf<Command.Voices>()
        var platformReleased = false
        var closed = false
    }

    private companion object {
        const val MIN_RATE_OR_PITCH = 0.25f
        const val MAX_RATE_OR_PITCH = 2f
        const val NOT_STARTED = 0
        const val STARTED = 1
        const val SUPPRESSED = 2

        fun initializationFailure() =
            SpeechEngineResult.Failed(SpeechError.SystemTtsInitializationFailed)
    }
}

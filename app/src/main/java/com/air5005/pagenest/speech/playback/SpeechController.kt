package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.session.SpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.air5005.pagenest.speech.model.SpeechPlaybackState

/** Commands that may be issued from Media3 and Android interruption callbacks. */
interface SpeechController {
    fun resume()
    fun pause()
    fun next()
    fun previous()
    fun stop()
}

/** Bridges non-suspending Android callbacks to the serialized [SpeechSession] command API. */
class SessionSpeechController(
    private val session: SpeechSession,
    private val scope: CoroutineScope,
) : SpeechController {
    override fun resume() = submit { session.resume() }
    override fun pause() = submit { session.pause() }
    override fun next() = submit { session.next() }
    override fun previous() = submit { session.previous() }
    override fun stop() = submit { session.stop() }

    private fun submit(command: suspend () -> Unit) {
        scope.launch { command() }
    }
}

data class SpeechNowPlaying(
    val bookTitle: String,
    val chapterTitle: String,
)

data class SpeechControllerSnapshot(
    val playbackState: SpeechPlaybackState = SpeechPlaybackState.Idle,
    val nowPlaying: SpeechNowPlaying = SpeechNowPlaying("Speech playback", ""),
)

/**
 * The only process-wide owner for the reader's active [SpeechSession].
 *
 * Readers attach a new session when their source changes. Replacing it closes the old session,
 * and MediaSession plus app commands always target the same controller.
 */
class SpeechSessionCoordinator(
    private val commandScope: CoroutineScope,
) : SpeechController, AutoCloseable {
    private val lock = Any()
    private val _snapshot = MutableStateFlow(SpeechControllerSnapshot())
    val snapshot: StateFlow<SpeechControllerSnapshot> = _snapshot.asStateFlow()

    private var activeSession: SpeechSession? = null
    private var activeController: SpeechController? = null
    private var stateObserver: Job? = null

    fun attach(session: SpeechSession, nowPlaying: SpeechNowPlaying) {
        val previous = synchronized(lock) {
            val oldSession = activeSession
            stateObserver?.cancel()
            activeSession = session
            activeController = SessionSpeechController(session, commandScope)
            _snapshot.value = SpeechControllerSnapshot(session.state.value, nowPlaying)
            stateObserver = commandScope.launch {
                session.state.collect { state ->
                    synchronized(lock) {
                        if (activeSession === session) {
                            _snapshot.value = SpeechControllerSnapshot(state, nowPlaying)
                        }
                    }
                }
            }
            oldSession
        }
        if (previous !== null && previous !== session) previous.close()
    }

    override fun resume() = withActiveController { it.resume() }
    override fun pause() = withActiveController { it.pause() }
    override fun next() = withActiveController { it.next() }
    override fun previous() = withActiveController { it.previous() }
    override fun stop() = withActiveController { it.stop() }

    override fun close() {
        val session = synchronized(lock) {
            stateObserver?.cancel()
            stateObserver = null
            activeController = null
            activeSession.also { activeSession = null }
        }
        session?.close()
    }

    private inline fun withActiveController(command: (SpeechController) -> Unit) {
        synchronized(lock) { activeController }?.let(command)
    }
}

/** Process-lifetime coordinator consumed by both reader callers and [SpeechPlaybackService]. */
object AppSpeechController : SpeechController {
    private val coordinator = SpeechSessionCoordinator(
        CoroutineScope(SupervisorJob() + Dispatchers.Default),
    )

    val snapshot: StateFlow<SpeechControllerSnapshot> = coordinator.snapshot

    fun attach(session: SpeechSession, nowPlaying: SpeechNowPlaying) =
        coordinator.attach(session, nowPlaying)

    override fun resume() = coordinator.resume()
    override fun pause() = coordinator.pause()
    override fun next() = coordinator.next()
    override fun previous() = coordinator.previous()
    override fun stop() = coordinator.stop()
}

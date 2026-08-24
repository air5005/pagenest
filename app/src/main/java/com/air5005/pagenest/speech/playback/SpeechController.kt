package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.session.SpeechSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

internal object NoOpSpeechController : SpeechController {
    override fun resume() = Unit
    override fun pause() = Unit
    override fun next() = Unit
    override fun previous() = Unit
    override fun stop() = Unit
}

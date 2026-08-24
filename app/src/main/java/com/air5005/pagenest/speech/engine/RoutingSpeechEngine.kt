package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechError

data class SpeechRouteIndicator(
    val engineId: String,
    val fellBack: Boolean,
    val fallbackError: SpeechError? = null,
)

/** Adapts the mode-aware router to the one-engine SpeechSession contract. */
class RoutingSpeechEngine(
    private val mode: SpeechMode,
    private val route: suspend (SpeechRequest, SpeechMode, (SpeechRouteIndicator) -> Unit) -> RoutedSpeechResult,
    private val stopRoute: suspend () -> Unit,
    private val onRoute: (SpeechRouteIndicator) -> Unit,
) : SpeechEngine {
    override val id: String = if (mode == SpeechMode.OFFLINE) "system" else "azure"

    override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()

    override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
        return route(request, mode, onRoute).result
    }

    override suspend fun stop() = stopRoute()

    /** Router engines are process singletons; a reader session must never close them. */
    override fun close() = Unit
}

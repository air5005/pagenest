package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechSegment

interface SpeechEngine : AutoCloseable {
    val id: String
    suspend fun voices(localeTag: String): List<SpeechVoice>
    suspend fun speak(request: SpeechRequest): SpeechEngineResult
    suspend fun stop()
    override fun close()
}

data class SpeechRequest(
    val generationId: Long,
    val segment: SpeechSegment,
    val localeTag: String,
    val voiceId: String?,
    val rate: Float,
    val pitch: Float,
)

data class SpeechVoice(
    val id: String,
    val displayName: String,
    val localeTag: String,
)

sealed interface SpeechEngineResult {
    data object Completed : SpeechEngineResult
    data object Cancelled : SpeechEngineResult
    data class Failed(val error: SpeechError) : SpeechEngineResult
}

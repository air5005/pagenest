package com.air5005.pagenest.speech.session

import com.air5005.pagenest.speech.content.SpeechContentSource
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPosition

sealed interface SpeechSessionCommand {
    data class Start(val source: SpeechContentSource, val options: SpeechOptions) : SpeechSessionCommand
    data object Pause : SpeechSessionCommand
    data object Resume : SpeechSessionCommand
    data object Next : SpeechSessionCommand
    data object Previous : SpeechSessionCommand
    data class Seek(val position: SpeechPosition) : SpeechSessionCommand
    data class SetSleepTimer(val deadlineElapsedMillis: Long?) : SpeechSessionCommand
    data class UpdateOptions(val options: SpeechOptions) : SpeechSessionCommand
    data object Stop : SpeechSessionCommand
}

data class SpeechOptions(
    val mode: SpeechMode,
    val localeTag: String,
    val voiceId: String?,
    val rate: Float,
    val pitch: Float,
)

package com.air5005.pagenest.speech.progress

import com.air5005.pagenest.speech.model.SpeechSegment

fun interface SpeechProgressCommitter {
    suspend fun commitCompleted(segment: SpeechSegment)
}

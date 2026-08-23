package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment

interface SpeechContentSource : AutoCloseable {
    suspend fun current(): SpeechSegment?
    suspend fun next(): SpeechSegment?
    suspend fun previous(): SpeechSegment?
    suspend fun seek(position: SpeechPosition): SpeechSegment?
    override fun close()
}

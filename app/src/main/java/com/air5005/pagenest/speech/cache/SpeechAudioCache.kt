package com.air5005.pagenest.speech.cache

import com.air5005.pagenest.speech.engine.SpeechRequest

interface SpeechAudioCache {
    suspend fun retainScope(request: SpeechRequest)
    suspend fun get(request: SpeechRequest, nowMillis: Long): ByteArray?
    suspend fun put(request: SpeechRequest, audio: ByteArray, nowMillis: Long)
    suspend fun remove(request: SpeechRequest)
    suspend fun clear()
}

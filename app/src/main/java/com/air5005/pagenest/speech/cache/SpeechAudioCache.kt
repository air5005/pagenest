package com.air5005.pagenest.speech.cache

import com.air5005.pagenest.speech.engine.SpeechRequest

class SpeechCacheScopeToken internal constructor(
    val generation: Long,
    val bookId: Long,
    val chapterIndex: Int,
)

interface SpeechAudioCache {
    suspend fun retainScope(requestGeneration: Long, request: SpeechRequest): SpeechCacheScopeToken
    suspend fun get(token: SpeechCacheScopeToken, request: SpeechRequest, nowMillis: Long): ByteArray?
    suspend fun put(
        token: SpeechCacheScopeToken,
        request: SpeechRequest,
        audio: ByteArray,
        nowMillis: Long,
    )
    suspend fun remove(token: SpeechCacheScopeToken, request: SpeechRequest)
    suspend fun clear()
}

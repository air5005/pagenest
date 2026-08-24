package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.engine.SpeechEngineResult

/** Plays one private, already encoded cloud TTS response without retaining it on disk. */
interface EncodedAudioPlayer : AutoCloseable {
    suspend fun playMp3(bytes: ByteArray): SpeechEngineResult
    suspend fun stop()
    override fun close()
}

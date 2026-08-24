package com.air5005.pagenest.speech.playback

import com.air5005.pagenest.speech.content.SpeechContentSource
import com.air5005.pagenest.speech.engine.SpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import com.air5005.pagenest.speech.session.SpeechHighlightSink
import com.air5005.pagenest.speech.session.SpeechOptions
import com.air5005.pagenest.speech.session.SpeechSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionCoordinatorTest {
    @Test
    fun `replacing the active session closes the old one and routes commands to the replacement`() = runTest {
        val firstEngine = RecordingEngine()
        val secondEngine = RecordingEngine()
        val first = session(firstEngine)
        val second = session(secondEngine)
        val coordinator = SpeechSessionCoordinator(this)

        first.start(EmptySource, options())
        runCurrent()
        coordinator.attach(first, SpeechNowPlaying("First book", "First chapter"))
        coordinator.attach(second, SpeechNowPlaying("Second book", "Second chapter"))
        second.start(EmptySource, options())
        coordinator.stop()
        runCurrent()

        assertEquals(1, firstEngine.stopCalls)
        assertEquals(1, secondEngine.stopCalls)
        assertEquals(SpeechPlaybackState.Idle, coordinator.snapshot.value.playbackState)
        assertEquals("Second book", coordinator.snapshot.value.nowPlaying.bookTitle)

        coordinator.close()
        second.closeAndJoin()
    }

    private fun kotlinx.coroutines.test.TestScope.session(engine: RecordingEngine) = SpeechSession(
        engine = engine,
        progressCommitter = SpeechProgressCommitter { },
        highlightSink = SpeechHighlightSink { },
        ownerScope = this,
    )

    private fun options() = SpeechOptions(SpeechMode.OFFLINE, "en-US", null, 1f, 1f)

    private object EmptySource : SpeechContentSource {
        override suspend fun current(): SpeechSegment? = null
        override suspend fun next(): SpeechSegment? = null
        override suspend fun previous(): SpeechSegment? = null
        override suspend fun seek(position: SpeechPosition): SpeechSegment? = null
        override fun close() = Unit
    }

    private class RecordingEngine : SpeechEngine {
        override val id = "recording"
        var stopCalls = 0

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()
        override suspend fun speak(request: SpeechRequest): SpeechEngineResult = SpeechEngineResult.Completed
        override suspend fun stop() { stopCalls++ }
        override fun close() = Unit
    }
}

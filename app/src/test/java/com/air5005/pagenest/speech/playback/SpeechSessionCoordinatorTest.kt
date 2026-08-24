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
import com.air5005.pagenest.speech.model.currentSegment
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import com.air5005.pagenest.speech.session.SpeechHighlightSink
import com.air5005.pagenest.speech.session.SpeechOptions
import com.air5005.pagenest.speech.session.SpeechSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.wxn.base.bean.Locator

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionCoordinatorTest {
    @Test
    fun `attached coordinator drives pause resume previous next and stop on a real session`() = runTest {
        val engine = RecordingEngine()
        val source = SegmentSource("first", "second")
        val session = session(engine)
        val coordinator = SpeechSessionCoordinator(this)

        session.start(source, options())
        runCurrent()
        coordinator.attach(session, SpeechNowPlaying("Book", "Chapter"))

        coordinator.pause()
        runCurrent()
        assertTrue(session.state.value is SpeechPlaybackState.Paused)

        coordinator.resume()
        runCurrent()
        assertEquals("first", session.state.value.currentSegment()?.id)

        coordinator.next()
        runCurrent()
        assertEquals("second", session.state.value.currentSegment()?.id)

        coordinator.previous()
        runCurrent()
        assertEquals("first", session.state.value.currentSegment()?.id)

        coordinator.stop()
        runCurrent()

        assertEquals(1, source.nextCalls)
        assertEquals(1, source.previousCalls)
        assertTrue(source.closed)
        assertTrue(engine.stopCalls >= 4)
        assertEquals(SpeechPlaybackState.Idle, coordinator.snapshot.value.playbackState)
        assertEquals("Book", coordinator.snapshot.value.nowPlaying.bookTitle)

        coordinator.close()
        session.closeAndJoin()
    }

    @Test
    fun `replacing a real active session closes it before routing new commands`() = runTest {
        val firstEngine = RecordingEngine()
        val secondEngine = RecordingEngine()
        val firstSource = SegmentSource("first-a", "first-b")
        val secondSource = SegmentSource("second-a", "second-b")
        val first = session(firstEngine)
        val second = session(secondEngine)
        val coordinator = SpeechSessionCoordinator(this)

        first.start(firstSource, options())
        second.start(secondSource, options())
        runCurrent()
        coordinator.attach(first, SpeechNowPlaying("First book", "First chapter"))
        coordinator.attach(second, SpeechNowPlaying("Second book", "Second chapter"))
        runCurrent()
        coordinator.next()
        runCurrent()

        assertTrue(firstSource.closed)
        assertTrue(firstEngine.stopCalls >= 1)
        assertEquals(0, firstSource.nextCalls)
        assertEquals(1, secondSource.nextCalls)
        assertEquals("second-b", second.state.value.currentSegment()?.id)

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

    private class SegmentSource(vararg ids: String) : SpeechContentSource {
        private val segments = ids.mapIndexed { index, id ->
            SpeechSegment(
                id = id,
                position = SpeechPosition(1L, 0, 0, index, 0),
                partIndex = 0,
                text = id,
                locator = Locator(id = id, progression = index.toDouble()),
            )
        }
        private var index = 0
        var nextCalls = 0
        var previousCalls = 0
        var closed = false

        override suspend fun current(): SpeechSegment? = segments.getOrNull(index)
        override suspend fun next(): SpeechSegment? {
            nextCalls++
            if (index + 1 < segments.size) index++
            return segments.getOrNull(index)
        }
        override suspend fun previous(): SpeechSegment? {
            previousCalls++
            if (index > 0) index--
            return segments.getOrNull(index)
        }
        override suspend fun seek(position: SpeechPosition): SpeechSegment? = null
        override fun close() { closed = true }
    }

    private class RecordingEngine : SpeechEngine {
        override val id = "recording"
        var stopCalls = 0

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()
        override suspend fun speak(request: SpeechRequest): SpeechEngineResult = awaitCancellation()
        override suspend fun stop() { stopCalls++ }
        override fun close() = Unit
    }
}

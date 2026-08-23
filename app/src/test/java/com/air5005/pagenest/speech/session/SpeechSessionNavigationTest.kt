package com.air5005.pagenest.speech.session

import com.air5005.pagenest.speech.content.ReflowableSpeechContentSource
import com.air5005.pagenest.speech.content.SpeechLineSnapshot
import com.air5005.pagenest.speech.content.SpeechPageNavigator
import com.air5005.pagenest.speech.content.SpeechPageSnapshot
import com.air5005.pagenest.speech.content.SpeechSegmenter
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionNavigationTest {
    @Test
    fun `previous at the first real reflowable segment preserves active playback`() = runTest {
        val fixture = fixture()
        fixture.session.start(fixture.source, options())
        runCurrent()
        val initial = requireNotNull(fixture.session.state.value.currentSegment())

        fixture.session.previous()
        runCurrent()

        assertEquals(initial, fixture.session.state.value.currentSegment())
        assertTrue(fixture.session.state.value is SpeechPlaybackState.Playing)
        assertEquals(1, fixture.engine.requests.size)
        assertEquals(0, fixture.engine.stopCalls)
        assertTrue(fixture.highlight.events.none { it == "clear" })
        fixture.session.closeAndJoin()
    }

    @Test
    fun `invalid seek in a real reflowable source preserves active playback`() = runTest {
        val fixture = fixture()
        fixture.session.start(fixture.source, options())
        runCurrent()
        val initial = requireNotNull(fixture.session.state.value.currentSegment())

        fixture.session.seek(SpeechPosition(BOOK_ID, chapterIndex = 99, pageIndex = 4, paragraphIndex = 0, textOffset = 0))
        runCurrent()

        assertEquals(initial, fixture.session.state.value.currentSegment())
        assertTrue(fixture.session.state.value is SpeechPlaybackState.Playing)
        assertEquals(1, fixture.engine.requests.size)
        assertEquals(0, fixture.engine.stopCalls)
        assertTrue(fixture.highlight.events.none { it == "clear" })
        fixture.session.closeAndJoin()
    }

    @Test
    fun `only forward exhaustion after completion completes a real reflowable session`() = runTest {
        val fixture = fixture()
        fixture.session.start(fixture.source, options())
        runCurrent()

        fixture.engine.complete(SpeechEngineResult.Completed)
        runCurrent()

        assertEquals(SpeechPlaybackState.Completed, fixture.session.state.value)
        assertEquals(1, fixture.progress.committed.size)
        assertEquals("clear", fixture.highlight.events.last())
        fixture.session.closeAndJoin()
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(): Fixture {
        val navigator = OnePageNavigator()
        val source = ReflowableSpeechContentSource(BOOK_ID, navigator, SpeechSegmenter())
        val engine = ControlledEngine()
        val progress = RecordingProgress()
        val highlight = RecordingHighlight()
        return Fixture(
            session = SpeechSession(engine, progress, highlight, ownerScope = this),
            source = source,
            engine = engine,
            progress = progress,
            highlight = highlight,
        )
    }

    private data class Fixture(
        val session: SpeechSession,
        val source: ReflowableSpeechContentSource,
        val engine: ControlledEngine,
        val progress: RecordingProgress,
        val highlight: RecordingHighlight,
    )

    private class OnePageNavigator : SpeechPageNavigator {
        private val page = SpeechPageSnapshot(
            chapterIndex = 0,
            pageIndex = 0,
            progression = 0.25,
            lines = listOf(SpeechLineSnapshot(0, "only", 0, 4, isImage = false, isLine = false)),
        )

        override suspend fun currentSpeechPage(): SpeechPageSnapshot = page
        override suspend fun nextSpeechPage(): SpeechPageSnapshot? = null
        override suspend fun previousSpeechPage(): SpeechPageSnapshot? = null
        override suspend fun seekSpeechPage(chapterIndex: Int, pageIndex: Int): SpeechPageSnapshot? =
            page.takeIf { chapterIndex == 0 && pageIndex == 0 }

        override fun close() = Unit
    }

    private class ControlledEngine : SpeechEngine {
        override val id = "controlled"
        val requests = mutableListOf<SpeechRequest>()
        var stopCalls = 0
        private var result = CompletableDeferred<SpeechEngineResult>()

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()
        override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
            requests += request
            return result.await()
        }

        override suspend fun stop() {
            stopCalls++
        }

        override fun close() = Unit

        fun complete(value: SpeechEngineResult) {
            result.complete(value)
        }
    }

    private class RecordingProgress : SpeechProgressCommitter {
        val committed = mutableListOf<SpeechSegment>()
        override suspend fun commitCompleted(segment: SpeechSegment) {
            committed += segment
        }
    }

    private class RecordingHighlight : SpeechHighlightSink {
        val events = mutableListOf<String>()
        override suspend fun show(segment: SpeechSegment) {
            events += "show:${segment.id}"
        }

        override suspend fun clear() {
            events += "clear"
        }
    }

    private companion object {
        const val BOOK_ID = 91L
        fun options() = SpeechOptions(SpeechMode.OFFLINE, "zh-CN", null, 1f, 1f)
    }
}

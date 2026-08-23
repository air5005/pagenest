package com.air5005.pagenest.speech.session

import com.air5005.pagenest.speech.content.SpeechContentSource
import com.air5005.pagenest.speech.engine.SpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineResult
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.model.currentSegment
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import com.wxn.base.bean.Locator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSessionTest {
    @Test
    fun `progress commits only fully completed segments`() = runTest {
        val fixture = fixture(segment("a"), segment("b"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Cancelled)
        runCurrent()

        assertEquals(listOf("a"), fixture.progress.committed.map { it.id })
        assertEquals("b", fixture.session.state.value.currentSegment()?.id)
    }

    @Test
    fun `seek fences a late completion from the old generation`() = runTest {
        val old = segment("old", paragraph = 0)
        val sought = segment("sought", paragraph = 9)
        val fixture = fixture(old, sought, completeOnStop = false)

        fixture.session.start(fixture.source, options())
        runCurrent()
        val oldRequest = fixture.engine.pending.single()
        fixture.session.seek(sought.position)
        runCurrent()
        val newRequest = fixture.engine.pending.last()

        oldRequest.result.complete(SpeechEngineResult.Completed)
        runCurrent()

        assertEquals("sought", fixture.session.state.value.currentSegment()?.id)
        assertTrue(fixture.progress.committed.isEmpty())
        assertEquals(listOf("old", "sought"), fixture.engine.requests.map { it.segment.id })

        newRequest.result.complete(SpeechEngineResult.Completed)
        runCurrent()
        assertEquals(listOf("sought"), fixture.progress.committed.map { it.id })
    }

    @Test
    fun `pause and resume replay the same unfinished segment without committing pause`() = runTest {
        val fixture = fixture(segment("unfinished"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.session.pause()
        runCurrent()

        assertEquals(SpeechPlaybackState.Paused(segment("unfinished")), fixture.session.state.value)
        assertTrue(fixture.progress.committed.isEmpty())

        fixture.session.resume()
        runCurrent()
        assertEquals(listOf("unfinished", "unfinished"), fixture.engine.requests.map { it.segment.id })
        assertTrue(fixture.engine.requests[1].generationId > fixture.engine.requests[0].generationId)
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        assertEquals(listOf("unfinished"), fixture.progress.committed.map { it.id })
    }

    @Test
    fun `next and previous replace playback without committing skipped segments`() = runTest {
        val fixture = fixture(segment("a"), segment("b"), segment("c"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.session.next()
        runCurrent()
        fixture.session.previous()
        runCurrent()

        assertEquals(listOf("a", "b", "a"), fixture.engine.requests.map { it.segment.id })
        assertEquals("a", fixture.session.state.value.currentSegment()?.id)
        assertTrue(fixture.progress.committed.isEmpty())
        assertEquals(2, fixture.engine.stopCalls)
    }

    @Test
    fun `a completed chapter tail advances to the next chapter`() = runTest {
        val tail = segment("tail", chapter = 3)
        val head = segment("head", chapter = 4)
        val fixture = fixture(tail, head)

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()

        assertEquals(listOf("tail"), fixture.progress.committed.map { it.id })
        assertEquals("head", fixture.session.state.value.currentSegment()?.id)
        assertEquals(listOf(3, 4), fixture.engine.requests.map { it.segment.position.chapterIndex })
    }

    @Test
    fun `natural completion commits then clears highlight and exposes Completed`() = runTest {
        val fixture = fixture(segment("only"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()

        assertEquals(SpeechPlaybackState.Completed, fixture.session.state.value)
        assertEquals(listOf("only"), fixture.progress.committed.map { it.id })
        assertEquals(listOf("show:only", "clear"), fixture.highlight.events)
    }

    @Test
    fun `engine failure clears highlight but a concurrent stop has cancellation priority`() = runTest {
        val fixture = fixture(segment("failing"), completeOnStop = false)

        fixture.session.start(fixture.source, options())
        runCurrent()
        val old = fixture.engine.pending.single()
        fixture.session.stop()
        runCurrent()
        old.result.complete(SpeechEngineResult.Failed(SpeechError.SystemTtsPlaybackFailed))
        runCurrent()

        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertTrue(fixture.progress.committed.isEmpty())
        assertEquals(listOf("show:failing", "clear"), fixture.highlight.events)
    }

    @Test
    fun `an active generation failure is exposed and never committed`() = runTest {
        val fixture = fixture(segment("failing"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Failed(SpeechError.UnsupportedLocale))
        runCurrent()

        assertEquals(
            SpeechPlaybackState.Error(SpeechError.UnsupportedLocale, segment("failing")),
            fixture.session.state.value,
        )
        assertTrue(fixture.progress.committed.isEmpty())
        assertEquals("clear", fixture.highlight.events.last())
    }

    @Test
    fun `concurrent public commands are serialized with one active generation`() = runTest {
        val target = segment("target", paragraph = 7)
        val fixture = fixture(segment("a"), segment("b"), target)

        fixture.session.start(fixture.source, options())
        runCurrent()
        val next = async { fixture.session.next() }
        val previous = async { fixture.session.previous() }
        val seek = async { fixture.session.seek(target.position) }
        advanceUntilIdle()
        next.await()
        previous.await()
        seek.await()

        assertEquals("target", fixture.session.state.value.currentSegment()?.id)
        assertEquals(1, fixture.engine.maxActive)
        assertEquals(fixture.engine.requests.size, fixture.engine.requests.map { it.generationId }.toSet().size)
    }

    @Test
    fun `highlight is shown before every engine request and progress follows completion`() = runTest {
        val events = mutableListOf<String>()
        val fixture = fixture(segment("a"), events = events)

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()

        assertEquals(listOf("highlight:a", "request:a", "commit:a", "clear"), events)
    }

    @Test
    fun `stop is crash safe and retains only the last completed locator`() = runTest {
        val fixture = fixture(segment("done"), segment("unfinished"))

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        fixture.session.stop()
        runCurrent()

        assertEquals(listOf("done"), fixture.progress.committed.map { it.id })
        assertEquals(1, fixture.source.closeCalls)
        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertEquals("clear", fixture.highlight.events.last())
    }

    @Test
    fun `monotonic sleep timer expires without real sleep and commits only prior completion`() = runTest {
        val clock = FakeSpeechClock(now = 1_000)
        val fixture = fixture(segment("done"), segment("unfinished"), clock = clock)

        fixture.session.start(fixture.source, options())
        runCurrent()
        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        fixture.session.setSleepTimer(1_500)
        runCurrent()

        assertEquals(1_500L, fixture.session.sleepTimerDeadline.value)
        assertEquals(listOf(1_500L), clock.awaitedDeadlines)
        clock.advanceTo(1_500)
        runCurrent()

        assertNull(fixture.session.sleepTimerDeadline.value)
        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertEquals(listOf("done"), fixture.progress.committed.map { it.id })
        assertTrue(fixture.engine.stopCalls >= 1)
    }

    @Test
    fun `caller cancellation cannot cancel an already admitted command`() = runTest {
        val nextGate = CompletableDeferred<Unit>()
        val fixture = fixture(segment("a"), segment("b"), nextGate = nextGate)
        fixture.session.start(fixture.source, options())
        runCurrent()

        val command = launch { fixture.session.next() }
        runCurrent()
        command.cancel()
        nextGate.complete(Unit)
        runCurrent()

        assertEquals("b", fixture.session.state.value.currentSegment()?.id)
    }

    @Test
    fun `close joins the command actor and releases playback source and highlight`() = runTest {
        val fixture = fixture(segment("active"))
        fixture.session.start(fixture.source, options())
        runCurrent()

        fixture.session.closeAndJoin()

        assertEquals(1, fixture.source.closeCalls)
        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertEquals("clear", fixture.highlight.events.last())
        assertTrue(fixture.engine.stopCalls >= 1)
        assertTrue(runCatching { fixture.session.next() }.isFailure)
    }

    @Test
    fun `a session whose owner is already cancelled rejects commands without hanging`() = runTest {
        val owner = Job().apply { cancel() }
        val fixture = fixture(
            segment("never-started"),
            ownerScope = CoroutineScope(owner + StandardTestDispatcher(testScheduler)),
        )

        val command = backgroundScope.async {
            runCatching { fixture.session.start(fixture.source, options()) }
        }
        runCurrent()

        assertTrue("pre-cancelled owner left an admitted acknowledgement pending", command.isCompleted)
        assertTrue(command.await().isFailure)
        fixture.session.closeAndJoin()
    }

    @Test
    fun `owner cancellation drains a blocked command and every queued acknowledgement`() = runTest {
        val owner = Job()
        val gate = CompletableDeferred<Unit>()
        val fixture = fixture(
            segment("a"),
            segment("b"),
            nextGate = gate,
            ownerScope = CoroutineScope(owner + StandardTestDispatcher(testScheduler)),
        )
        fixture.session.start(fixture.source, options())
        runCurrent()
        val blocked = backgroundScope.async { runCatching { fixture.session.next() } }
        val queued = backgroundScope.async { runCatching { fixture.session.previous() } }
        runCurrent()

        owner.cancel()
        runCurrent()

        assertTrue("blocked command acknowledgement was not terminated", blocked.isCompleted)
        assertTrue("queued command acknowledgement was not drained", queued.isCompleted)
        assertTrue(blocked.await().isFailure)
        assertTrue(queued.await().isFailure)
        fixture.session.closeAndJoin()
        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertEquals("clear", fixture.highlight.events.last())
        assertEquals(1, fixture.source.closeCalls)
    }

    @Test
    fun `close cancels a gated next and terminates its admitted acknowledgement`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = fixture(segment("a"), segment("b"), nextGate = gate)
        fixture.session.start(fixture.source, options())
        runCurrent()
        val command = backgroundScope.async { runCatching { fixture.session.next() } }
        runCurrent()

        val closing = backgroundScope.async { fixture.session.closeAndJoin() }
        runCurrent()

        assertTrue("closeAndJoin remained queued behind next", closing.isCompleted)
        assertTrue("gated next acknowledgement remained pending", command.isCompleted)
        assertTrue(command.await().isFailure)
        assertEquals(1, fixture.source.closeCalls)
        assertEquals("clear", fixture.highlight.events.last())
    }

    @Test
    fun `close cancels a gated seek and terminates its admitted acknowledgement`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = fixture(segment("a"), segment("target"), seekGate = gate)
        fixture.session.start(fixture.source, options())
        runCurrent()
        val command = backgroundScope.async {
            runCatching { fixture.session.seek(segment("target").position) }
        }
        runCurrent()

        val closing = backgroundScope.async { fixture.session.closeAndJoin() }
        runCurrent()

        assertTrue("closeAndJoin remained queued behind seek", closing.isCompleted)
        assertTrue("gated seek acknowledgement remained pending", command.isCompleted)
        assertTrue(command.await().isFailure)
    }

    @Test
    fun `a progress exception closes the session and later commands fail instead of hanging`() = runTest {
        val fixture = fixture(segment("a"), progressFailure = IllegalStateException("disk failed"))
        fixture.session.start(fixture.source, options())
        runCurrent()

        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        val later = backgroundScope.async { runCatching { fixture.session.stop() } }
        runCurrent()

        assertTrue("actor failure left admission open", later.isCompleted)
        assertTrue(later.await().isFailure)
        fixture.session.closeAndJoin()
        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        assertEquals(1, fixture.source.closeCalls)
        assertEquals("clear", fixture.highlight.events.last())
    }

    @Test
    fun `a synchronous source exception fails only that command and actor remains usable`() = runTest {
        val fixture = fixture(segment("a"), nextFailure = IllegalStateException("parse failed"))
        fixture.session.start(fixture.source, options())
        runCurrent()

        assertTrue(runCatching { fixture.session.next() }.isFailure)
        fixture.session.pause()
        fixture.session.stop()

        assertEquals(SpeechPlaybackState.Idle, fixture.session.state.value)
        fixture.session.closeAndJoin()
    }

    @Test
    fun `owner cancellation closes admission and drains acknowledgements before slow cleanup`() = runTest {
        val owner = Job()
        val navigationGate = CompletableDeferred<Unit>()
        val cleanupGate = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val fixture = fixture(
            segment("a"),
            segment("b"),
            nextGate = navigationGate,
            stopGate = cleanupGate,
            stopEntered = cleanupEntered,
            ownerScope = CoroutineScope(owner + StandardTestDispatcher(testScheduler)),
        )
        fixture.session.start(fixture.source, options())
        runCurrent()
        val active = backgroundScope.async { runCatching { fixture.session.next() } }
        val queued = backgroundScope.async { runCatching { fixture.session.previous() } }
        runCurrent()

        owner.cancel()
        runCurrent()
        assertTrue(cleanupEntered.isCompleted)
        val activeTerminatedBeforeCleanup = active.isCompleted
        val queuedTerminatedBeforeCleanup = queued.isCompleted
        val raced = backgroundScope.async { runCatching { fixture.session.stop() } }
        runCurrent()
        val raceRejectedBeforeCleanup = raced.isCompleted

        cleanupGate.complete(Unit)
        runCurrent()
        fixture.session.closeAndJoin()

        assertTrue("active acknowledgement waited for cleanup", activeTerminatedBeforeCleanup)
        assertTrue("queued acknowledgement waited for cleanup", queuedTerminatedBeforeCleanup)
        assertTrue("command entered the dead actor channel during cleanup", raceRejectedBeforeCleanup)
        assertTrue(active.await().isFailure)
        assertTrue(queued.await().isFailure)
        assertTrue(raced.await().isFailure)
    }

    @Test
    fun `actor failure closes admission before slow cleanup`() = runTest {
        val cleanupGate = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val fixture = fixture(
            segment("a"),
            progressFailure = IllegalStateException("disk failed"),
            stopGate = cleanupGate,
            stopEntered = cleanupEntered,
            ownerScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        )
        fixture.session.start(fixture.source, options())
        runCurrent()

        fixture.engine.completeLatest(SpeechEngineResult.Completed)
        runCurrent()
        assertTrue(cleanupEntered.isCompleted)
        val raced = backgroundScope.async { runCatching { fixture.session.stop() } }
        runCurrent()
        val rejectedBeforeCleanup = raced.isCompleted

        cleanupGate.complete(Unit)
        runCurrent()
        fixture.session.closeAndJoin()

        assertTrue("actor failure left admission open during cleanup", rejectedBeforeCleanup)
        assertTrue(raced.await().isFailure)
    }

    private fun fixture(
        vararg segments: SpeechSegment,
        completeOnStop: Boolean = true,
        clock: FakeSpeechClock = FakeSpeechClock(),
        events: MutableList<String> = mutableListOf(),
        nextGate: CompletableDeferred<Unit>? = null,
        seekGate: CompletableDeferred<Unit>? = null,
        nextFailure: Throwable? = null,
        progressFailure: Throwable? = null,
        stopGate: CompletableDeferred<Unit>? = null,
        stopEntered: CompletableDeferred<Unit>? = null,
        ownerScope: CoroutineScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Unconfined),
    ): Fixture {
        val source = FakeSource(segments.toList(), nextGate, seekGate, nextFailure)
        val engine = FakeEngine(completeOnStop, events, stopGate, stopEntered)
        val progress = RecordingProgress(events, progressFailure)
        val highlight = RecordingHighlight(events)
        val session = SpeechSession(
            engine = engine,
            progressCommitter = progress,
            highlightSink = highlight,
            clock = clock,
            ownerScope = ownerScope,
        )
        return Fixture(session, source, engine, progress, highlight)
    }

    private data class Fixture(
        val session: SpeechSession,
        val source: FakeSource,
        val engine: FakeEngine,
        val progress: RecordingProgress,
        val highlight: RecordingHighlight,
    )

    private class FakeSource(
        private val segments: List<SpeechSegment>,
        private val nextGate: CompletableDeferred<Unit>?,
        private val seekGate: CompletableDeferred<Unit>?,
        private val nextFailure: Throwable?,
    ) : SpeechContentSource {
        private var index = 0
        var closeCalls = 0

        override suspend fun current(): SpeechSegment? = segments.getOrNull(index)
        override suspend fun next(): SpeechSegment? {
            nextGate?.await()
            nextFailure?.let { throw it }
            return segments.getOrNull(++index)
        }
        override suspend fun previous(): SpeechSegment? = segments.getOrNull((index - 1).coerceAtLeast(0)).also {
            if (it != null) index = (index - 1).coerceAtLeast(0)
        }

        override suspend fun seek(position: SpeechPosition): SpeechSegment? {
            seekGate?.await()
            val target = segments.indexOfFirst { it.position == position }
            if (target < 0) return null
            index = target
            return segments[index]
        }

        override fun close() {
            closeCalls++
        }
    }

    private class FakeEngine(
        private val completeOnStop: Boolean,
        private val events: MutableList<String>,
        private val stopGate: CompletableDeferred<Unit>?,
        private val stopEntered: CompletableDeferred<Unit>?,
    ) : SpeechEngine {
        data class Pending(val request: SpeechRequest, val result: CompletableDeferred<SpeechEngineResult>)

        override val id = "fake"
        val requests = mutableListOf<SpeechRequest>()
        val pending = mutableListOf<Pending>()
        var stopCalls = 0
        var active = 0
        var maxActive = 0

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()

        override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
            requests += request
            events += "request:${request.segment.id}"
            val call = Pending(request, CompletableDeferred())
            pending += call
            active++
            maxActive = maxOf(maxActive, active)
            return try {
                withContext(NonCancellable) { call.result.await() }
            } finally {
                active--
            }
        }

        override suspend fun stop() {
            stopCalls++
            if (completeOnStop) pending.lastOrNull { !it.result.isCompleted }
                ?.result?.complete(SpeechEngineResult.Cancelled)
            stopEntered?.complete(Unit)
            stopGate?.await()
        }

        override fun close() = Unit

        fun completeLatest(result: SpeechEngineResult) {
            pending.last { !it.result.isCompleted }.result.complete(result)
        }
    }

    private class RecordingProgress(
        private val events: MutableList<String>,
        private val failure: Throwable?,
    ) : SpeechProgressCommitter {
        val committed = mutableListOf<SpeechSegment>()
        override suspend fun commitCompleted(segment: SpeechSegment) {
            failure?.let { throw it }
            committed += segment
            events += "commit:${segment.id}"
        }
    }

    private class RecordingHighlight(private val sharedEvents: MutableList<String>) : SpeechHighlightSink {
        val events = mutableListOf<String>()
        override suspend fun show(segment: SpeechSegment) {
            events += "show:${segment.id}"
            sharedEvents += "highlight:${segment.id}"
        }

        override suspend fun clear() {
            events += "clear"
            sharedEvents += "clear"
        }
    }

    private class FakeSpeechClock(var now: Long = 0) : SpeechClock {
        private val waiters = mutableListOf<Pair<Long, CompletableDeferred<Unit>>>()
        val awaitedDeadlines = mutableListOf<Long>()

        override fun elapsedRealtime(): Long = now

        override suspend fun awaitUntil(deadlineElapsedMillis: Long) {
            awaitedDeadlines += deadlineElapsedMillis
            if (now >= deadlineElapsedMillis) return
            val waiter = CompletableDeferred<Unit>()
            waiters += deadlineElapsedMillis to waiter
            waiter.await()
        }

        fun advanceTo(value: Long) {
            assertTrue(value >= now)
            now = value
            waiters.filter { it.first <= now }.forEach { it.second.complete(Unit) }
        }
    }

    private companion object {
        fun options() = SpeechOptions(SpeechMode.OFFLINE, "zh-CN", null, 1f, 1f)

        fun segment(id: String, chapter: Int = 1, paragraph: Int = 0): SpeechSegment {
            val position = SpeechPosition(7, chapter, 2, paragraph, 0)
            return SpeechSegment(
                id = id,
                position = position,
                partIndex = 0,
                text = id,
                locator = Locator(
                    id = id,
                    chapterIndex = chapter,
                    startParagraphIndex = paragraph,
                    startTextOffset = 0,
                    endParagraphIndex = paragraph,
                    endTextOffset = id.length,
                    text = id,
                    progression = 0.25,
                ),
            )
        }
    }
}

package com.air5005.pagenest.speech.playback

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import com.air5005.pagenest.speech.progress.RoomSpeechProgressCommitter
import com.air5005.pagenest.speech.progress.SpeechProgressCommitter
import com.air5005.pagenest.speech.session.SpeechHighlightSink
import com.air5005.pagenest.speech.session.SpeechOptions
import com.air5005.pagenest.speech.session.SpeechSession
import com.wxn.base.bean.Book
import com.wxn.base.bean.Locator
import com.wxn.reader.data.mapper.annotation.BookAnnotationMapperImpl
import com.wxn.reader.data.mapper.book.BookMapperImpl
import com.wxn.reader.data.mapper.bookmark.BookmarkMapperImpl
import com.wxn.reader.data.mapper.bookshelf.BookShelfMapperImpl
import com.wxn.reader.data.mapper.note.NoteMapperImpl
import com.wxn.reader.data.mapper.readingactive.ReadingActiveMapperImpl
import com.wxn.reader.data.mapper.shelf.ShelfMapperImpl
import com.wxn.reader.data.repository.BooksRepositoryImpl
import com.wxn.reader.data.source.local.AppDatabase
import com.wxn.reader.domain.use_case.books.GetBookByIdUseCase
import com.wxn.reader.domain.use_case.books.UpdateBookUseCase
import com.wxn.reader.domain.use_case.reading_progress.SetReadingProgressUseCase
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechPlaybackRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun serviceObjectRecreationKeepsPersistedProgressAndPausedSessionWithoutNewSpeech() =
        withPersistedPlaybackFixture { fixture ->
            fixture.completeFirstSegmentAndPauseSecond()
            fixture.recreateService()

            assertEquals(fixture.first.locator, fixture.persistedLocator())
            assertEquals(fixture.pausedSecondState, AppSpeechController.snapshot.value.playbackState)
            assertEquals(2, fixture.engineRequests)
        }

    @Test
    fun notificationPreviousNextAndStopDriveTheAttachedSpeechSession() =
        withAttachedPlaybackFixture { fixture ->
            val controller = fixture.connectService()
            try {
                fixture.send(controller, SpeechMediaPlayer.ACTION_NEXT)
                assertEquals("second", fixture.awaitCurrentSegmentId("second"))
                fixture.send(controller, SpeechMediaPlayer.ACTION_PREVIOUS)
                assertEquals("first", fixture.awaitCurrentSegmentId("first"))
                fixture.send(controller, SpeechMediaPlayer.ACTION_STOP)
                fixture.awaitIdle()

                assertEquals(listOf("first", "second", "first"), fixture.requestedSegmentIds)
                assertTrue(fixture.sourceClosed)
            } finally {
                controller.release()
            }
        }

    @Test
    fun focusLossAndNoisyRoutePauseWhileFocusGainDoesNotResume() {
        val controller = RecordingController()
        val focus = SpeechAudioFocusController(context, controller)
        val noisy = BecomingNoisyReceiver(controller)

        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        focus.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        noisy.onReceive(context, Intent("fixture-unrelated"))
        noisy.onReceive(context, Intent(AudioManager.ACTION_AUDIO_BECOMING_NOISY))

        assertEquals(2, controller.pauseCalls)
        assertEquals(0, controller.resumeCalls)
    }

    private fun withPersistedPlaybackFixture(block: suspend (PersistedPlaybackFixture) -> Unit): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val mapper = BookMapperImpl()
        val repository = repository(database, mapper)
        database.bookDao().insertBook(mapper.toBookEntity(book()))
        val fixture = PersistedPlaybackFixture(
            database = database,
            repository = repository,
            committer = RoomSpeechProgressCommitter(
                GetBookByIdUseCase(repository),
                SetReadingProgressUseCase(repository),
                UpdateBookUseCase(repository),
            ),
        )
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private fun withAttachedPlaybackFixture(block: suspend (AttachedPlaybackFixture) -> Unit): Unit = runBlocking {
        val fixture = AttachedPlaybackFixture(SpeechProgressCommitter { })
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private open inner class AttachedPlaybackFixture(
        progressCommitter: SpeechProgressCommitter,
    ) : AutoCloseable {
        val first = segment("first", paragraph = 0)
        val second = segment("second", paragraph = 1)
        val source = SegmentSource(first, second)
        val engine = ControllableEngine()
        private val ownerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        protected val session = SpeechSession(
            engine = engine,
            progressCommitter = progressCommitter,
            highlightSink = SpeechHighlightSink { },
            ownerScope = ownerScope,
        )
        val requestedSegmentIds: List<String> get() = engine.requests.map { it.segment.id }
        val sourceClosed: Boolean get() = source.closed

        init {
            runBlocking {
                AppSpeechController.attach(session, SpeechNowPlaying("Fixture", "Chapter"))
                session.start(source, SpeechOptions(SpeechMode.OFFLINE, "zh-CN", null, 1f, 1f))
                awaitState { it is SpeechPlaybackState.Playing && it.segment.id == "first" }
                engine.awaitPending("first")
            }
        }

        fun connectService(): MediaController =
            connect(context, ComponentName(context, SpeechPlaybackService::class.java))

        fun send(controller: MediaController, action: String) {
            assertTrue(controller.customLayout.any { it.sessionCommand?.customAction == action })
            assertEquals(
                SessionResult.RESULT_SUCCESS,
                controller.sendCustomCommand(
                    SessionCommand(action, android.os.Bundle.EMPTY),
                    android.os.Bundle.EMPTY,
                ).get(10, TimeUnit.SECONDS).resultCode,
            )
        }

        suspend fun awaitCurrentSegmentId(expected: String): String =
            requireNotNull(awaitState { it.currentSegment()?.id == expected }.currentSegment()).id

        suspend fun awaitIdle() {
            awaitState { it is SpeechPlaybackState.Idle }
        }

        override fun close() {
            context.stopService(Intent(context, SpeechPlaybackService::class.java))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            runBlocking { session.closeAndJoin() }
            ownerScope.cancel()
        }
    }

    private inner class PersistedPlaybackFixture(
        private val database: AppDatabase,
        private val repository: BooksRepositoryImpl,
        committer: RoomSpeechProgressCommitter,
    ) : AttachedPlaybackFixture(committer) {
        val pausedSecondState get() = SpeechPlaybackState.Paused(second)
        val engineRequests get() = engine.requests.size

        suspend fun completeFirstSegmentAndPauseSecond() {
            engine.awaitPending("first")
            engine.complete("first", SpeechEngineResult.Completed)
            awaitState { it is SpeechPlaybackState.Playing && it.segment.id == "second" }
            engine.awaitPending("second")
            session.pause()
            awaitState { it == pausedSecondState }
        }

        suspend fun persistedLocator(): Locator? =
            repository.getBookById(BOOK_ID)?.locator?.let(Locator::fromJsonString)

        suspend fun recreateService() {
            val before = engine.requests.size
            connectService().release()
            context.stopService(Intent(context, SpeechPlaybackService::class.java))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()

            val recreated = connectService()
            try {
                assertFalse(recreated.playWhenReady)
                awaitState { it == pausedSecondState }
                assertEquals(before, engine.requests.size)
            } finally {
                recreated.release()
            }
        }

        override fun close() {
            super.close()
            database.close()
        }
    }

    private suspend fun awaitState(predicate: (SpeechPlaybackState) -> Boolean): SpeechPlaybackState =
        withTimeout(10_000) {
            AppSpeechController.snapshot.first { predicate(it.playbackState) }.playbackState
        }

    private class SegmentSource(vararg segments: SpeechSegment) : SpeechContentSource {
        private val values = segments.toList()
        private var index = 0
        var closed = false
            private set

        override suspend fun current() = values.getOrNull(index)
        override suspend fun next() = values.getOrNull(index + 1)?.also { index++ }
        override suspend fun previous() = values.getOrNull(index - 1)?.also { index-- }
        override suspend fun seek(position: SpeechPosition): SpeechSegment? {
            val target = values.indexOfFirst { it.position == position }
            if (target < 0) return null
            index = target
            return values[index]
        }

        override fun close() { closed = true }
    }

    private class ControllableEngine : SpeechEngine {
        data class Pending(val request: SpeechRequest, val result: CompletableDeferred<SpeechEngineResult>)
        override val id = "fixture"
        private val lock = Any()
        private val requestValues = mutableListOf<SpeechRequest>()
        private val pending = mutableListOf<Pending>()
        val requests: List<SpeechRequest> get() = synchronized(lock) { requestValues.toList() }

        override suspend fun voices(localeTag: String): List<SpeechVoice> = emptyList()
        override suspend fun speak(request: SpeechRequest): SpeechEngineResult {
            val result = CompletableDeferred<SpeechEngineResult>()
            synchronized(lock) {
                requestValues += request
                pending += Pending(request, result)
            }
            return result.await()
        }

        override suspend fun stop() {
            synchronized(lock) { pending.lastOrNull { !it.result.isCompleted } }
                ?.result?.complete(SpeechEngineResult.Cancelled)
        }

        override fun close() = Unit

        fun complete(segmentId: String, result: SpeechEngineResult) {
            synchronized(lock) {
                pending.last { it.request.segment.id == segmentId && !it.result.isCompleted }
            }.result.complete(result)
        }

        suspend fun awaitPending(segmentId: String) = withTimeout(10_000) {
            while (synchronized(lock) { pending.none { it.request.segment.id == segmentId && !it.result.isCompleted } }) {
                yield()
            }
        }
    }

    private class RecordingController : SpeechController {
        var pauseCalls = 0
        var resumeCalls = 0
        override fun pause() { pauseCalls++ }
        override fun resume() { resumeCalls++ }
        override fun next() = Unit
        override fun previous() = Unit
        override fun stop() = Unit
    }

    private fun repository(database: AppDatabase, mapper: BookMapperImpl) = BooksRepositoryImpl(
        appDb = database,
        bookDao = database.bookDao(),
        annotationDao = database.annotationDao(),
        noteDao = database.noteDao(),
        bookmarkDao = database.bookmarkDao(),
        readingActivityDao = database.readingActivityDao(),
        bookMapper = mapper,
        annotaionMapper = BookAnnotationMapperImpl(),
        bookmarkMapper = BookmarkMapperImpl(),
        noteMapper = NoteMapperImpl(),
        readingActiveMapper = ReadingActiveMapperImpl(),
        shelfMapper = ShelfMapperImpl(),
        bookShelfMapper = BookShelfMapperImpl(),
    )

    private fun connect(context: Context, component: ComponentName): MediaController =
        MediaController.Builder(context, SessionToken(context, component)).buildAsync().get(10, TimeUnit.SECONDS)

    private companion object {
        const val BOOK_ID = 9109L

        fun segment(id: String, paragraph: Int): SpeechSegment {
            val position = SpeechPosition(BOOK_ID, 2, 0, paragraph, 0)
            return SpeechSegment(
                id = id,
                position = position,
                partIndex = 0,
                text = id,
                locator = Locator(
                    id = id,
                    chapterIndex = 2,
                    startParagraphIndex = paragraph,
                    startTextOffset = 0,
                    endParagraphIndex = paragraph,
                    endTextOffset = id.length,
                    text = id,
                    progression = 0.5,
                ),
            )
        }

        fun book() = Book(
            id = BOOK_ID,
            title = "Fixture",
            author = "Author",
            description = null,
            filePath = "file:///fixture.epub",
            coverImage = null,
            scrollIndex = 0,
            scrollOffset = 0,
            progress = 0f,
            lastOpened = null,
            category = null,
            fileType = "epub",
            locator = "initial",
        )
    }
}

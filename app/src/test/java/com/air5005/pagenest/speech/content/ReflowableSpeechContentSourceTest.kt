package com.air5005.pagenest.speech.content

import android.graphics.RectF
import com.air5005.pagenest.speech.model.SpeechPosition
import com.wxn.base.bean.Book
import com.wxn.base.bean.BookChapter
import com.wxn.base.bean.ReaderText
import com.wxn.bookread.data.model.TextChapter
import com.wxn.bookread.data.model.TextLine
import com.wxn.bookread.data.model.TextPage
import com.wxn.bookread.provider.ChapterProvider
import com.wxn.reader.presentation.mainReader.PageViewController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

class ReflowableSpeechContentSourceTest {
    @Test
    fun `reflowable source groups lines by paragraph and crosses chapter`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(
                chapter = 0,
                page = 0,
                line(paragraph = 4, offset = 0, text = "第一行"),
                line(paragraph = 4, offset = 3, text = "第二行"),
            ),
            page(chapter = 1, page = 0, line(paragraph = 0, offset = 0, text = "下一章")),
        )
        val source = ReflowableSpeechContentSource(7, pages, SpeechSegmenter())

        assertEquals("第一行第二行", source.current()?.text)
        assertEquals("下一章", source.next()?.text)
        assertNull(source.next())
        assertEquals("第一行第二行", source.previous()?.text)
    }

    @Test
    fun `image and rule lines never become speech`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(
                chapter = 2,
                page = 3,
                line(paragraph = 0, offset = 0, text = "cover.jpg", image = true),
                line(paragraph = 1, offset = 0, text = "-----", rule = true),
                line(paragraph = 2, offset = 8, text = "  Read me  "),
            ),
        )
        val source = ReflowableSpeechContentSource(11, pages, SpeechSegmenter())

        val segment = source.current()

        assertEquals("Read me", segment?.text)
        assertEquals(10, segment?.locator?.startTextOffset)
        assertNull(source.next())
    }

    @Test
    fun `same paragraph on adjacent pages keeps each visible fragment stable`() = runTest {
        val pages = FakeSpeechPageNavigator(
            page(chapter = 3, page = 4, line(paragraph = 7, offset = 0, text = "page tail")),
            page(chapter = 3, page = 5, line(paragraph = 7, offset = 9, text = "page head")),
        )
        val source = ReflowableSpeechContentSource(13, pages, SpeechSegmenter())

        val first = source.current()!!
        val second = source.next()!!

        assertEquals("page tail", first.text)
        assertEquals(SpeechPosition(13, 3, 4, 7, 0), first.position)
        assertEquals("page head", second.text)
        assertEquals(SpeechPosition(13, 3, 5, 7, 9), second.position)
        assertTrue(first.id != second.id)
    }

    @Test
    fun `seek selects the split child containing the requested UTF-16 offset`() = runTest {
        val longText = "a".repeat(500) + "😀" + "b"
        val pages = FakeSpeechPageNavigator(
            page(chapter = 4, page = 2, line(paragraph = 6, offset = 20, text = longText)),
        )
        val source = ReflowableSpeechContentSource(17, pages, SpeechSegmenter())

        val selected = source.seek(SpeechPosition(17, 4, 2, 6, 522))

        assertEquals("😀b", selected?.text)
        assertEquals(520, selected?.locator?.startTextOffset)
    }

    private class FakeSpeechPageNavigator(
        vararg pages: SpeechPageSnapshot,
    ) : SpeechPageNavigator {
        private data class Candidate(
            val index: Int,
            override val snapshot: SpeechPageSnapshot,
        ) : LoadedSpeechPage

        private val values = pages.toList()
        private var index = 0

        override suspend fun currentSpeechPage(): SpeechPageSnapshot? = values.getOrNull(index)

        override suspend fun nextSpeechPage(): SpeechPageSnapshot? {
            if (index >= values.lastIndex) return null
            index++
            return values[index]
        }

        override suspend fun previousSpeechPage(): SpeechPageSnapshot? {
            if (index <= 0) return null
            index--
            return values[index]
        }

        override suspend fun loadSpeechPage(chapterIndex: Int, pageIndex: Int): LoadedSpeechPage? {
            val target = values.indexOfFirst {
                it.chapterIndex == chapterIndex && it.pageIndex == pageIndex
            }
            if (target < 0) return null
            return Candidate(target, values[target])
        }

        override suspend fun activateSpeechPage(candidate: LoadedSpeechPage): Boolean {
            val loaded = candidate as? Candidate ?: return false
            index = loaded.index
            return true
        }

        override fun close() = Unit
    }

    companion object {
        private fun line(
            paragraph: Int,
            offset: Int,
            text: String,
            image: Boolean = false,
            rule: Boolean = false,
        ) = SpeechLineSnapshot(
            paragraphIndex = paragraph,
            text = text,
            charStartOffset = offset,
            charEndOffset = offset + text.length,
            isImage = image,
            isLine = rule,
        )

        private fun page(
            chapter: Int,
            page: Int,
            vararg lines: SpeechLineSnapshot,
        ) = SpeechPageSnapshot(
            chapterIndex = chapter,
            pageIndex = page,
            progression = (chapter * 10 + page) / 100.0,
            lines = lines.toList(),
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageViewControllerSpeechSnapshotTest {
    private val mainDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(mainDispatcher)
    }

    @After
    fun tearDown() {
        unmockkObject(ChapterProvider)
        Dispatchers.resetMain()
    }

    @Test
    fun `controller snapshot is immutable after parser TextLine mutation`() = runTest {
        val textLine = TextLine(
            text = "immutable",
            paragraphIndex = 2,
            charStartOffset = 4,
            charEndOffset = 13,
        )
        val controller = controllerWith(TextPage(index = 6, textLines = arrayListOf(textLine)))

        val snapshot = controller.currentSpeechPage()
        textLine.text = "mutated"
        textLine.paragraphIndex = 99

        assertEquals("immutable", snapshot?.lines?.single()?.text)
        assertEquals(2, snapshot?.lines?.single()?.paragraphIndex)
        assertEquals(6, snapshot?.pageIndex)
    }

    @Test
    fun `next speech page awaits an unloaded chapter before returning its first page`() = runTest {
        val parsed = CompletableDeferred<Unit>()
        val target = textChapter(1, TextPage(index = 0, textLines = arrayListOf(textLine("next"))))
        val controller = controllerWith(TextPage(index = 0, textLines = arrayListOf(textLine("current"))))
        controller.durChapterIndex = 0
        controller.curTextChapter = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
        )
        controller.chapterSize = 2
        controller.book = book()
        every { controller.getChapterByIdUserCase(1, 1) } returns flowOf(bookChapter(1))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            parsed.await()
            listOf(ReaderText.Text(line = "next"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns target

        val moving = async { controller.nextSpeechPage() }
        testScheduler.runCurrent()

        assertTrue("navigation must wait for parsing", !moving.isCompleted)
        parsed.complete(Unit)
        testScheduler.advanceUntilIdle()

        assertEquals("next", moving.await()?.lines?.single()?.text)
        assertEquals(1, controller.durChapterIndex)
        assertEquals(target, controller.curTextChapter)
    }

    @Test
    fun `speech seek loads a valid unloaded chapter before selecting its page`() = runTest {
        val target = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("first"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("sought"))),
        )
        val controller = controllerWith(TextPage(index = 0, textLines = arrayListOf(textLine("current"))))
        controller.chapterSize = 3
        controller.book = book()
        every { controller.getChapterByIdUserCase(1, 2) } returns flowOf(bookChapter(2))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } returns
            listOf(ReaderText.Text(line = "first sought"))
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns target

        val candidate = requireNotNull(controller.loadSpeechPage(chapterIndex = 2, pageIndex = 1))
        val activated = controller.activateSpeechPage(candidate)

        assertTrue(activated)
        assertEquals("sought", candidate.snapshot.lines.single().text)
        assertEquals(2, controller.durChapterIndex)
        assertEquals(1, controller.durPageIndex)
    }

    @Test
    fun `valid unloaded source seek loads once and commits the validated candidate`() = runTest {
        val validated = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("validated"))),
        )
        val drifted = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("drifted", paragraphIndex = 9))),
        )
        val controller = unloadedController()
        var layoutLoads = 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            layoutLoads++
            if (layoutLoads == 1) validated else drifted
        }
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        requireNotNull(source.current())

        val result = source.seek(SpeechPosition(1, 2, 0, paragraphIndex = 0, textOffset = 0))

        assertEquals(1, layoutLoads)
        assertEquals("validated", result?.text)
        assertSame(validated, controller.curTextChapter)
        assertEquals(2, controller.durChapterIndex)
        assertEquals(0, controller.durPageIndex)
        assertEquals(result, source.current())
    }

    @Test
    fun `invalid unloaded source seek loads once without mutating controller or source`() = runTest {
        val invalid = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("other", paragraphIndex = 9))),
        )
        val controller = unloadedController()
        val originalChapter = requireNotNull(controller.curTextChapter)
        var layoutLoads = 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            layoutLoads++
            invalid
        }
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val originalSegment = requireNotNull(source.current())

        val result = source.seek(SpeechPosition(1, 2, 0, paragraphIndex = 0, textOffset = 0))

        assertNull(result)
        assertEquals(1, layoutLoads)
        assertSame(originalChapter, controller.curTextChapter)
        assertEquals(0, controller.durChapterIndex)
        assertEquals(0, controller.durPageIndex)
        assertEquals(originalSegment, source.current())
    }

    @Test
    fun `layout refresh invalidates a loaded speech candidate before activation`() = runTest {
        val target = textChapter(
            2,
            TextPage(index = 0, textLines = arrayListOf(textLine("target"))),
        )
        val controller = unloadedController()
        val originalChapter = requireNotNull(controller.curTextChapter)
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns target
        val candidate = requireNotNull(controller.loadSpeechPage(chapterIndex = 2, pageIndex = 0))

        controller.loadContent(resetPageOffset = true)
        val activated = controller.activateSpeechPage(candidate)

        assertFalse(activated)
        assertSame(originalChapter, controller.curTextChapter)
        assertEquals(0, controller.durChapterIndex)
        assertEquals(0, controller.durPageIndex)
    }

    @Test
    fun `source seek during layout reload never activates old cache and recovers on new layout`() = runTest {
        val controller = reloadableController()
        controller.scope = this
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val oldSegment = requireNotNull(source.current())
        val reloadEntered = CompletableDeferred<Unit>()
        val allowCurrentReload = CompletableDeferred<Unit>()
        val reloadTailEntered = CompletableDeferred<Unit>()
        val allowReloadTail = CompletableDeferred<Unit>()
        val refreshed = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("fresh", paragraphIndex = 1))),
        )
        every { controller.getChapterByIdUserCase(1, 0) } returns flowOf(bookChapter(0))
        every { controller.getChapterByIdUserCase(1, 1) } returns flow {
            reloadTailEntered.complete(Unit)
            allowReloadTail.await()
            throw NoSuchElementException("end of fixture")
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            reloadEntered.complete(Unit)
            allowCurrentReload.await()
            listOf(ReaderText.Text(line = "fresh"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } returns refreshed

        controller.loadContent(resetPageOffset = true)
        reloadEntered.await()
        val duringReload = source.seek(oldSegment.position)
        allowCurrentReload.complete(Unit)
        reloadTailEntered.await()
        val afterCurrentInstallButBeforeReloadCompletion =
            controller.loadSpeechPage(chapterIndex = 0, pageIndex = 0)
        allowReloadTail.complete(Unit)
        val refreshedPosition = SpeechPosition(1, 0, 0, paragraphIndex = 1, textOffset = 0)
        val recovered = awaitSeek(source, refreshedPosition)

        assertNull(duringReload)
        assertNull(afterCurrentInstallButBeforeReloadCompletion)
        assertEquals("fresh", recovered.text)
        assertSame(refreshed, controller.curTextChapter)
        assertEquals(recovered, source.current())
    }

    @Test
    fun `older overlapping reload cannot overwrite the latest layout`() = runTest {
        val controller = reloadableController()
        controller.scope = this
        val firstReloadEntered = CompletableDeferred<Unit>()
        val allowFirstReload = CompletableDeferred<Unit>()
        val secondReloadEntered = CompletableDeferred<Unit>()
        val allowSecondReload = CompletableDeferred<Unit>()
        val bothReloadsAdvancedPastCurrent = CompletableDeferred<Unit>()
        var parserCalls = 0
        val reloadTailCalls = AtomicInteger()
        every { controller.getChapterByIdUserCase(1, 0) } returns flowOf(bookChapter(0))
        every { controller.getChapterByIdUserCase(1, 1) } answers {
            flow {
                if (reloadTailCalls.incrementAndGet() == 2) {
                    bothReloadsAdvancedPastCurrent.complete(Unit)
                }
                throw NoSuchElementException("end of fixture")
            }
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            parserCalls++
            when (parserCalls) {
                1 -> {
                    firstReloadEntered.complete(Unit)
                    allowFirstReload.await()
                    listOf(ReaderText.Text(line = "older"))
                }

                2 -> {
                    secondReloadEntered.complete(Unit)
                    allowSecondReload.await()
                    listOf(ReaderText.Text(line = "latest"))
                }

                else -> error("unexpected parser call $parserCalls")
            }
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val text = secondArg<List<ReaderText>>()
                .filterIsInstance<ReaderText.Text>()
                .single()
                .line
            textChapter(
                0,
                TextPage(index = 0, textLines = arrayListOf(textLine(text))),
            )
        }

        controller.loadContent(resetPageOffset = true)
        firstReloadEntered.await()
        controller.loadContent(resetPageOffset = true)
        secondReloadEntered.await()
        allowSecondReload.complete(Unit)
        while (controller.curTextChapter?.pages?.single()?.textLines?.single()?.text != "latest") {
            yield()
        }
        allowFirstReload.complete(Unit)
        bothReloadsAdvancedPastCurrent.await()

        assertEquals(
            "latest",
            controller.curTextChapter?.pages?.single()?.textLines?.single()?.text,
        )
    }

    @Test
    fun `manual load started before layout reload cannot overwrite refreshed reader state`() = runTest {
        val controller = manualNavigationController()
        controller.scope = this
        val listener = RecordingClickListener()
        controller.clickListener = listener
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val oldManualEntered = CompletableDeferred<Unit>()
        val allowOldManual = CompletableDeferred<Unit>()
        val oldManualLayoutBuilt = CompletableDeferred<Unit>()
        val chapterOneLoads = AtomicInteger()
        every { controller.getChapterByIdUserCase(1, any()) } answers {
            flowOf(bookChapter(secondArg()))
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            val chapter = thirdArg<BookChapter>()
            when {
                chapter.chapterIndex != 1 -> listOf(ReaderText.Text(line = "fresh-${chapter.chapterIndex}"))
                chapterOneLoads.incrementAndGet() == 1 -> {
                    oldManualEntered.complete(Unit)
                    allowOldManual.await()
                    listOf(ReaderText.Text(line = "stale-manual"))
                }

                else -> listOf(ReaderText.Text(line = "fresh-layout"))
            }
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<BookChapter>()
            val text = secondArg<List<ReaderText>>()
                .filterIsInstance<ReaderText.Text>()
                .single()
                .line
            textChapter(
                chapter.chapterIndex,
                TextPage(index = 0, textLines = arrayListOf(textLine(text, paragraphIndex = 1))),
            ).also {
                if (text == "stale-manual") oldManualLayoutBuilt.complete(Unit)
            }
        }

        assertTrue(controller.moveToNextChapter(upContent = true))
        oldManualEntered.await()
        controller.loadContent(resetPageOffset = true)
        val freshPosition = SpeechPosition(1, 1, 0, paragraphIndex = 1, textOffset = 0)
        val refreshed = awaitSeek(source, freshPosition)
        val callbacksAfterRefresh = listener.pageChanges.get()

        allowOldManual.complete(Unit)
        oldManualLayoutBuilt.await()
        testScheduler.advanceUntilIdle()
        val acceptedAfterOldManual = requireNotNull(
            controller.loadSpeechPage(chapterIndex = 1, pageIndex = 0),
        )

        assertEquals("fresh-layout", refreshed.text)
        assertEquals("fresh-layout", currentChapterText(controller))
        assertEquals("fresh-layout", acceptedAfterOldManual.snapshot.lines.single().text)
        assertEquals(refreshed, source.current())
        assertEquals(callbacksAfterRefresh, listener.pageChanges.get())
    }

    @Test
    fun `manual navigation without a newer operation installs its current and adjacent chapters`() = runTest {
        val controller = manualNavigationController()
        val currentBuilt = CompletableDeferred<Unit>()
        val adjacentBuilt = CompletableDeferred<Unit>()
        every { controller.getChapterByIdUserCase(1, any()) } answers {
            flowOf(bookChapter(secondArg()))
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            val chapter = thirdArg<BookChapter>()
            listOf(ReaderText.Text(line = "manual-${chapter.chapterIndex}"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<BookChapter>()
            val loaded = textChapter(
                chapter.chapterIndex,
                TextPage(
                    index = 0,
                    textLines = arrayListOf(textLine("manual-${chapter.chapterIndex}")),
                ),
            )
            if (chapter.chapterIndex == 1) currentBuilt.complete(Unit) else adjacentBuilt.complete(Unit)
            loaded
        }

        assertTrue(controller.moveToNextChapter(upContent = true))
        currentBuilt.await()
        adjacentBuilt.await()
        awaitCurrentChapterText(controller, "manual-1")
        val candidate = requireNotNull(controller.loadSpeechPage(chapterIndex = 1, pageIndex = 0))

        assertEquals("manual-1", currentChapterText(controller))
        assertEquals("manual-2", controller.nextTextChapter?.pages?.single()?.textLines?.single()?.text)
        assertEquals("manual-1", candidate.snapshot.lines.single().text)
    }

    @Test
    fun `latest manual navigation wins when two manual operations finish out of order`() = runTest {
        val controller = manualNavigationController().apply { chapterSize = 4 }
        val firstCurrentEntered = CompletableDeferred<Unit>()
        val firstAdjacentEntered = CompletableDeferred<Unit>()
        val allowFirstCurrent = CompletableDeferred<Unit>()
        val allowFirstAdjacent = CompletableDeferred<Unit>()
        val firstCurrentBuilt = CompletableDeferred<Unit>()
        val firstAdjacentBuilt = CompletableDeferred<Unit>()
        val chapterTwoLoads = AtomicInteger()
        every { controller.getChapterByIdUserCase(1, any()) } answers {
            flowOf(bookChapter(secondArg()))
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            val chapter = thirdArg<BookChapter>()
            when {
                chapter.chapterIndex == 1 -> {
                    firstCurrentEntered.complete(Unit)
                    allowFirstCurrent.await()
                    listOf(ReaderText.Text(line = "older-1"))
                }

                chapter.chapterIndex == 2 && chapterTwoLoads.incrementAndGet() == 1 -> {
                    firstAdjacentEntered.complete(Unit)
                    allowFirstAdjacent.await()
                    listOf(ReaderText.Text(line = "older-2"))
                }

                else -> listOf(ReaderText.Text(line = "latest-${chapter.chapterIndex}"))
            }
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<BookChapter>()
            val text = secondArg<List<ReaderText>>()
                .filterIsInstance<ReaderText.Text>()
                .single()
                .line
            textChapter(
                chapter.chapterIndex,
                TextPage(index = 0, textLines = arrayListOf(textLine(text))),
            ).also {
                if (text == "older-1") firstCurrentBuilt.complete(Unit)
                if (text == "older-2") firstAdjacentBuilt.complete(Unit)
            }
        }

        assertTrue(controller.moveToNextChapter(upContent = true))
        firstCurrentEntered.await()
        firstAdjacentEntered.await()
        assertTrue(controller.moveToNextChapter(upContent = true))
        awaitCurrentChapterText(controller, "latest-2")

        allowFirstCurrent.complete(Unit)
        allowFirstAdjacent.complete(Unit)
        firstCurrentBuilt.await()
        firstAdjacentBuilt.await()
        testScheduler.advanceUntilIdle()

        assertEquals(2, controller.durChapterIndex)
        assertEquals("latest-2", currentChapterText(controller))
        assertEquals("latest-3", controller.nextTextChapter?.pages?.single()?.textLines?.single()?.text)
    }

    @Test
    fun `reset invalidates a manual load that was already parsing`() = runTest {
        val controller = manualNavigationController()
        val oldManualEntered = CompletableDeferred<Unit>()
        val allowOldManual = CompletableDeferred<Unit>()
        val oldManualBuilt = CompletableDeferred<Unit>()
        val listener = RecordingClickListener()
        controller.clickListener = listener
        every { controller.getChapterByIdUserCase(1, any()) } answers {
            flowOf(bookChapter(secondArg()))
        }
        every { controller.getChapterCountByBookIdUserCase(1) } returns flowOf(3)
        coEvery { controller.getAnnotationsUseCase(1) } returns flowOf(emptyList())
        coEvery { controller.getNotesForBookUseCase(1) } returns flowOf(emptyList())
        coEvery { controller.getBookmarksForBookUseCase(1) } returns flowOf(emptyList())
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            val chapter = thirdArg<BookChapter>()
            if (chapter.chapterIndex == 1) {
                oldManualEntered.complete(Unit)
                allowOldManual.await()
            }
            listOf(ReaderText.Text(line = "old-${chapter.chapterIndex}"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<BookChapter>()
            textChapter(
                chapter.chapterIndex,
                TextPage(index = 0, textLines = arrayListOf(textLine("old-${chapter.chapterIndex}"))),
            ).also {
                if (chapter.chapterIndex == 1) oldManualBuilt.complete(Unit)
            }
        }

        assertTrue(controller.moveToNextChapter(upContent = true))
        oldManualEntered.await()
        controller.resetBook(book()) { }
        val callbacksAfterReset = listener.pageChanges.get()

        allowOldManual.complete(Unit)
        oldManualBuilt.await()
        testScheduler.advanceUntilIdle()

        assertNull(controller.prevTextChapter)
        assertNull(controller.curTextChapter)
        assertNull(controller.nextTextChapter)
        assertEquals(callbacksAfterReset, listener.pageChanges.get())
    }

    @Test
    fun `clear invalidates a manual load that was already parsing`() = runTest {
        val controller = manualNavigationController()
        val oldManualEntered = CompletableDeferred<Unit>()
        val allowOldManual = CompletableDeferred<Unit>()
        val oldManualBuilt = CompletableDeferred<Unit>()
        val listener = RecordingClickListener()
        controller.clickListener = listener
        every { controller.getChapterByIdUserCase(1, any()) } answers {
            flowOf(bookChapter(secondArg()))
        }
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            val chapter = thirdArg<BookChapter>()
            if (chapter.chapterIndex == 1) {
                oldManualEntered.complete(Unit)
                allowOldManual.await()
            }
            listOf(ReaderText.Text(line = "old-${chapter.chapterIndex}"))
        }
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        mockkObject(ChapterProvider)
        coEvery { ChapterProvider.getTextChapter(any(), any(), any(), any()) } coAnswers {
            val chapter = firstArg<BookChapter>()
            textChapter(
                chapter.chapterIndex,
                TextPage(index = 0, textLines = arrayListOf(textLine("old-${chapter.chapterIndex}"))),
            ).also {
                if (chapter.chapterIndex == 1) oldManualBuilt.complete(Unit)
            }
        }

        assertTrue(controller.moveToNextChapter(upContent = true))
        oldManualEntered.await()
        controller.clear()
        val callbacksAfterClear = listener.pageChanges.get()

        allowOldManual.complete(Unit)
        oldManualBuilt.await()
        testScheduler.advanceUntilIdle()

        assertNull(controller.prevTextChapter)
        assertNull(controller.curTextChapter)
        assertNull(controller.nextTextChapter)
        assertEquals(callbacksAfterClear, listener.pageChanges.get())
    }

    @Test
    fun `failed reload clears its fence and keeps the current valid layout available`() = runTest {
        val controller = reloadableController()
        controller.scope = this
        val originalChapter = requireNotNull(controller.curTextChapter)
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val original = requireNotNull(source.current())
        val failureEntered = CompletableDeferred<Unit>()
        val allowFailure = CompletableDeferred<Unit>()
        every { controller.getChapterByIdUserCase(1, 0) } returns flowOf(bookChapter(0))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            failureEntered.complete(Unit)
            allowFailure.await()
            throw IllegalStateException("fixture reload failure")
        }

        controller.loadContent(resetPageOffset = true)
        failureEntered.await()
        assertNull(source.seek(original.position))
        allowFailure.complete(Unit)
        val recovered = awaitSeek(source, original.position)

        assertEquals(original, recovered)
        assertSame(originalChapter, controller.curTextChapter)
    }

    @Test
    fun `cancelled reload clears its fence and keeps the current valid layout available`() = runTest {
        val controller = reloadableController()
        controller.scope = this
        val originalChapter = requireNotNull(controller.curTextChapter)
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val original = requireNotNull(source.current())
        val cancellationEntered = CompletableDeferred<Unit>()
        val allowCancellation = CompletableDeferred<Unit>()
        every { controller.getChapterByIdUserCase(1, 0) } returns flowOf(bookChapter(0))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } coAnswers {
            cancellationEntered.complete(Unit)
            allowCancellation.await()
            throw CancellationException("fixture reload cancellation")
        }

        controller.loadContent(resetPageOffset = true)
        cancellationEntered.await()
        assertNull(source.seek(original.position))
        allowCancellation.complete(Unit)
        val recovered = awaitSeek(source, original.position)

        assertEquals(original, recovered)
        assertSame(originalChapter, controller.curTextChapter)
    }

    @Test
    fun `invalid locator on an existing page leaves real controller and source position unchanged`() = runTest {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("next"))),
        )
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        val current = requireNotNull(source.current())
        val before = requireNotNull(controller.currentSpeechPage())

        val result = source.seek(SpeechPosition(1, 3, 1, paragraphIndex = 99, textOffset = 50))

        assertNull(result)
        assertEquals(3, controller.durChapterIndex)
        assertEquals(0, controller.durPageIndex)
        assertEquals(before, controller.currentSpeechPage())
        assertEquals(current, source.current())
        assertEquals("next", source.next()?.text)
        assertNull(source.next())
    }

    @Test
    fun `valid locator on an existing page commits controller and source position once`() = runTest {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
            TextPage(index = 1, textLines = arrayListOf(textLine("target"))),
        )
        val source = ReflowableSpeechContentSource(1, controller, SpeechSegmenter())
        requireNotNull(source.current())

        val result = source.seek(SpeechPosition(1, 3, 1, paragraphIndex = 0, textOffset = 0))

        assertEquals("target", result?.text)
        assertEquals(3, controller.durChapterIndex)
        assertEquals(1, controller.durPageIndex)
        assertEquals(result, source.current())
    }

    private fun controllerWith(vararg pages: TextPage): PageViewController {
        val controller = PageViewController(
            context = mockk(relaxed = true),
            getChapterByIdUserCase = mockk(relaxed = true),
            getChapterCountByBookIdUserCase = mockk(relaxed = true),
            getAnnotationsUseCase = mockk(relaxed = true),
            getNotesForBookUseCase = mockk(relaxed = true),
            getBookmarksForBookUseCase = mockk(relaxed = true),
            updateChapterWordCountUserCase = mockk(relaxed = true),
            updateBookUseCase = mockk(relaxed = true),
            appPreferencesUtil = mockk(relaxed = true),
            textParser = mockk(relaxed = true),
        )
        controller.durChapterIndex = 3
        controller.durPageIndex = 0
        controller.chapterSize = 4
        controller.curTextChapter = TextChapter(
            position = 3,
            title = "Chapter",
            chapterId = 1,
            pages = pages.toList(),
            pageLines = pages.map { it.textLines.size },
            pageLengths = pages.map { it.text.length },
            chaptersSize = 1,
        )
        return controller
    }

    private fun unloadedController(): PageViewController {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
        )
        controller.durChapterIndex = 0
        controller.curTextChapter = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("current"))),
        )
        controller.chapterSize = 3
        controller.book = book()
        every { controller.getChapterByIdUserCase(1, 2) } returns flowOf(bookChapter(2))
        coEvery { controller.textParser.parsedChapterData(any(), any(), any()) } returns
            listOf(ReaderText.Text(line = "target"))
        coEvery { controller.textParser.parseCss(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { controller.appPreferencesUtil.chineseConverterType() } returns 0
        return controller
    }

    private fun reloadableController(): PageViewController {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("old"))),
        )
        controller.durChapterIndex = 0
        controller.curTextChapter = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("old"))),
        )
        controller.chapterSize = 1
        controller.book = book()
        controller.isInitFinish = true
        return controller
    }

    private fun manualNavigationController(): PageViewController {
        val controller = controllerWith(
            TextPage(index = 0, textLines = arrayListOf(textLine("chapter-0"))),
        )
        controller.durChapterIndex = 0
        controller.curTextChapter = textChapter(
            0,
            TextPage(index = 0, textLines = arrayListOf(textLine("chapter-0"))),
        )
        controller.nextTextChapter = null
        controller.chapterSize = 3
        controller.book = book()
        controller.isInitFinish = true
        return controller
    }

    private fun currentChapterText(controller: PageViewController): String? =
        controller.curTextChapter?.pages?.single()?.textLines?.single()?.text

    private class RecordingClickListener : PageViewController.OnClickListener {
        val pageChanges = AtomicInteger()

        override fun onCenterClick() = Unit
        override fun onLinkClick(href: String?, clickX: Float, clickY: Float) = Unit
        override fun onPageChange() {
            pageChanges.incrementAndGet()
        }
        override fun onSelectedText(startX: Float, startY: Float, endX: Float, endY: Float) = Unit
        override fun onSelectedCancel() = Unit
        override fun onCheckedAnnotation(annotationIds: List<String>, rect: RectF) = Unit
        override fun onCheckedNote(noteId: String, rect: RectF) = Unit
    }

    private suspend fun awaitSeek(
        source: ReflowableSpeechContentSource,
        position: SpeechPosition,
    ) = withTimeout(5_000) {
        while (true) {
            source.seek(position)?.let { return@withTimeout it }
            yield()
        }
        error("unreachable")
    }

    private suspend fun awaitCurrentChapterText(
        controller: PageViewController,
        expected: String,
    ) = withTimeout(5_000) {
        while (currentChapterText(controller) != expected) {
            yield()
        }
    }

    private fun textLine(text: String, paragraphIndex: Int = 0) = TextLine(
        text = text,
        paragraphIndex = paragraphIndex,
        charStartOffset = 0,
        charEndOffset = text.length,
    )

    private fun textChapter(position: Int, vararg pages: TextPage) = TextChapter(
        position = position,
        title = "Chapter $position",
        chapterId = position.toLong(),
        pages = pages.toList(),
        pageLines = pages.map { it.textLines.size },
        pageLengths = pages.map { it.text.length },
        chaptersSize = 3,
    )

    private fun bookChapter(index: Int) = BookChapter(
        bookId = 1,
        chapterIndex = index,
        chapterName = "Chapter $index",
        chaptersSize = 3,
    )

    private fun book() = Book(
        id = 1,
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
    )
}

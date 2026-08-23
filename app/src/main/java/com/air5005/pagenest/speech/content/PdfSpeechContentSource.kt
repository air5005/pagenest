package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class PdfSpeechAvailability { READABLE, SCANNED }

class PdfSpeechContentSource(
    private val bookId: Long,
    private val document: PdfSpeechDocument,
    private val segmenter: SpeechSegmenter,
) : SpeechContentSource {
    private val preparationMutex = Mutex()
    private var prepared = false
    private var closed = false
    private var availability: PdfSpeechAvailability? = null
    private var segments: List<SpeechSegment> = emptyList()
    private var segmentIndex = -1

    suspend fun availability(): PdfSpeechAvailability {
        prepare()
        return checkNotNull(availability)
    }

    override suspend fun current(): SpeechSegment? {
        prepare()
        if (segmentIndex < 0 && segments.isNotEmpty()) segmentIndex = 0
        return segments.getOrNull(segmentIndex)
    }

    override suspend fun next(): SpeechSegment? {
        prepare()
        if (segmentIndex < 0) return current()
        val next = segments.getOrNull(segmentIndex + 1) ?: return null
        segmentIndex++
        return next
    }

    override suspend fun previous(): SpeechSegment? {
        prepare()
        if (segmentIndex < 0) return current()
        val previous = segments.getOrNull(segmentIndex - 1) ?: return null
        segmentIndex--
        return previous
    }

    override suspend fun seek(position: SpeechPosition): SpeechSegment? {
        prepare()
        if (position.bookId != bookId || position.pageIndex == null) return null
        val exactPosition = segments.indexOfFirst { it.position == position }
        segmentIndex = if (exactPosition >= 0) {
            exactPosition
        } else {
            segments.indexOfFirst { segment ->
                segment.position.chapterIndex == position.chapterIndex &&
                    segment.position.pageIndex == position.pageIndex &&
                    segment.position.paragraphIndex == position.paragraphIndex &&
                    position.textOffset >= segment.locator.startTextOffset &&
                    position.textOffset < segment.locator.endTextOffset
            }
        }
        return segments.getOrNull(segmentIndex)
    }

    override fun close() {
        if (closed) return
        closed = true
        segments = emptyList()
        segmentIndex = -1
        document.close()
    }

    private suspend fun prepare() {
        ensureOpen()
        if (prepared) return
        preparationMutex.withLock {
            ensureOpen()
            if (prepared) return@withLock
            try {
                val extractedSegments = mutableListOf<SpeechSegment>()
                repeat(document.pageCount) { pageIndex ->
                    val text = document.pageText(pageIndex)
                    if (text.isNotEmpty()) {
                        val pageSegments = segmenter.fromParagraph(
                            position = SpeechPosition(
                                bookId = bookId,
                                chapterIndex = PDF_CHAPTER_INDEX,
                                pageIndex = pageIndex,
                                paragraphIndex = PDF_PARAGRAPH_INDEX,
                                textOffset = 0,
                            ),
                            text = text,
                            progression = pageIndex.toDouble() / document.pageCount.coerceAtLeast(1),
                        )
                        extractedSegments += pageSegments.mapIndexed { index, segment ->
                            segment.copy(completesPage = index == pageSegments.lastIndex)
                        }
                    }
                }
                segments = extractedSegments
                availability = if (segments.isEmpty()) {
                    PdfSpeechAvailability.SCANNED
                } else {
                    PdfSpeechAvailability.READABLE
                }
                prepared = true
            } catch (cancellation: CancellationException) {
                close()
                throw cancellation
            }
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Speech content source is closed" }
    }

    private companion object {
        const val PDF_CHAPTER_INDEX = 0
        const val PDF_PARAGRAPH_INDEX = 0
    }
}

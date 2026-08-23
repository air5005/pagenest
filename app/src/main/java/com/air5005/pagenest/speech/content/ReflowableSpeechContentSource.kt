package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment

data class SpeechLineSnapshot(
    val paragraphIndex: Int,
    val text: String,
    val charStartOffset: Int,
    val charEndOffset: Int,
    val isImage: Boolean,
    val isLine: Boolean,
)

data class SpeechPageSnapshot(
    val chapterIndex: Int,
    val pageIndex: Int,
    val progression: Double,
    val lines: List<SpeechLineSnapshot>,
)

interface SpeechPageNavigator : AutoCloseable {
    suspend fun currentSpeechPage(): SpeechPageSnapshot?
    suspend fun nextSpeechPage(): SpeechPageSnapshot?
    suspend fun previousSpeechPage(): SpeechPageSnapshot?
    suspend fun previewSpeechPage(chapterIndex: Int, pageIndex: Int): SpeechPageSnapshot?
    suspend fun seekSpeechPage(chapterIndex: Int, pageIndex: Int): SpeechPageSnapshot?
    override fun close()
}

class ReflowableSpeechContentSource(
    private val bookId: Long,
    private val navigator: SpeechPageNavigator,
    private val segmenter: SpeechSegmenter,
) : SpeechContentSource {
    private var page: SpeechPageSnapshot? = null
    private var segments: List<SpeechSegment> = emptyList()
    private var segmentIndex = -1
    private var closed = false

    override suspend fun current(): SpeechSegment? {
        ensureOpen()
        if (segmentIndex >= 0) return segments.getOrNull(segmentIndex)
        return selectFirstNonEmpty(navigator.currentSpeechPage(), Direction.FORWARD)
    }

    override suspend fun next(): SpeechSegment? {
        ensureOpen()
        if (segmentIndex < 0) return current()
        segments.getOrNull(segmentIndex + 1)?.let {
            segmentIndex++
            return it
        }
        return selectFirstNonEmpty(navigator.nextSpeechPage(), Direction.FORWARD)
    }

    override suspend fun previous(): SpeechSegment? {
        ensureOpen()
        if (segmentIndex < 0) return current()
        segments.getOrNull(segmentIndex - 1)?.let {
            segmentIndex--
            return it
        }
        return selectFirstNonEmpty(navigator.previousSpeechPage(), Direction.BACKWARD)
    }

    override suspend fun seek(position: SpeechPosition): SpeechSegment? {
        ensureOpen()
        if (position.bookId != bookId || position.pageIndex == null) return null
        val preview = navigator.previewSpeechPage(position.chapterIndex, position.pageIndex) ?: return null
        if (findSegmentIndex(segmentsForPage(preview), position) < 0) return null
        val targetPage = navigator.seekSpeechPage(position.chapterIndex, position.pageIndex) ?: return null
        setPage(targetPage)
        segmentIndex = findSegmentIndex(segments, position)
        return segments.getOrNull(segmentIndex)
    }

    override fun close() {
        if (closed) return
        closed = true
        segments = emptyList()
        segmentIndex = -1
        page = null
        navigator.close()
    }

    private suspend fun selectFirstNonEmpty(
        initialPage: SpeechPageSnapshot?,
        direction: Direction,
    ): SpeechSegment? {
        var candidate = initialPage
        while (candidate != null) {
            setPage(candidate)
            if (segments.isNotEmpty()) {
                segmentIndex = if (direction == Direction.FORWARD) 0 else segments.lastIndex
                return segments[segmentIndex]
            }
            candidate = when (direction) {
                Direction.FORWARD -> navigator.nextSpeechPage()
                Direction.BACKWARD -> navigator.previousSpeechPage()
            }
        }
        return null
    }

    private fun setPage(snapshot: SpeechPageSnapshot) {
        page = snapshot
        segments = segmentsForPage(snapshot)
        segmentIndex = -1
    }

    private fun segmentsForPage(snapshot: SpeechPageSnapshot): List<SpeechSegment> = snapshot.lines
        .asSequence()
        .filterNot { it.isImage || it.isLine || it.text.isBlank() }
        .groupBy { it.paragraphIndex }
        .values
        .flatMap { paragraphLines ->
            val orderedLines = paragraphLines.sortedBy { it.charStartOffset }
            val first = orderedLines.first()
            val paragraphText = orderedLines.joinToString(separator = "") { it.text }
            segmenter.fromParagraph(
                position = SpeechPosition(
                    bookId = bookId,
                    chapterIndex = snapshot.chapterIndex,
                    pageIndex = snapshot.pageIndex,
                    paragraphIndex = first.paragraphIndex,
                    textOffset = first.charStartOffset,
                ),
                text = paragraphText,
                progression = snapshot.progression,
            )
        }

    private fun findSegmentIndex(
        candidates: List<SpeechSegment>,
        position: SpeechPosition,
    ): Int {
        val exactPosition = candidates.indexOfFirst { it.position == position }
        if (exactPosition >= 0) return exactPosition
        return candidates.indexOfFirst { segment ->
            segment.position.chapterIndex == position.chapterIndex &&
                segment.position.pageIndex == position.pageIndex &&
                segment.position.paragraphIndex == position.paragraphIndex &&
                position.textOffset >= segment.locator.startTextOffset &&
                position.textOffset < segment.locator.endTextOffset
        }
    }

    private fun ensureOpen() {
        check(!closed) { "Speech content source is closed" }
    }

    private enum class Direction { FORWARD, BACKWARD }
}

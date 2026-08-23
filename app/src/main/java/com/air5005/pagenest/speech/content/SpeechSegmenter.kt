package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator

class SpeechSegmenter {
    fun fromParagraph(
        position: SpeechPosition,
        text: String,
        progression: Double,
    ): List<SpeechSegment> {
        val normalized = normalizeCrLf(text)
        val normalizedText = normalized.text
        val firstContentIndex = normalizedText.indexOfFirst { !it.isWhitespace() }
        if (firstContentIndex == -1) return emptyList()

        val lastContentIndex = normalizedText.indexOfLast { !it.isWhitespace() }
        val paragraphText = normalizedText.substring(firstContentIndex, lastContentIndex + 1)
        val codePoints = paragraphText.codePoints().toArray()
        val segments = mutableListOf<SpeechSegment>()
        var codePointStart = 0
        var normalizedSegmentStart = firstContentIndex

        while (codePointStart < codePoints.size) {
            val maximumEnd = minOf(codePointStart + MAX_CODE_POINTS, codePoints.size)
            val codePointEnd = if (maximumEnd == codePoints.size) {
                maximumEnd
            } else {
                preferredEnd(codePoints, codePointStart, maximumEnd)
            }
            val segmentText = String(codePoints, codePointStart, codePointEnd - codePointStart)
            val segmentPosition = position
            val partIndex = segments.size
            val normalizedSegmentEnd = normalizedSegmentStart + segmentText.length
            val startTextOffset = position.textOffset + normalized.sourceOffsets[normalizedSegmentStart]
            val endTextOffset = position.textOffset + normalized.sourceOffsets[normalizedSegmentEnd]
            val id = stableId(segmentPosition, partIndex, startTextOffset, endTextOffset)

            segments += SpeechSegment(
                id = id,
                position = segmentPosition,
                partIndex = partIndex,
                text = segmentText,
                locator = Locator(
                    id = id,
                    chapterIndex = position.chapterIndex,
                    startParagraphIndex = position.paragraphIndex,
                    startTextOffset = startTextOffset,
                    endParagraphIndex = position.paragraphIndex,
                    endTextOffset = endTextOffset,
                    text = segmentText,
                    progression = progression,
                ),
            )

            codePointStart = codePointEnd
            normalizedSegmentStart = normalizedSegmentEnd
        }

        return segments
    }

    private fun preferredEnd(codePoints: IntArray, start: Int, maximumEnd: Int): Int {
        for (index in maximumEnd - 1 downTo start) {
            if (codePoints[index] in PREFERRED_BOUNDARIES) return index + 1
        }
        return maximumEnd
    }

    private fun normalizeCrLf(text: String): NormalizedText {
        val normalizedText = StringBuilder(text.length)
        val sourceOffsets = mutableListOf(0)
        var sourceIndex = 0

        while (sourceIndex < text.length) {
            if (text[sourceIndex] == '\r' && text.getOrNull(sourceIndex + 1) == '\n') {
                normalizedText.append('\n')
                sourceIndex += 2
            } else {
                normalizedText.append(text[sourceIndex])
                sourceIndex += 1
            }
            sourceOffsets += sourceIndex
        }

        return NormalizedText(normalizedText.toString(), sourceOffsets.toIntArray())
    }

    private fun stableId(
        position: SpeechPosition,
        partIndex: Int,
        startTextOffset: Int,
        endTextOffset: Int,
    ): String = listOf(
        position.bookId,
        position.chapterIndex,
        position.pageIndex ?: "none",
        position.paragraphIndex,
        position.textOffset,
        partIndex,
        startTextOffset,
        endTextOffset,
    ).joinToString(":")

    private data class NormalizedText(
        val text: String,
        val sourceOffsets: IntArray,
    )

    private companion object {
        const val MAX_CODE_POINTS = 500
        val PREFERRED_BOUNDARIES = setOf('。'.code, '！'.code, '？'.code, '；'.code, '.'.code, '!'.code, '?'.code, ';'.code, '\n'.code)
    }
}

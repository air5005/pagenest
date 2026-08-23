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
        val normalizedText = text.replace("\r\n", "\n")
        val firstContentIndex = normalizedText.indexOfFirst { !it.isWhitespace() }
        if (firstContentIndex == -1) return emptyList()

        val lastContentIndex = normalizedText.indexOfLast { !it.isWhitespace() }
        val paragraphText = normalizedText.substring(firstContentIndex, lastContentIndex + 1)
        val codePoints = paragraphText.codePoints().toArray()
        val segments = mutableListOf<SpeechSegment>()
        var codePointStart = 0
        var textOffset = position.textOffset + firstContentIndex

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
            val id = stableId(segmentPosition, partIndex)
            val endTextOffset = textOffset + segmentText.length

            segments += SpeechSegment(
                id = id,
                position = segmentPosition,
                partIndex = partIndex,
                text = segmentText,
                locator = Locator(
                    id = id,
                    chapterIndex = position.chapterIndex,
                    startParagraphIndex = position.paragraphIndex,
                    startTextOffset = textOffset,
                    endParagraphIndex = position.paragraphIndex,
                    endTextOffset = endTextOffset,
                    text = segmentText,
                    progression = progression,
                ),
            )

            codePointStart = codePointEnd
            textOffset = endTextOffset
        }

        return segments
    }

    private fun preferredEnd(codePoints: IntArray, start: Int, maximumEnd: Int): Int {
        for (index in maximumEnd - 1 downTo start) {
            if (codePoints[index] in PREFERRED_BOUNDARIES) return index + 1
        }
        return maximumEnd
    }

    private fun stableId(position: SpeechPosition, partIndex: Int): String = listOf(
        position.bookId,
        position.chapterIndex,
        position.pageIndex ?: "none",
        position.paragraphIndex,
        position.textOffset,
        partIndex,
    ).joinToString(":")

    private companion object {
        const val MAX_CODE_POINTS = 500
        val PREFERRED_BOUNDARIES = setOf('。'.code, '！'.code, '？'.code, '；'.code, '.'.code, '!'.code, '?'.code, ';'.code, '\n'.code)
    }
}

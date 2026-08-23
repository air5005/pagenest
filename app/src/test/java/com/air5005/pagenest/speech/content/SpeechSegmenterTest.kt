package com.air5005.pagenest.speech.content

import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.currentSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechSegmenterTest {
    private val segmenter = SpeechSegmenter()

    @Test
    fun `500 code points stay in one segment`() {
        val text = "乘".repeat(500)

        val result = segmenter.fromParagraph(position(), text, progression = 0.25)

        assertEquals(listOf(text), result.map { it.text })
    }

    @Test
    fun `501 code points split at punctuation without loss`() {
        val first = "甲".repeat(480) + "。"
        val second = "乘".repeat(20)

        val result = segmenter.fromParagraph(position(), first + second, progression = 0.25)

        assertEquals(listOf(first, second), result.map { it.text })
        assertEquals(first + second, result.joinToString("") { it.text })
        assertEquals(listOf(0, 1), result.map { it.partIndex })
    }

    @Test
    fun `images rules and blank paragraphs produce no speech`() {
        assertTrue(segmenter.fromParagraph(position(), " \n\t", 0.0).isEmpty())
    }

    @Test
    fun `surrogate pairs count as one code point and are never split`() {
        val text = "😀".repeat(501)

        val result = segmenter.fromParagraph(position(), text, progression = 0.25)

        assertEquals(listOf("😀".repeat(500), "😀"), result.map { it.text })
        assertEquals(listOf(500, 1), result.map { it.text.codePointCount(0, it.text.length) })
    }

    @Test
    fun `Chinese and English punctuation are preferred at the limit`() {
        val chinese = "a".repeat(498) + "！" + "b".repeat(20)
        val english = "c".repeat(498) + "?" + "d".repeat(20)

        assertEquals(
            listOf("a".repeat(498) + "！", "b".repeat(20)),
            segmenter.fromParagraph(position(), chinese, progression = 0.25).map { it.text },
        )
        assertEquals(
            listOf("c".repeat(498) + "?", "d".repeat(20)),
            segmenter.fromParagraph(position(), english, progression = 0.25).map { it.text },
        )
    }

    @Test
    fun `single 501 code point token splits exactly at the limit`() {
        val text = "x".repeat(501)

        val result = segmenter.fromParagraph(position(), text, progression = 0.25)

        assertEquals(listOf("x".repeat(500), "x"), result.map { it.text })
    }

    @Test
    fun `normalization trims only outer whitespace and preserves interior text`() {
        val text = " \r\nalpha\r\n beta\t "

        val result = segmenter.fromParagraph(position(), text, progression = 0.25)

        assertEquals(listOf("alpha\n beta"), result.map { it.text })
    }

    @Test
    fun `locator ranges retain original UTF-16 offsets through leading and interior CRLF`() {
        val result = segmenter.fromParagraph(
            position(textOffset = 10),
            " \r\nalpha\r\n beta\t ",
            progression = 0.25,
        )

        assertEquals(listOf("alpha\n beta"), result.map { it.text })
        assertEquals(listOf(10), result.map { it.position.textOffset })
        assertEquals(listOf(13), result.map { it.locator.startTextOffset })
        assertEquals(listOf(25), result.map { it.locator.endTextOffset })
    }

    @Test
    fun `segments have stable ids and exact UTF-16 locator ranges`() {
        val position = position(textOffset = 10)
        val text = "😀".repeat(500) + "x"

        val first = segmenter.fromParagraph(position, text, progression = 0.25)
        val second = segmenter.fromParagraph(position, text, progression = 0.25)

        assertEquals(first.map { it.id }, second.map { it.id })
        assertEquals(2, first.map { it.id }.toSet().size)
        assertEquals(listOf(10, 10), first.map { it.position.textOffset })
        assertEquals(listOf(10, 1010), first.map { it.locator.startTextOffset })
        assertEquals(listOf(1010, 1011), first.map { it.locator.endTextOffset })
        assertEquals(listOf(3, 3), first.map { it.locator.startParagraphIndex })
        assertEquals(listOf(3, 3), first.map { it.locator.endParagraphIndex })
        assertEquals(listOf(0.25, 0.25), first.map { it.locator.progression })
    }

    @Test
    fun `playback states expose only their active segment`() {
        val segment = segmenter.fromParagraph(position(), "segment", progression = 0.25).single()

        assertNull(SpeechPlaybackState.Idle.currentSegment())
        assertEquals(segment, SpeechPlaybackState.Preparing(segment).currentSegment())
        assertEquals(segment, SpeechPlaybackState.Playing(segment, "system").currentSegment())
        assertEquals(segment, SpeechPlaybackState.Paused(segment).currentSegment())
        assertNull(SpeechPlaybackState.Completed.currentSegment())
        assertEquals(
            segment,
            SpeechPlaybackState.Error(SpeechError.NetworkTimeout, segment).currentSegment(),
        )
        assertNull(SpeechPlaybackState.Error(SpeechError.NetworkTimeout, null).currentSegment())
    }

    @Test
    fun `speech errors retain their stable categories`() {
        assertEquals(SpeechError.Kind.NETWORK, SpeechError.NetworkTimeout.kind)
        assertEquals(SpeechError.Kind.RATE_LIMIT, SpeechError.RateLimited.kind)
        assertEquals(SpeechError.Kind.SERVICE, SpeechError.ServiceUnavailable.kind)
        assertEquals(SpeechError.Kind.AUTH, SpeechError.InvalidCredentials.kind)
        assertEquals(SpeechError.Kind.CONFIGURATION, SpeechError.InvalidRegion.kind)
        assertEquals(SpeechError.Kind.QUOTA, SpeechError.QuotaExceeded.kind)
        assertEquals(SpeechError.Kind.CONTENT, SpeechError.NoExtractableText.kind)
        assertEquals(SpeechError.Kind.ENGINE, SpeechError.SystemTtsUnavailable.kind)
        assertEquals(SpeechError.Kind.ENGINE, SpeechError.MissingLanguageData.kind)
        assertEquals(SpeechError.Kind.ENGINE, SpeechError.UnsupportedLocale.kind)
        assertEquals(SpeechError.Kind.DECODE, SpeechError.AudioDecodeFailure.kind)
    }

    private fun position(textOffset: Int = 0) = SpeechPosition(
        bookId = 11,
        chapterIndex = 2,
        pageIndex = 5,
        paragraphIndex = 3,
        textOffset = textOffset,
    )
}

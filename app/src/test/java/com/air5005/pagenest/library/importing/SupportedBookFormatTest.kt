package com.air5005.pagenest.library.importing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupportedBookFormatTest {
    @Test
    fun recognizesMilestoneFormatsCaseInsensitively() {
        assertEquals(SupportedBookFormat.EPUB, SupportedBookFormat.fromFileName("Book.EPUB"))
        assertEquals(SupportedBookFormat.TXT, SupportedBookFormat.fromFileName("notes.txt"))
        assertEquals(SupportedBookFormat.PDF, SupportedBookFormat.fromFileName("paper.Pdf"))
        assertEquals(SupportedBookFormat.MOBI, SupportedBookFormat.fromFileName("novel.mobi"))
        assertEquals(SupportedBookFormat.AZW3, SupportedBookFormat.fromFileName("kindle.azw3"))
    }

    @Test
    fun rejectsMissingExtensions() {
        assertNull(SupportedBookFormat.fromFileName("README"))
        assertNull(SupportedBookFormat.fromFileName(""))
    }

    @Test
    fun rejectsTrailingDots() {
        assertNull(SupportedBookFormat.fromFileName("book."))
    }

    @Test
    fun rejectsHiddenFiles() {
        assertNull(SupportedBookFormat.fromFileName(".epub"))
        assertNull(SupportedBookFormat.fromFileName(".hidden.epub"))
    }

    @Test
    fun rejectsPathLikeInputs() {
        assertNull(SupportedBookFormat.fromFileName("books/novel.epub"))
        assertNull(SupportedBookFormat.fromFileName("books\\novel.epub"))
        assertNull(SupportedBookFormat.fromFileName("../novel.epub"))
    }

    @Test
    fun rejectsUnsupportedExtensions() {
        assertNull(SupportedBookFormat.fromFileName("archive.zip"))
        assertNull(SupportedBookFormat.fromFileName("book.epub.zip"))
    }
}

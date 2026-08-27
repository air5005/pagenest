package com.wxn.reader.presentation.mainReader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingReaderProgressTest {
    @Test
    fun `latest progress request wins while a book is indexing`() {
        val pending = PendingReaderProgress()

        pending.remember(bookId = 7, progress = 0.25)
        pending.remember(bookId = 7, progress = 0.72)

        assertEquals(0.72, pending.consume(bookId = 7)!!, 0.0)
        assertNull(pending.consume(bookId = 7))
    }

    @Test
    fun `progress is clamped to the book range`() {
        val pending = PendingReaderProgress()

        pending.remember(bookId = 7, progress = 2.0)

        assertEquals(1.0, pending.consume(bookId = 7)!!, 0.0)
    }

    @Test
    fun `switching books discards the previous book request`() {
        val pending = PendingReaderProgress()
        pending.remember(bookId = 7, progress = 0.72)

        pending.switchTo(bookId = 8)

        assertNull(pending.consume(bookId = 7))
        assertNull(pending.consume(bookId = 8))
    }
}

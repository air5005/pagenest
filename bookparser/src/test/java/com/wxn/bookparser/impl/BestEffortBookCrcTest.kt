package com.wxn.bookparser.impl

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BestEffortBookCrcTest {
    @Test
    fun `available native crc is preserved`() {
        assertEquals(73, bestEffortBookCrc("book.epub") { 73 })
    }

    @Test
    fun `missing native crc becomes zero`() {
        assertEquals(0, bestEffortBookCrc("book.txt") { null })
    }

    @Test
    fun `ordinary crc failure becomes zero`() {
        assertEquals(0, bestEffortBookCrc("book.epub") { throw IOException("unreadable") })
    }

    @Test
    fun `unavailable native library becomes zero`() {
        assertEquals(
            0,
            bestEffortBookCrc("book.epub") {
                throw UnsatisfiedLinkError("libappmobi.so not found")
            },
        )
    }

    @Test
    fun `unrelated fatal error is not swallowed`() {
        val fatal = AssertionError("fatal")

        val thrown = assertThrows(AssertionError::class.java) {
            bestEffortBookCrc("book.epub") { throw fatal }
        }

        assertEquals(fatal, thrown)
    }
}

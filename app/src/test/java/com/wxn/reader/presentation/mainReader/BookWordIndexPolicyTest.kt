package com.wxn.reader.presentation.mainReader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookWordIndexPolicyTest {
    @Test
    fun `cached books skip full word indexing`() {
        assertFalse(BookWordIndexPolicy.shouldStart(20_000, 7, null, false))
    }

    @Test
    fun `an active index for the same book is not duplicated`() {
        assertFalse(BookWordIndexPolicy.shouldStart(0, 7, 7, true))
    }

    @Test
    fun `missing cache starts when no matching active index exists`() {
        assertTrue(BookWordIndexPolicy.shouldStart(0, 7, null, false))
        assertTrue(BookWordIndexPolicy.shouldStart(0, 8, 7, true))
    }
}

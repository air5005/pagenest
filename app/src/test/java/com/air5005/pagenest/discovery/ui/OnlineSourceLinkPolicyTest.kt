package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.model.SourceReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineSourceLinkPolicyTest {
    @Test
    fun `open library work reference maps to trusted work page`() {
        assertEquals(
            "https://openlibrary.org/works/OL45804W",
            OnlineSourceLinkPolicy.sourcePage(SourceReference("openlibrary", "OL45804W")),
        )
        assertNull(OnlineSourceLinkPolicy.sourcePage(SourceReference("openlibrary", "../bad")))
    }
    @Test
    fun `gutendex and gutenberg numeric ids map to canonical Gutenberg pages`() {
        assertEquals(
            "https://www.gutenberg.org/ebooks/1342",
            OnlineSourceLinkPolicy.sourcePage(SourceReference("gutendex", "1342")),
        )
        assertEquals(
            "https://www.gutenberg.org/ebooks/84",
            OnlineSourceLinkPolicy.sourcePage(SourceReference("gutenberg-opds", "84")),
        )
    }

    @Test
    fun `standard ebooks slugs map to canonical pages`() {
        assertEquals(
            "https://standardebooks.org/ebooks/jane-austen/pride-and-prejudice",
            OnlineSourceLinkPolicy.sourcePage(
                SourceReference("standard-ebooks", "jane-austen/pride-and-prejudice"),
            ),
        )
    }

    @Test
    fun `open library ids map to canonical work pages`() {
        assertEquals(
            "https://openlibrary.org/works/OL66554W",
            OnlineSourceLinkPolicy.openLibraryWorkPage("OL66554W"),
        )
    }

    @Test
    fun `unsafe and unknown identifiers are rejected`() {
        listOf(
            SourceReference("gutendex", "12?download=1"),
            SourceReference("gutenberg-opds", "../12"),
            SourceReference("standard-ebooks", "user@example.com/book"),
            SourceReference("standard-ebooks", "author/../book"),
            SourceReference("standard-ebooks", "author/book?x=1"),
            SourceReference("unknown", "123"),
        ).forEach { assertNull(OnlineSourceLinkPolicy.sourcePage(it)) }

        assertNull(OnlineSourceLinkPolicy.openLibraryWorkPage("../OL66554W"))
        assertNull(OnlineSourceLinkPolicy.openLibraryWorkPage("OL66554W?x=1"))
        assertNull(OnlineSourceLinkPolicy.openLibraryWorkPage("OL123M"))
    }
}

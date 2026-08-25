package com.air5005.pagenest.discovery.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadUrlPolicyTest {
    private val policy = DownloadUrlPolicy()

    @Test
    fun `known sources accept only their exact HTTPS hosts on port 443`() {
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.epub",
            policy.validate(
                "gutendex",
                "https://www.gutenberg.org/cache/epub/1342/pg1342.epub",
            )?.toASCIIString(),
        )
        assertEquals(
            "https://gutenberg.org:443/files/84/84-0.txt",
            policy.validate(
                "gutenberg-opds",
                "https://gutenberg.org:443/files/84/84-0.txt",
            )?.toASCIIString(),
        )
        assertEquals(
            "https://standardebooks.org/ebooks/author/book/text/single-page",
            policy.validate(
                "standard-ebooks",
                "https://standardebooks.org/ebooks/author/book/text/single-page",
            )?.toASCIIString(),
        )
    }

    @Test
    fun `unknown source host protocol port credentials and fragment are rejected`() {
        listOf(
            "unknown" to "https://www.gutenberg.org/files/1.txt",
            "gutendex" to "http://www.gutenberg.org/files/1.txt",
            "gutendex" to "https://evil.example/files/1.txt",
            "gutendex" to "https://www.gutenberg.org.evil.example/files/1.txt",
            "gutendex" to "https://www.gutenberg.org:444/files/1.txt",
            "gutendex" to "https://user@www.gutenberg.org/files/1.txt",
            "gutendex" to "https://www.gutenberg.org/files/1.txt#fragment",
        ).forEach { (source, url) -> assertNull(policy.validate(source, url)) }
    }

    @Test
    fun `redirects resolve relative locations then reapply source policy`() {
        val current = requireNotNull(
            policy.validate("gutendex", "https://www.gutenberg.org/ebooks/1342.epub"),
        )
        assertEquals(
            "https://www.gutenberg.org/cache/epub/1342/pg1342.epub",
            policy.resolveRedirect(
                "gutendex",
                current,
                "../cache/epub/1342/pg1342.epub",
            )?.toASCIIString(),
        )
        assertNull(policy.resolveRedirect("gutendex", current, "https://127.0.0.1/book.epub"))
        assertNull(policy.resolveRedirect("gutendex", current, "//evil.example/book.epub"))
        assertNull(policy.resolveRedirect("gutendex", current, "http://www.gutenberg.org/book.epub"))
    }

    @Test
    fun `control characters and excessive URLs are rejected`() {
        assertNull(
            policy.validate("gutendex", "https://www.gutenberg.org/file\nInjected: value"),
        )
        assertNull(
            policy.validate(
                "gutendex",
                "https://www.gutenberg.org/" + "a".repeat(DownloadUrlPolicy.MAX_URL_LENGTH),
            ),
        )
    }
}

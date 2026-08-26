package com.wxn.base.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticSanitizerTest {
    @Test
    fun `secrets and authorization values are removed case insensitively`() {
        val sanitized = DiagnosticSanitizer.sanitize(
            "Authorization: Bearer abc123 Api-Key=xyz api_key=qwerty token: session-value secret=hidden",
        )

        listOf("abc123", "xyz", "qwerty", "session-value", "hidden").forEach {
            assertFalse("must remove $it", sanitized.contains(it))
        }
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun `url query fragment and absolute paths are removed`() {
        val sanitized = DiagnosticSanitizer.sanitize(
            "GET https://example.com/books/42?api_key=secret#page " +
                "C:\\Users\\Ada\\private\\book.epub /data/user/0/com.app/files/private.txt " +
                "/home/ada/private/book.pdf",
        )

        assertTrue(sanitized.contains("https://example.com/books/42?[REDACTED]"))
        assertFalse(sanitized.contains("api_key=secret"))
        assertFalse(sanitized.contains("C:\\Users"))
        assertFalse(sanitized.contains("/data/user/0"))
        assertFalse(sanitized.contains("/home/ada"))
        assertTrue(sanitized.contains("[PRIVATE_PATH]"))
    }

    @Test
    fun `message is bounded and control newlines are escaped`() {
        val sanitized = DiagnosticSanitizer.sanitize("a".repeat(2_100) + "\nsecret tail")

        assertTrue(sanitized.length <= 2_000)
        assertFalse(sanitized.contains('\n'))
        assertFalse(sanitized.contains('\r'))
    }

    @Test
    fun `throwable output contains at most twenty physical stack lines and is sanitized`() {
        val throwable = IllegalStateException("token=super-secret").apply {
            stackTrace = (1..40).map {
                StackTraceElement("com.example.Secret$it", "call", "/data/user/0/app/File.kt", it)
            }.toTypedArray()
        }

        val sanitized = DiagnosticSanitizer.sanitize(throwable)

        assertFalse(sanitized.contains("super-secret"))
        assertFalse(sanitized.contains("/data/user/0"))
        assertTrue(sanitized.split("\\n").size <= 20)
    }
}

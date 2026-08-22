package com.air5005.pagenest.library.importing

import java.io.File
import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultBookProtectionInspectorTest {
    @Test
    fun rejectsEncryptedMobiAndAzw3() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { true },
            pdfEncrypted = { false },
            epubProtected = { false },
        )

        assertEquals(
            ProtectionVerdict.PROTECTED,
            inspector.inspect(File("a.mobi"), SupportedBookFormat.MOBI),
        )
        assertEquals(
            ProtectionVerdict.PROTECTED,
            inspector.inspect(File("a.azw3"), SupportedBookFormat.AZW3),
        )
    }

    @Test
    fun rejectsEncryptedPdf() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { false },
            pdfEncrypted = { true },
            epubProtected = { false },
        )

        assertEquals(
            ProtectionVerdict.PROTECTED,
            inspector.inspect(File("a.pdf"), SupportedBookFormat.PDF),
        )
    }

    @Test
    fun allowsPlainTextWithoutRunningAProtectionProbe() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { error("MOBI probe must not run") },
            pdfEncrypted = { error("PDF probe must not run") },
            epubProtected = { error("EPUB probe must not run") },
        )

        assertEquals(
            ProtectionVerdict.CLEAR,
            inspector.inspect(File("a.txt"), SupportedBookFormat.TXT),
        )
    }

    @Test
    fun allowsUnprotectedEpub() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { false },
            pdfEncrypted = { false },
            epubProtected = { false },
        )

        assertEquals(
            ProtectionVerdict.CLEAR,
            inspector.inspect(File("a.epub"), SupportedBookFormat.EPUB),
        )
    }

    @Test
    fun returnsUnreadableWhenAProtectionProbeCannotDetermineTheVerdict() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { throw IllegalStateException("cannot inspect") },
            pdfEncrypted = { false },
            epubProtected = { false },
        )

        assertEquals(
            ProtectionVerdict.UNREADABLE,
            inspector.inspect(File("a.mobi"), SupportedBookFormat.MOBI),
        )
    }

    @Test
    fun returnsUnreadableWhenTheNativeProtectionProbeCannotLoad() {
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { throw UnsatisfiedLinkError("native probe unavailable") },
            pdfEncrypted = { false },
            epubProtected = { false },
        )

        assertEquals(
            ProtectionVerdict.UNREADABLE,
            inspector.inspect(File("a.azw3"), SupportedBookFormat.AZW3),
        )
    }

    @Test
    fun cancellationFromAProductionProtectionProbePropagates() {
        val cancellation = CancellationException("cancel protection inspection")
        val inspector = DefaultBookProtectionInspector(
            mobiEncrypted = { throw cancellation },
            pdfEncrypted = { false },
            epubProtected = { false },
        )

        val thrown = assertThrows(CancellationException::class.java) {
            inspector.inspect(File("a.mobi"), SupportedBookFormat.MOBI)
        }

        assertTrue(thrown === cancellation)
    }
}

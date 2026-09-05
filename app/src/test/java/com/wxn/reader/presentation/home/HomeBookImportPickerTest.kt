package com.wxn.reader.presentation.home

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HomeBookImportPickerTest {
    @Test
    fun importActionCreatesAMultipleDocumentPickerForSupportedBookTypes() {
        val application = ApplicationProvider.getApplicationContext<Application>()

        val intent = bookImportPickerContract().createIntent(application, BOOK_IMPORT_MIME_TYPES)

        assertArrayEquals(
            arrayOf(
                "application/epub+zip",
                "text/plain",
                "application/pdf",
                "application/x-mobipocket-ebook",
                "application/vnd.amazon.ebook",
                "application/octet-stream",
            ),
            BOOK_IMPORT_MIME_TYPES,
        )
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals("*/*", intent.type)
        assertTrue(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false))
        assertArrayEquals(
            BOOK_IMPORT_MIME_TYPES,
            intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES),
        )
    }
}

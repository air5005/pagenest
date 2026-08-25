package com.air5005.pagenest.skin

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.wxn.bookread.ui.ReaderBackgroundResolver
import com.wxn.base.skin.SkinCanonicalStore
import com.wxn.base.skin.SkinCanonicalState
import android.graphics.drawable.BitmapDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReaderBackgroundResolverTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `custom private image path resolves to drawable`() {
        val source = File(context.cacheDir, "reader-skin.jpg")
        val bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }

        assertNotNull(ReaderBackgroundResolver.resolve(context, source.absolutePath))
    }

    @Test
    fun `missing or corrupt custom image falls back to color`() {
        val corrupt = File(context.cacheDir, "corrupt-reader-skin.jpg").apply { writeText("broken") }

        assertNull(ReaderBackgroundResolver.resolve(context, corrupt.absolutePath))
        assertNull(ReaderBackgroundResolver.resolve(context, File(context.cacheDir, "missing.jpg").absolutePath))
    }

    @Test
    fun `canonical committed skin wins over a partially written custom reader preference`() {
        val canonical = imageFile("canonical-reader-skin.jpg", 90, 60)
        val partial = imageFile("partial-reader-skin.jpg", 40, 20)
        val store = SkinCanonicalStore(context)
        store.write(SkinCanonicalState(canonical.absolutePath, canonical.absolutePath))
        try {
            val drawable = ReaderBackgroundResolver.resolve(context, partial.absolutePath) as BitmapDrawable

            assertEquals(90, drawable.bitmap.width)
            assertEquals(60, drawable.bitmap.height)
        } finally {
            store.clear()
        }
    }

    private fun imageFile(name: String, width: Int, height: Int): File {
        val file = File(context.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
        return file
    }
}

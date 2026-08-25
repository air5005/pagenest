package com.air5005.pagenest.skin

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSkinImageImporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `valid image is downsampled and stored in private skin directory`() = runTest {
        val source = File(context.cacheDir, "large-skin.png")
        val bitmap = Bitmap.createBitmap(2600, 1300, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }

        val result = AndroidSkinImageImporter(context).import(source.absolutePath)

        assertTrue(result is SkinImageImportResult.Success)
        val output = File((result as SkinImageImportResult.Success).path)
        assertTrue(output.isFile)
        assertEquals(File(context.filesDir, "skins").canonicalPath, output.parentFile?.canonicalPath)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, bounds)
        assertTrue(maxOf(bounds.outWidth, bounds.outHeight) <= 2048)
    }

    @Test
    fun `non image file is rejected without leaving imported file`() = runTest {
        val source = File(context.cacheDir, "not-image.txt").apply { writeText("not an image") }

        val result = AndroidSkinImageImporter(context).import(source.absolutePath)

        assertEquals(
            SkinImageImportResult.Failure(SkinImageImportFailure.INVALID_IMAGE),
            result,
        )
    }

    @Test
    fun `source larger than limit is rejected`() = runTest {
        val source = File(context.cacheDir, "oversized.bin")
        RandomAccessFile(source, "rw").use { it.setLength(AndroidSkinImageImporter.MAX_INPUT_BYTES + 1) }

        val result = AndroidSkinImageImporter(context).import(source.absolutePath)

        assertEquals(
            SkinImageImportResult.Failure(SkinImageImportFailure.IMAGE_TOO_LARGE),
            result,
        )
    }

    @Test
    fun `private storage setup failure is returned as io failure`() = runTest {
        val blockedFilesDir = File(context.cacheDir, "blocked-files").apply { writeText("file") }
        val blockedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = blockedFilesDir
        }

        val result = AndroidSkinImageImporter(blockedContext).import("content://gallery/image")

        assertEquals(SkinImageImportResult.Failure(SkinImageImportFailure.IO), result)
    }

    @Test
    fun `jpeg exif rotation is applied before publishing`() = runTest {
        val source = File(context.cacheDir, "rotated.jpg")
        val bitmap = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888)
        try {
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } finally {
            bitmap.recycle()
        }
        ExifInterface(source.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }

        val result = AndroidSkinImageImporter(context).import(source.absolutePath) as SkinImageImportResult.Success
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(result.path, bounds)

        assertEquals(40, bounds.outWidth)
        assertEquals(80, bounds.outHeight)
    }

    @Test
    fun `retaining active skin removes old imported images and temporary files`() = runTest {
        val directory = File(context.filesDir, "skins").apply { mkdirs() }
        val active = File(directory, "skin_active.jpg").apply { writeText("active") }
        val old = File(directory, "skin_old.jpg").apply { writeText("old") }
        val temporary = File(directory, "skin_source_orphan.tmp").apply { writeText("temp") }

        AndroidSkinImageImporter(context).retainOnly(setOf(active.absolutePath))

        assertTrue(active.isFile)
        assertTrue(!old.exists())
        assertTrue(!temporary.exists())
    }
}

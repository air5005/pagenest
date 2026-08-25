package com.wxn.bookread.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.appcompat.content.res.AppCompatResources
import com.wxn.base.skin.SkinCanonicalStore
import com.wxn.bookread.R
import java.io.File

object ReaderBackgroundResolver {
    private val bitmapCache = object : LruCache<String, android.graphics.Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: android.graphics.Bitmap): Int = value.allocationByteCount
    }

    fun resolve(context: Context, backgroundImage: String): Drawable? {
        val effectiveImage = if (backgroundImage in PRESET_BACKGROUNDS) {
            backgroundImage
        } else {
            SkinCanonicalStore(context).read()?.readerBackground ?: backgroundImage
        }
        return when (effectiveImage) {
        "ic_read_bg1" -> AppCompatResources.getDrawable(context, R.drawable.ic_read_bg1)
        "ic_read_bg2" -> AppCompatResources.getDrawable(context, R.drawable.ic_read_bg2)
        "ic_read_bg3" -> AppCompatResources.getDrawable(context, R.drawable.ic_read_bg3)
        "ic_read_bg4" -> AppCompatResources.getDrawable(context, R.drawable.ic_read_bg4)
            else -> decodeCustomImage(context, effectiveImage)
        }
    }

    private fun decodeCustomImage(context: Context, path: String): Drawable? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile) return null
        if (!hasSupportedImageHeader(file)) return null
        val cacheKey = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
        bitmapCache.get(cacheKey)?.let { return BitmapDrawable(context.resources, it) }

        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val target = maxOf(context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
                .coerceAtLeast(1)
            var sample = 1
            while (maxOf(bounds.outWidth / sample, bounds.outHeight / sample) > target * 2) sample *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return null
            bitmapCache.put(cacheKey, bitmap)
            BitmapDrawable(context.resources, bitmap)
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun hasSupportedImageHeader(file: File): Boolean {
        val header = ByteArray(12)
        val count = runCatching { file.inputStream().use { it.read(header) } }.getOrDefault(-1)
        if (count < 6) return false
        val jpeg = header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
        val png = header.copyOfRange(0, 8).contentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
        )
        val gif = String(header, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")
        val webp = count >= 12 &&
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(header, 8, 4, Charsets.US_ASCII) == "WEBP"
        return jpeg || png || gif || webp
    }

    private const val CACHE_BYTES = 16 * 1024 * 1024
    private val PRESET_BACKGROUNDS = setOf("ic_read_bg1", "ic_read_bg2", "ic_read_bg3", "ic_read_bg4")
}

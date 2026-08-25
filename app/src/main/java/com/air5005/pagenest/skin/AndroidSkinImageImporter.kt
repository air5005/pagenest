package com.air5005.pagenest.skin

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject

class AndroidSkinImageImporter @Inject constructor(
    @ApplicationContext private val context: Context,
) : SkinImageImporter {

    override suspend fun import(source: String): SkinImageImportResult = withContext(Dispatchers.IO) {
        var staged: File? = null
        var encoded: File? = null
        try {
            val skinDirectory = File(context.filesDir, SKIN_DIRECTORY)
            if (!skinDirectory.isDirectory && !skinDirectory.mkdirs()) throw IOException("Skin directory")
            val stagedFile = File.createTempFile("skin_source_", ".tmp", skinDirectory).also { staged = it }
            val encodedFile = File.createTempFile("skin_encoded_", ".tmp", skinDirectory).also { encoded = it }
            val knownLength = sourceLength(source)
            if (knownLength > MAX_INPUT_BYTES) {
                return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.IMAGE_TOO_LARGE)
            }
            openSource(source)?.use { input ->
                stagedFile.outputStream().buffered().use { output ->
                    copyBounded(input, output)
                }
            } ?: return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.IO)

            if (!hasSupportedImageHeader(stagedFile)) {
                return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.INVALID_IMAGE)
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(stagedFile.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                maxOf(bounds.outWidth, bounds.outHeight) > MAX_SOURCE_DIMENSION
            ) {
                return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.INVALID_IMAGE)
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            }
            val decoded = BitmapFactory.decodeFile(stagedFile.absolutePath, decodeOptions)
                ?: return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.INVALID_IMAGE)
            val bitmap = applyExifOrientation(decoded, stagedFile)
            try {
                FileOutputStream(encodedFile).use { output ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                        return@withContext SkinImageImportResult.Failure(SkinImageImportFailure.IO)
                    }
                    output.flush()
                    output.fd.sync()
                }
            } finally {
                bitmap.recycle()
                if (bitmap !== decoded) decoded.recycle()
            }

            val digest = sha256(encodedFile)
            val destination = File(skinDirectory, "skin_$digest.jpg")
            if (!destination.isFile) {
                moveAtomically(encodedFile, destination)
            }
            SkinImageImportResult.Success(destination.absolutePath)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InputTooLargeException) {
            SkinImageImportResult.Failure(SkinImageImportFailure.IMAGE_TOO_LARGE)
        } catch (_: Exception) {
            SkinImageImportResult.Failure(SkinImageImportFailure.IO)
        } finally {
            staged?.delete()
            encoded?.delete()
        }
    }

    override suspend fun retainOnly(paths: Set<String>) = withContext(Dispatchers.IO) {
        val allowed = paths.mapNotNull { runCatching { File(it).canonicalPath }.getOrNull() }.toSet()
        val skinDirectory = File(context.filesDir, SKIN_DIRECTORY)
        skinDirectory.listFiles()?.forEach { file ->
            val importedImage = file.name.startsWith("skin_") && file.extension == "jpg"
            val temporaryImport = file.name.startsWith("skin_source_") ||
                file.name.startsWith("skin_encoded_") ||
                file.name.startsWith("active_skin_")
            if ((importedImage && file.canonicalPath !in allowed) || temporaryImport) file.delete()
        }
        Unit
    }

    private fun sourceLength(source: String): Long {
        val file = File(source)
        if (file.isFile) return file.length()
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(Uri.parse(source), "r")?.use { it.length }
        }.getOrNull() ?: -1L
    }

    private fun openSource(source: String): InputStream? {
        val file = File(source)
        return if (file.isFile) FileInputStream(file) else {
            context.contentResolver.openInputStream(Uri.parse(source))
        }
    }

    private fun copyBounded(input: InputStream, output: java.io.OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > MAX_INPUT_BYTES) throw InputTooLargeException()
            output.write(buffer, 0, count)
        }
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_OUTPUT_DIMENSION) sample *= 2
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, file: File): Bitmap {
        val orientation = runCatching {
            ExifInterface(file.absolutePath).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
                matrix.setRotate(180f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun hasSupportedImageHeader(file: File): Boolean {
        val header = ByteArray(12)
        val count = file.inputStream().use { it.read(header) }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveAtomically(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private class InputTooLargeException : IOException()

    companion object {
        const val MAX_INPUT_BYTES = 25L * 1024L * 1024L
        const val MAX_OUTPUT_DIMENSION = 2048
        const val MAX_SOURCE_DIMENSION = 32_768
        private const val JPEG_QUALITY = 90
        private const val SKIN_DIRECTORY = "skins"
    }
}

package com.wxn.base.skin

import android.content.Context
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class SkinCanonicalState(
    val homeBackground: String,
    val readerBackground: String,
)

/**
 * A single-file commit point for one-tap skins. `null` means the feature has never been used,
 * while an empty string means the user explicitly restored the default skin.
 */
class SkinCanonicalStore(context: Context) {
    private val directory = File(context.filesDir, "skins")
    private val activeFile = File(directory, ACTIVE_FILE)

    fun read(): SkinCanonicalState? = runCatching {
        if (!activeFile.isFile) return@runCatching null
        val lines = activeFile.readLines(Charsets.UTF_8)
        if (lines.size != 2) return@runCatching null
        SkinCanonicalState(
            homeBackground = decode(lines[0]),
            readerBackground = decode(lines[1]),
        )
    }.getOrNull()

    fun write(state: SkinCanonicalState) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw java.io.IOException("Unable to create skin directory")
        }
        val temporary = File.createTempFile("active_skin_", ".tmp", directory)
        try {
            FileOutputStream(temporary).use { output ->
                val content = "${encode(state.homeBackground)}\n${encode(state.readerBackground)}"
                output.write(content.toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            try {
                Files.move(
                    temporary.toPath(),
                    activeFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), activeFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }

    fun clear() {
        Files.deleteIfExists(activeFile.toPath())
    }

    private fun encode(value: String): String =
        if (value.isEmpty()) EMPTY_VALUE else
            Base64.encodeToString(
                value.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
            )

    private fun decode(value: String): String =
        if (value == EMPTY_VALUE) "" else String(Base64.decode(value, Base64.URL_SAFE), Charsets.UTF_8)

    companion object {
        private const val ACTIVE_FILE = "active_skin"
        private const val EMPTY_VALUE = "_"
    }
}

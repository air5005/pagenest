package com.wxn.base.diagnostics

import java.nio.charset.StandardCharsets
import java.util.Base64

object DiagnosticLogCodec {
    fun encode(entry: DiagnosticLogEntry): String = listOf(
        entry.timestampEpochMillis.toString(),
        entry.level.name,
        encodeText(entry.category),
        encodeText(entry.message),
    ).joinToString("\t")

    fun decode(line: String): DiagnosticLogEntry? = runCatching {
        val fields = line.split('\t')
        require(fields.size == 4)
        DiagnosticLogEntry(
            timestampEpochMillis = fields[0].toLong(),
            level = DiagnosticLevel.valueOf(fields[1]),
            category = decodeText(fields[2]),
            message = decodeText(fields[3]),
        )
    }.getOrNull()

    private fun encodeText(value: String): String = Base64.getEncoder().encodeToString(
        value.toByteArray(StandardCharsets.UTF_8),
    )

    private fun decodeText(value: String): String = String(
        Base64.getDecoder().decode(value),
        StandardCharsets.UTF_8,
    )
}

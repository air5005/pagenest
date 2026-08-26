package com.wxn.base.diagnostics

enum class DiagnosticLevel {
    RUNNING,
    WARNING,
    ERROR,
}

data class DiagnosticLogEntry(
    val timestampEpochMillis: Long,
    val level: DiagnosticLevel,
    val category: String,
    val message: String,
) {
    companion object {
        val NEWEST_FIRST = compareByDescending<DiagnosticLogEntry> { it.timestampEpochMillis }
    }
}

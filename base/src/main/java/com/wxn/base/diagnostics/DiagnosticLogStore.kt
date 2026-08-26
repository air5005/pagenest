package com.wxn.base.diagnostics

interface DiagnosticLogStore {
    fun append(entry: DiagnosticLogEntry)
    fun readRecent(limit: Int): List<DiagnosticLogEntry>
    fun clear()
    fun totalBytes(): Long
    fun flush()
}

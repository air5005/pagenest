package com.air5005.pagenest.diagnostics

import com.wxn.base.diagnostics.DiagnosticLogEntry
import com.wxn.base.util.Logger
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class DiagnosticsSnapshot(
    val entries: List<DiagnosticLogEntry>,
    val totalBytes: Long,
)

interface DiagnosticsRepository {
    suspend fun readRecent(): DiagnosticsSnapshot
    suspend fun clear()
}

class LoggerDiagnosticsRepository @Inject constructor() : DiagnosticsRepository {
    override suspend fun readRecent(): DiagnosticsSnapshot = withContext(Dispatchers.IO) {
        DiagnosticsSnapshot(
            entries = Logger.readDiagnostics(),
            totalBytes = Logger.diagnosticsBytes(),
        )
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        Logger.clearDiagnostics()
    }
}

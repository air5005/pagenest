package com.wxn.base.util

import android.os.Build
import android.os.StrictMode
import android.util.Log
import com.wxn.base.diagnostics.DiagnosticCrashHandler
import com.wxn.base.diagnostics.DiagnosticLevel
import com.wxn.base.diagnostics.DiagnosticLogEntry
import com.wxn.base.diagnostics.DiagnosticLogWriter
import com.wxn.base.diagnostics.RotatingDiagnosticLogStore
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.cancellation.CancellationException

fun Throwable.toast(prefix: String = "") {
    if (this is CancellationException) return
//    val errorMessage = getErrorMessage()
//    if (errorMessage.isEmpty() && prefix.isEmpty()) return
    toast("$prefix${this.message}")
}

object Logger {
    private var diagnosticsWriter: DiagnosticLogWriter? = null
    private var diagnosticsTree: Timber.Tree? = null

    fun init(isDebug: Boolean, diagnosticsDirectory: File) {
        if (isDebug) {
//            enableStrictMode()
            Timber.plant(Timber.DebugTree())
        }
        diagnosticsTree?.let(Timber::uproot)
        diagnosticsWriter = DiagnosticLogWriter(RotatingDiagnosticLogStore(diagnosticsDirectory))
        diagnosticsTree = DiagnosticsTree().also(Timber::plant)
    }

    fun running(category: String, message: String) {
        Timber.i("[$category] $message")
        diagnosticsWriter?.log(DiagnosticLevel.RUNNING, category, message)
    }

    fun readDiagnostics(limit: Int = RotatingDiagnosticLogStore.DEFAULT_MAX_ENTRIES): List<DiagnosticLogEntry> =
        diagnosticsWriter?.readRecent(limit).orEmpty()

    fun clearDiagnostics() {
        diagnosticsWriter?.clear()
    }

    fun diagnosticsBytes(): Long = diagnosticsWriter?.totalBytes() ?: 0L

    fun flushDiagnostics() {
        diagnosticsWriter?.flush()
    }

    fun crashHandler(delegate: Thread.UncaughtExceptionHandler?): Thread.UncaughtExceptionHandler =
        DiagnosticCrashHandler(
            recordCrash = { throwable ->
                diagnosticsWriter?.logSynchronously(
                    DiagnosticLevel.ERROR,
                    "CRASH",
                    "Uncaught exception",
                    throwable,
                )
            },
            delegate = delegate,
        )

    /**
     * Strict mode will log violation of VM and threading policy.
     * Use it to make sure the app doesn't do too much work on the main thread.
     */
    private fun enableStrictMode() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return
        }

        val executor = Executors.newSingleThreadExecutor()
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .permitDiskWrites()
                .permitDiskReads()
                .penaltyListener(executor) { violation ->
                    Timber.e(violation, "Thread policy violation")
                }
//                .penaltyDeath()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyListener(executor) { violation ->
                    Timber.e(violation, "VM policy violation")
                }
//                .penaltyDeath()
                .build()
        )
    }


    fun i(t: Throwable) {
        Timber.i(t)
    }

    fun i(message: String) {
        Timber.i(message)
    }

    fun d(t: Throwable) {
        Timber.d(t)
    }

    fun d(message: String) {
        Timber.d(message)
    }

    fun w(t: Throwable) {
        Timber.w(t)
    }

    fun w(message: String) {
        Timber.w(message)
    }

    fun e(t: Throwable) {
        Timber.e(t)
    }

    fun e(message: String) {
        Timber.e(message)
    }

    fun v(t: Throwable) {
        Timber.v(t)
    }

    fun v(message: String) {
        Timber.v(message)
    }

    fun wtf(t: Throwable) {
        Timber.wtf(t)
    }

    fun wtf(message: String) {
        Timber.wtf(message)
    }

    fun log(priority: Int, t: Throwable) {
        Timber.log(priority, t)
    }

    fun log(priority: Int, message: String) {
        Timber.log(priority, message)
    }

    private class DiagnosticsTree : Timber.DebugTree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val level = when {
                priority >= Log.ERROR -> DiagnosticLevel.ERROR
                priority >= Log.WARN -> DiagnosticLevel.WARNING
                else -> return
            }
            diagnosticsWriter?.log(level, tag ?: "APP", message, t)
        }
    }
}

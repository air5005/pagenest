package com.air5005.pagenest.discovery.download

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout

class SecureBookDownloader(
    private val transport: BookDownloadTransport,
    private val urlPolicy: DownloadUrlPolicy,
    private val validator: DownloadedBookValidator,
    private val stagingFileStore: StagingFileStore,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun download(
        request: DownloadRequest,
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult {
        val initial = urlPolicy.validate(request.sourceId, request.url)
            ?: return DownloadResult.Failure(DownloadFailure.UNSAFE_URL)
        return try {
            withTimeout(timeoutMillis) { downloadValidated(request, initial, onProgress) }
        } catch (_: TimeoutCancellationException) {
            DownloadResult.Failure(DownloadFailure.RETRYABLE)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            DownloadResult.Failure(DownloadFailure.IO)
        }
    }

    private suspend fun downloadValidated(
        request: DownloadRequest,
        initial: URI,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        var current = initial
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val redirectTarget = transport.execute(current).use { response ->
                if (response.statusCode in REDIRECT_CODES) {
                    if (redirects >= MAX_REDIRECTS) {
                        return DownloadResult.Failure(DownloadFailure.REDIRECT_LIMIT)
                    }
                    val location = response.header("Location")
                        ?: return DownloadResult.Failure(DownloadFailure.HTTP_ERROR)
                    urlPolicy.resolveRedirect(request.sourceId, current, location)
                        ?: return DownloadResult.Failure(DownloadFailure.UNSAFE_URL)
                } else {
                    mapHttpFailure(response.statusCode)?.let { return DownloadResult.Failure(it) }
                    if (response.contentLength != null && response.contentLength > maxBytes) {
                        return DownloadResult.Failure(DownloadFailure.RESPONSE_TOO_LARGE)
                    }
                    return stageAndValidate(request, response, onProgress)
                }
            }
            current = redirectTarget
            redirects++
        }
    }

    private suspend fun stageAndValidate(
        request: DownloadRequest,
        response: BookDownloadResponse,
        onProgress: (DownloadProgress) -> Unit,
    ): DownloadResult {
        val part = stagingFileStore.createPart()
        var keepFile = false
        try {
            val total = response.contentLength
            var bytesRead = 0L
            onProgress(DownloadProgress(bytesRead, total))
            FileOutputStream(part).use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = runInterruptible(Dispatchers.IO) {
                        response.body.read(buffer)
                    }
                    if (read < 0) break
                    if (read == 0) continue
                    bytesRead += read
                    if (bytesRead > maxBytes) {
                        return DownloadResult.Failure(DownloadFailure.RESPONSE_TOO_LARGE)
                    }
                    output.write(buffer, 0, read)
                    onProgress(DownloadProgress(bytesRead, total))
                }
                output.flush()
                output.fd.sync()
            }
            if (!validator.validate(part, request.format, response.contentType)) {
                return DownloadResult.Failure(DownloadFailure.FORMAT_MISMATCH)
            }
            keepFile = true
            return DownloadResult.Success(DownloadedBook(part, request.format))
        } finally {
            if (!keepFile) deleteQuietly(part)
        }
    }

    private fun mapHttpFailure(statusCode: Int): DownloadFailure? = when {
        statusCode in 200..299 -> null
        statusCode == 401 || statusCode == 403 -> DownloadFailure.HTTP_UNAUTHORIZED
        statusCode == 404 -> DownloadFailure.NOT_FOUND
        statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode in 500..599 ->
            DownloadFailure.RETRYABLE
        else -> DownloadFailure.HTTP_ERROR
    }

    private fun deleteQuietly(file: File) {
        try {
            file.delete()
        } catch (_: Exception) {
            // The staging startup cleanup provides a second bounded cleanup opportunity.
        }
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 100L * 1024L * 1024L
        private const val DEFAULT_TIMEOUT_MILLIS = 120_000L
        private const val MAX_REDIRECTS = 3
        private const val BUFFER_SIZE = 16 * 1024
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    }
}

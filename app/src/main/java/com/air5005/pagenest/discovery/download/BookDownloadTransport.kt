package com.air5005.pagenest.discovery.download

import java.io.Closeable
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

interface BookDownloadTransport {
    suspend fun execute(uri: URI): BookDownloadResponse
}

class BookDownloadResponse(
    val statusCode: Int,
    private val headers: Map<String, String>,
    val contentLength: Long?,
    val contentType: String?,
    val body: InputStream,
    private val closeAction: () -> Unit = {},
) : Closeable {
    var closed: Boolean = false
        private set

    fun header(name: String): String? = headers.entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value

    override fun close() {
        if (closed) return
        closed = true
        try {
            body.close()
        } finally {
            closeAction()
        }
    }
}

object BookDownloadClientFactory {
    fun create(): OkHttpClient = OkHttpClient.Builder()
        .dns(PublicAddressDns())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    private const val CONNECT_TIMEOUT_SECONDS = 10L
    private const val READ_TIMEOUT_SECONDS = 30L
    private const val CALL_TIMEOUT_SECONDS = 120L
}

class OkHttpBookDownloadTransport(
    private val client: OkHttpClient = BookDownloadClientFactory.create(),
) : BookDownloadTransport {
    override suspend fun execute(uri: URI): BookDownloadResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(uri.toASCIIString())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, exception: java.io.IOException) {
                if (continuation.isActive) continuation.resumeWithException(exception)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body
                if (body == null) {
                    response.close()
                    if (continuation.isActive) {
                        continuation.resumeWithException(java.io.IOException("Response body unavailable"))
                    }
                    return
                }
                val result = BookDownloadResponse(
                    statusCode = response.code,
                    headers = response.headers.toMultimap().mapValues { it.value.firstOrNull().orEmpty() },
                    contentLength = body.contentLength().takeIf { it >= 0L },
                    contentType = body.contentType()?.toString(),
                    body = body.byteStream(),
                    closeAction = response::close,
                )
                if (continuation.isActive) {
                    continuation.resume(result) { _, value, _ -> value.close() }
                } else {
                    result.close()
                }
            }
        })
    }

    companion object {
        private const val USER_AGENT = "YiNest/GitHub"
    }
}

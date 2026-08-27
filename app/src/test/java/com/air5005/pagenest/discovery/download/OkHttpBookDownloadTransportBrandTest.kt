package com.air5005.pagenest.discovery.download

import java.net.URI
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Test

class OkHttpBookDownloadTransportBrandTest {
    @Test
    fun `download requests identify the YiNest product`() = runTest {
        var userAgent: String? = null
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                userAgent = chain.request().header("User-Agent")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(byteArrayOf(1).toResponseBody())
                    .build()
            })
            .build()

        OkHttpBookDownloadTransport(client).execute(URI("https://example.com/book.epub")).use {
            assertEquals("YiNest/GitHub", userAgent)
        }
    }
}

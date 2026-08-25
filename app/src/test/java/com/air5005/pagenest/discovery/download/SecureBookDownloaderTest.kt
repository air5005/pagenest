package com.air5005.pagenest.discovery.download

import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SecureBookDownloaderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `downloads a valid file and emits monotonic byte progress`() = runBlocking {
        val payload = "%PDF-1.7\nbody".toByteArray()
        val transport = FakeTransport(response(200, payload, "application/pdf"))
        val progress = mutableListOf<DownloadProgress>()

        val result = downloader(transport).download(pdfRequest(), progress::add)

        assertTrue(result is DownloadResult.Success)
        val downloaded = (result as DownloadResult.Success).book
        assertEquals(payload.toList(), downloaded.file.readBytes().toList())
        assertEquals(OnlineBookFormat.PDF, downloaded.format)
        assertTrue(progress.zipWithNext().all { (before, after) -> before.bytesRead <= after.bytesRead })
        assertEquals(payload.size.toLong(), progress.last().bytesRead)
    }

    @Test
    fun `follows at most three validated redirects`() = runBlocking {
        val successTransport = FakeTransport(
            redirect("/one"), redirect("/two"), redirect("/three"),
            response(200, "%PDF-1.7".toByteArray(), "application/pdf"),
        )
        assertTrue(downloader(successTransport).download(pdfRequest()) is DownloadResult.Success)
        assertEquals(4, successTransport.requested.size)

        val excessive = FakeTransport(
            redirect("/one"), redirect("/two"), redirect("/three"), redirect("/four"),
            response(200, "%PDF-1.7".toByteArray(), "application/pdf"),
        )
        assertEquals(DownloadFailure.REDIRECT_LIMIT, (downloader(excessive).download(pdfRequest()) as DownloadResult.Failure).reason)
        assertEquals(4, excessive.requested.size)
    }

    @Test
    fun `rejects an unsafe redirect before requesting it`() = runBlocking {
        val transport = FakeTransport(redirect("https://evil.example/book.pdf"))

        val result = downloader(transport).download(pdfRequest())

        assertEquals(DownloadFailure.UNSAFE_URL, (result as DownloadResult.Failure).reason)
        assertEquals(1, transport.requested.size)
    }

    @Test
    fun `maps HTTP statuses to safe typed failures`() = runBlocking {
        val expectations = mapOf(
            401 to DownloadFailure.HTTP_UNAUTHORIZED,
            403 to DownloadFailure.HTTP_UNAUTHORIZED,
            404 to DownloadFailure.NOT_FOUND,
            429 to DownloadFailure.RETRYABLE,
            503 to DownloadFailure.RETRYABLE,
            418 to DownloadFailure.HTTP_ERROR,
        )

        expectations.forEach { (status, expected) ->
            val result = downloader(FakeTransport(response(status, byteArrayOf(), null)))
                .download(pdfRequest())
            assertEquals(expected, (result as DownloadResult.Failure).reason)
        }
    }

    @Test
    fun `rejects declared and streamed bodies over the byte limit and cleans staging`() = runBlocking {
        val declared = FakeTransport(response(200, byteArrayOf(1), "application/pdf", contentLength = 9))
        assertEquals(DownloadFailure.RESPONSE_TOO_LARGE, (downloader(declared, maxBytes = 8).download(pdfRequest()) as DownloadResult.Failure).reason)
        assertFalse(declared.responses.single().bodyWasRead)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())

        val streamed = FakeTransport(response(200, ByteArray(9) { 'a'.code.toByte() }, "text/plain", contentLength = null))
        val result = downloader(streamed, maxBytes = 8).download(txtRequest())
        assertEquals(DownloadFailure.RESPONSE_TOO_LARGE, (result as DownloadResult.Failure).reason)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `rejects format spoof and removes partial file`() = runBlocking {
        val transport = FakeTransport(response(200, "not a pdf".toByteArray(), "application/pdf"))

        val result = downloader(transport).download(pdfRequest())

        assertEquals(DownloadFailure.FORMAT_MISMATCH, (result as DownloadResult.Failure).reason)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `cancellation is rethrown and partial file is removed`() = runBlocking {
        val cancellingBody = object : InputStream() {
            override fun read(): Int = throw CancellationException("cancelled")
        }
        val transport = FakeTransport(BookDownloadResponse(200, emptyMap(), null, "text/plain", cancellingBody))

        try {
            downloader(transport).download(txtRequest())
            throw AssertionError("Expected cancellation")
        } catch (_: CancellationException) {
            // Expected: cancellation is never mapped to an ordinary failure.
        }
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
        assertTrue(transport.responses.single().closed)
    }

    @Test
    fun `cancelling a blocked body read interrupts promptly and cleans staging`() = runBlocking {
        val readStarted = CountDownLatch(1)
        val blockingBody = object : InputStream() {
            override fun read(): Int {
                readStarted.countDown()
                Thread.sleep(5_000)
                return -1
            }
        }
        val transport = FakeTransport(BookDownloadResponse(200, emptyMap(), null, "text/plain", blockingBody))
        val job = launch(Dispatchers.Default) { downloader(transport).download(txtRequest()) }
        assertTrue(readStarted.await(2, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        job.cancelAndJoin()
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

        assertTrue("Cancellation took $elapsedMillis ms", elapsedMillis < 1_000)
        assertTrue(stagingDirectory().listFiles().orEmpty().isEmpty())
        assertTrue(transport.responses.single().closed)
    }

    private fun downloader(transport: BookDownloadTransport, maxBytes: Long = 1024): SecureBookDownloader =
        SecureBookDownloader(
            transport = transport,
            urlPolicy = DownloadUrlPolicy(),
            validator = DownloadedBookValidator(),
            stagingFileStore = StagingFileStore(stagingDirectory()),
            maxBytes = maxBytes,
        )

    private fun stagingDirectory(): File = File(temporaryFolder.root, "staging")

    private fun pdfRequest() = DownloadRequest(
        sourceId = DiscoverySourceRegistry.GUTENBERG_ID,
        url = "https://www.gutenberg.org/files/book.pdf",
        format = OnlineBookFormat.PDF,
    )

    private fun txtRequest() = pdfRequest().copy(
        url = "https://www.gutenberg.org/files/book.txt",
        format = OnlineBookFormat.TXT,
    )

    private fun redirect(location: String) = BookDownloadResponse(
        statusCode = 302,
        headers = mapOf("Location" to location),
        contentLength = 0,
        contentType = null,
        body = ByteArrayInputStream(byteArrayOf()),
    )

    private fun response(
        status: Int,
        body: ByteArray,
        contentType: String?,
        contentLength: Long? = body.size.toLong(),
    ) = BookDownloadResponse(
        statusCode = status,
        headers = emptyMap(),
        contentLength = contentLength,
        contentType = contentType,
        body = TrackingInputStream(body),
    )

    private class TrackingInputStream(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var wasRead = false
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            wasRead = true
            return super.read(buffer, offset, length)
        }
    }

    private class FakeTransport(vararg items: BookDownloadResponse) : BookDownloadTransport {
        val responses = items.toList()
        val requested = mutableListOf<URI>()
        private var index = 0

        override suspend fun execute(uri: URI): BookDownloadResponse {
            requested += uri
            return responses[index++]
        }
    }

    private val BookDownloadResponse.bodyWasRead: Boolean
        get() = (body as? TrackingInputStream)?.wasRead == true
}

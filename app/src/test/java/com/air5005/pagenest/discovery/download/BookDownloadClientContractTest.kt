package com.air5005.pagenest.discovery.download

import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BookDownloadClientContractTest {
    @Test
    fun `production client disables redirects and retries and binds timeouts`() {
        val client = BookDownloadClientFactory.create()

        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertFalse(client.retryOnConnectionFailure)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
        assertEquals(120_000, client.callTimeoutMillis)
        assertEquals(PublicAddressDns::class, client.dns::class)
        assertEquals(TimeUnit.MILLISECONDS.toMillis(120_000), client.callTimeoutMillis.toLong())
    }
}

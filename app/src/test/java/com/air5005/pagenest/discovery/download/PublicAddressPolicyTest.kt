package com.air5005.pagenest.discovery.download

import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicAddressPolicyTest {
    @Test
    fun `public IPv4 and IPv6 addresses are accepted`() {
        assertTrue(PublicAddressPolicy.isPublic(address("8.8.8.8")))
        assertTrue(PublicAddressPolicy.isPublic(address("93.184.216.34")))
        assertTrue(PublicAddressPolicy.isPublic(address("2606:4700:4700::1111")))
    }

    @Test
    fun `local private shared documentation benchmark multicast and reserved ranges are rejected`() {
        listOf(
            "0.0.0.0", "10.1.2.3", "100.64.0.1", "127.0.0.1", "169.254.1.1",
            "172.16.0.1", "192.0.0.1", "192.0.2.1", "192.168.1.1", "198.18.0.1",
            "198.51.100.1", "203.0.113.1", "224.0.0.1", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "ff02::1", "2001:db8::1",
        ).forEach { value -> assertFalse(value, PublicAddressPolicy.isPublic(address(value))) }
    }

    @Test
    fun `a mixed DNS answer fails closed`() {
        assertFalse(
            PublicAddressPolicy.areAllPublic(
                listOf(address("8.8.8.8"), address("127.0.0.1")),
            ),
        )
        assertFalse(PublicAddressPolicy.areAllPublic(emptyList()))
    }

    @Test
    fun `production DNS adapter returns only a fully public answer`() {
        val accepted = PublicAddressDns(object : Dns {
            override fun lookup(hostname: String) =
                listOf(address("8.8.8.8"), address("2606:4700:4700::1111"))
        })
        assertEquals(2, accepted.lookup("www.gutenberg.org").size)

        val rejected = PublicAddressDns(object : Dns {
            override fun lookup(hostname: String) =
                listOf(address("8.8.8.8"), address("192.168.1.1"))
        })
        assertThrows(UnknownHostException::class.java) {
            rejected.lookup("www.gutenberg.org")
        }
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}

package com.air5005.pagenest.discovery.download

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import okhttp3.Dns

object PublicAddressPolicy {
    fun areAllPublic(addresses: List<InetAddress>): Boolean =
        addresses.isNotEmpty() && addresses.all(::isPublic)

    fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return false
        }
        return when (address) {
            is Inet4Address -> isPublicIpv4(address.address)
            is Inet6Address -> isPublicIpv6(address.address)
            else -> false
        }
    }

    private fun isPublicIpv4(bytes: ByteArray): Boolean {
        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        return when {
            first == 0 -> false
            first == 10 -> false
            first == 100 && second in 64..127 -> false
            first == 127 -> false
            first == 169 && second == 254 -> false
            first == 172 && second in 16..31 -> false
            first == 192 && second == 0 -> false
            first == 192 && second == 168 -> false
            first == 198 && second in 18..19 -> false
            first == 198 && second == 51 && bytes[2].unsigned() == 100 -> false
            first == 203 && second == 0 && bytes[2].unsigned() == 113 -> false
            first >= 224 -> false
            else -> true
        }
    }

    private fun isPublicIpv6(bytes: ByteArray): Boolean {
        val first = bytes[0].unsigned()
        val second = bytes[1].unsigned()
        val third = bytes[2].unsigned()
        return when {
            first and 0xfe == 0xfc -> false // unique local fc00::/7
            first == 0x20 && second == 0x01 && third <= 0x01 -> false // special-use 2001::/23
            first == 0x20 && second == 0x01 && third == 0x0d && bytes[3].unsigned() == 0xb8 -> false
            first == 0x3f && second and 0xf0 == 0xf0 -> false // documentation 3fff::/20
            first == 0x00 && second == 0x64 && third == 0xff && bytes[3].unsigned() == 0x9b &&
                bytes[4].unsigned() == 0x00 && bytes[5].unsigned() == 0x01 -> false
            else -> true
        }
    }

    private fun Byte.unsigned(): Int = toInt() and 0xff
}

class PublicAddressDns(
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        if (!PublicAddressPolicy.areAllPublic(addresses)) {
            throw UnknownHostException("DNS answer is not public")
        }
        return addresses
    }
}

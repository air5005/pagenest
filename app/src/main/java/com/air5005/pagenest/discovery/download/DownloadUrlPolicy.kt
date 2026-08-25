package com.air5005.pagenest.discovery.download

import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import java.net.URI
import java.util.Locale

class DownloadUrlPolicy {
    fun validate(sourceId: String, rawUrl: String): URI? {
        if (rawUrl.isBlank() || rawUrl.length > MAX_URL_LENGTH || rawUrl.any(Char::isISOControl)) {
            return null
        }
        val uri = try {
            URI(rawUrl)
        } catch (_: Exception) {
            return null
        }
        if (!uri.isAbsolute || uri.scheme?.lowercase(Locale.ROOT) != HTTPS_SCHEME) return null
        if (uri.rawUserInfo != null || uri.rawFragment != null) return null
        if (uri.port != -1 && uri.port != HTTPS_PORT) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (host !in allowedHosts(sourceId)) return null
        return uri
    }

    fun resolveRedirect(sourceId: String, current: URI, location: String): URI? {
        if (location.isBlank() || location.length > MAX_URL_LENGTH || location.any(Char::isISOControl)) {
            return null
        }
        val resolved = try {
            current.resolve(location)
        } catch (_: Exception) {
            return null
        }
        return validate(sourceId, resolved.toASCIIString())
    }

    private fun allowedHosts(sourceId: String): Set<String> = when (sourceId) {
        DiscoverySourceRegistry.GUTENDEX_ID,
        DiscoverySourceRegistry.GUTENBERG_ID,
        -> GUTENBERG_HOSTS

        DiscoverySourceRegistry.STANDARD_EBOOKS_ID -> STANDARD_EBOOKS_HOSTS
        else -> emptySet()
    }

    companion object {
        const val MAX_URL_LENGTH = 2_048
        private const val HTTPS_SCHEME = "https"
        private const val HTTPS_PORT = 443
        private val GUTENBERG_HOSTS = setOf("gutenberg.org", "www.gutenberg.org")
        private val STANDARD_EBOOKS_HOSTS = setOf("standardebooks.org", "www.standardebooks.org")
    }
}

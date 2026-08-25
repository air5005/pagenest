package com.air5005.pagenest.discovery.cache

import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class CachedCatalogPage(
    val cachedAtEpochMillis: Long,
    val page: CatalogPage,
)

interface CatalogCache {
    suspend fun get(key: String): CachedCatalogPage?
    suspend fun put(key: String, value: CachedCatalogPage)
    suspend fun remove(key: String)
}

object CatalogCacheKey {
    private val json = Json { encodeDefaults = true }

    fun from(request: CatalogRequest): String = json.encodeToString(request)

    internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

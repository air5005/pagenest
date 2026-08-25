package com.air5005.pagenest.discovery.repository

import com.air5005.pagenest.discovery.aggregate.ReciprocalRankFusion
import com.air5005.pagenest.discovery.cache.CachedCatalogPage
import com.air5005.pagenest.discovery.cache.CatalogCache
import com.air5005.pagenest.discovery.cache.CatalogCacheKey
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout

data class DiscoveryResult(
    val page: CatalogPage,
    val fromStaleCache: Boolean,
    val unavailableSourceIds: List<String>,
)

class OnlineDiscoveryRepository(
    private val sources: List<OnlineCatalogSource>,
    private val cache: CatalogCache,
    private val fusion: ReciprocalRankFusion = ReciprocalRankFusion(),
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val sourceTimeoutMillis: Long = DEFAULT_SOURCE_TIMEOUT_MILLIS,
) : DiscoveryCatalogRepository {
    init {
        require(sourceTimeoutMillis > 0) { "Source timeout must be positive" }
    }

    override suspend fun discover(request: CatalogRequest): DiscoveryResult {
        val key = CatalogCacheKey.from(request)
        val cached = safeCacheGet(key)
        if (cached != null && isFresh(cached, request.kind)) {
            return DiscoveryResult(cached.page, fromStaleCache = false, unavailableSourceIds = emptyList())
        }

        val outcomes = querySources(request)
        val successfulPages = outcomes.mapNotNull { (it as? SourceOutcome.Success)?.page }
        val unavailable = outcomes.mapNotNull { (it as? SourceOutcome.Failure)?.sourceId }

        if (successfulPages.isEmpty()) {
            if (cached != null) {
                return DiscoveryResult(
                    page = cached.page.copy(sourceWarnings = unavailable),
                    fromStaleCache = true,
                    unavailableSourceIds = unavailable,
                )
            }
            return DiscoveryResult(
                page = CatalogPage(emptyList(), null, unavailable),
                fromStaleCache = false,
                unavailableSourceIds = unavailable,
            )
        }

        val ranked = fusion.rank(successfulPages.map { it.books })
        val page = CatalogPage(ranked, nextPageToken = null, sourceWarnings = unavailable)
        if (page.books.isNotEmpty()) {
            safeCachePut(key, CachedCatalogPage(nowEpochMillis(), page))
        }
        return DiscoveryResult(page, fromStaleCache = false, unavailableSourceIds = unavailable)
    }

    private suspend fun querySources(request: CatalogRequest): List<SourceOutcome> = supervisorScope {
        sources.map { source ->
            async {
                try {
                    SourceOutcome.Success(
                        sourceId = source.id,
                        page = withTimeout(sourceTimeoutMillis) { source.browse(request) },
                    )
                } catch (_: TimeoutCancellationException) {
                    SourceOutcome.Failure(source.id)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    SourceOutcome.Failure(source.id)
                }
            }
        }.awaitAll()
    }

    private suspend fun safeCacheGet(key: String): CachedCatalogPage? = try {
        cache.get(key)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
    }

    private suspend fun safeCachePut(key: String, value: CachedCatalogPage) {
        try {
            cache.put(key, value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            // Cache failure must not hide successfully fetched catalog data.
        }
    }

    private fun isFresh(cached: CachedCatalogPage, kind: CatalogKind): Boolean {
        val age = nowEpochMillis() - cached.cachedAtEpochMillis
        return age >= 0 && age <= ttlMillis(kind)
    }

    private fun ttlMillis(kind: CatalogKind): Long = when (kind) {
        CatalogKind.LATEST,
        CatalogKind.SUBJECT,
        -> ONE_HOUR_MILLIS
        CatalogKind.RECOMMENDED,
        CatalogKind.POPULAR,
        CatalogKind.SEARCH,
        -> THIRTY_MINUTES_MILLIS
    }

    private sealed interface SourceOutcome {
        data class Success(val sourceId: String, val page: CatalogPage) : SourceOutcome
        data class Failure(val sourceId: String) : SourceOutcome
    }

    companion object {
        const val DEFAULT_SOURCE_TIMEOUT_MILLIS = 8_000L
        const val DETAILS_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        private const val THIRTY_MINUTES_MILLIS = 30L * 60L * 1_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
    }
}

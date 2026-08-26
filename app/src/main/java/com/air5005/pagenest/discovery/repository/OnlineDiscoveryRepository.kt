package com.air5005.pagenest.discovery.repository

import com.air5005.pagenest.discovery.aggregate.ReciprocalRankFusion
import com.air5005.pagenest.discovery.cache.CachedCatalogPage
import com.air5005.pagenest.discovery.cache.CatalogCache
import com.air5005.pagenest.discovery.cache.CatalogCacheKey
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.CatalogSourceException
import com.air5005.pagenest.discovery.model.CatalogSourceFailure
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import com.wxn.base.util.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveryResult(
    val page: CatalogPage,
    val fromStaleCache: Boolean,
    val unavailableSourceIds: List<String>,
    val sourceFailures: List<SourceFailure> = emptyList(),
)

data class SourceFailure(
    val sourceId: String,
    val failure: CatalogSourceFailure,
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
        val failures = outcomes.mapNotNull { (it as? SourceOutcome.Failure)?.failure }
        val unavailable = failures.map(SourceFailure::sourceId)

        if (successfulPages.isEmpty()) {
            if (cached != null) {
                return DiscoveryResult(
                    page = cached.page.copy(sourceWarnings = unavailable),
                    fromStaleCache = true,
                    unavailableSourceIds = unavailable,
                    sourceFailures = failures,
                )
            }
            return DiscoveryResult(
                page = CatalogPage(emptyList(), null, unavailable),
                fromStaleCache = false,
                unavailableSourceIds = unavailable,
                sourceFailures = failures,
            )
        }

        val ranked = fusion.rank(successfulPages.map { it.books })
        val page = CatalogPage(ranked, nextPageToken = null, sourceWarnings = unavailable)
        if (page.books.isNotEmpty()) {
            safeCachePut(key, CachedCatalogPage(nowEpochMillis(), page))
        }
        return DiscoveryResult(
            page,
            fromStaleCache = false,
            unavailableSourceIds = unavailable,
            sourceFailures = failures,
        )
    }

    private suspend fun querySources(request: CatalogRequest): List<SourceOutcome> = supervisorScope {
        if (sources.isEmpty()) return@supervisorScope emptyList()
        val channel = Channel<SourceOutcome>(sources.size)
        val jobs = sources.associate { source ->
            source.id to launch { channel.send(querySource(source, request)) }
        }
        val outcomes = mutableListOf<SourceOutcome>()
        var hasUsefulResult = false
        while (outcomes.size < sources.size) {
            val outcome = if (hasUsefulResult) {
                withTimeoutOrNull(SOURCE_AGGREGATION_GRACE_MILLIS) { channel.receive() }
            } else {
                channel.receive()
            } ?: break
            if (outcome is SourceOutcome.Cancelled) {
                jobs.values.forEach { it.cancel() }
                throw outcome.cause
            }
            outcomes += outcome
            if (outcome is SourceOutcome.Success && outcome.page.books.isNotEmpty()) {
                hasUsefulResult = true
            }
        }

        val completedIds = outcomes.mapTo(mutableSetOf()) { it.sourceId }
        jobs.filterKeys { it !in completedIds }.values.forEach { it.cancel() }
        jobs.values.joinAll()
        sources.filter { it.id !in completedIds }.forEach { source ->
            outcomes += sourceFailure(source.id, CatalogSourceFailure.TIMEOUT)
        }
        channel.close()
        outcomes
    }

    private suspend fun querySource(
        source: OnlineCatalogSource,
        request: CatalogRequest,
    ): SourceOutcome = try {
        SourceOutcome.Success(
            sourceId = source.id,
            page = withTimeout(sourceTimeoutMillis) { source.browse(request) },
        )
    } catch (_: TimeoutCancellationException) {
        sourceFailure(source.id, CatalogSourceFailure.TIMEOUT)
    } catch (cancelled: CancellationException) {
        SourceOutcome.Cancelled(source.id, cancelled)
    } catch (known: CatalogSourceException) {
        sourceFailure(source.id, known.failure)
    } catch (_: Throwable) {
        sourceFailure(source.id, CatalogSourceFailure.NETWORK)
    }

    private fun sourceFailure(sourceId: String, failure: CatalogSourceFailure): SourceOutcome.Failure {
        Logger.warning("DISCOVERY", "Catalog source failed id=$sourceId reason=${failure.name}")
        return SourceOutcome.Failure(SourceFailure(sourceId, failure))
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
        val sourceId: String

        data class Success(override val sourceId: String, val page: CatalogPage) : SourceOutcome
        data class Failure(val failure: SourceFailure) : SourceOutcome {
            override val sourceId: String get() = failure.sourceId
        }
        data class Cancelled(
            override val sourceId: String,
            val cause: CancellationException,
        ) : SourceOutcome
    }

    companion object {
        // Public catalog endpoints are frequently reached through mobile VPN/proxy paths.
        // Eight seconds was short enough to reject a valid Gutenberg response on HyperOS.
        const val DEFAULT_SOURCE_TIMEOUT_MILLIS = 20_000L
        const val SOURCE_AGGREGATION_GRACE_MILLIS = 1_500L
        const val DETAILS_TTL_MILLIS = 24L * 60L * 60L * 1_000L
        private const val THIRTY_MINUTES_MILLIS = 30L * 60L * 1_000L
        private const val ONE_HOUR_MILLIS = 60L * 60L * 1_000L
    }
}

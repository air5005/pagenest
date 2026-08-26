package com.air5005.pagenest.discovery.source.opds

import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.model.AcquisitionAccess
import com.air5005.pagenest.discovery.model.OnlineAcquisition
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder

object KnownOpdsSources {
    fun gutenberg(client: HttpClient): OpdsCatalogSource = OpdsCatalogSource(
        client = client,
        config = OpdsSourceConfig(
            id = "gutenberg-opds",
            baseUrl = GUTENBERG_FEED,
            allowedHosts = setOf("www.gutenberg.org", "gutenberg.org"),
            rightsStatus = RightsStatus.PUBLIC_DOMAIN,
            epubPriority = 20,
            requestPathMapper = { request ->
                URLBuilder(GUTENBERG_FEED).apply {
                    parameters.clear()
                    when (request.kind) {
                        CatalogKind.LATEST -> parameters.append("sort_order", "release_date")
                        else -> parameters.append("sort_order", "downloads")
                    }
                    request.query?.takeIf(String::isNotBlank)?.let { parameters.append("query", it) }
                    request.subject?.takeIf(String::isNotBlank)?.let { parameters.append("topic", it) }
                }.buildString()
            },
            sourceBookId = { entry -> GUTENBERG_ID.find(entry.id)?.groupValues?.get(1) },
            stableKey = { _, sourceBookId -> "gutenberg:$sourceBookId" },
            derivedAcquisitions = { _, sourceBookId ->
                listOf(
                    OnlineAcquisition(
                        sourceId = "gutenberg-opds",
                        format = OnlineBookFormat.EPUB,
                        url = "https://www.gutenberg.org/ebooks/$sourceBookId.epub3.images",
                        access = AcquisitionAccess.FREE_FULL,
                        qualityPriority = 20,
                    ),
                )
            },
        ),
    )

    fun standardEbooks(client: HttpClient): OpdsCatalogSource = OpdsCatalogSource(
        client = client,
        config = OpdsSourceConfig(
            id = "standard-ebooks",
            baseUrl = STANDARD_EBOOKS_FEED,
            allowedHosts = setOf("standardebooks.org", "www.standardebooks.org"),
            rightsStatus = RightsStatus.PUBLIC_DOMAIN,
            epubPriority = 10,
            requestPathMapper = { STANDARD_EBOOKS_FEED },
            sourceBookId = { entry ->
                entry.id.substringAfter("/ebooks/", missingDelimiterValue = "")
                    .trim('/')
                    .takeIf(String::isNotBlank)
            },
            stableKey = { _, sourceBookId -> "standard-ebooks:$sourceBookId" },
        ),
    )

    private const val GUTENBERG_FEED = "https://www.gutenberg.org/ebooks/search.opds/"
    private const val STANDARD_EBOOKS_FEED = "https://standardebooks.org/feeds/opds"
    private val GUTENBERG_ID = Regex("/ebooks/(\\d+)(?:$|[/?#.])")
}

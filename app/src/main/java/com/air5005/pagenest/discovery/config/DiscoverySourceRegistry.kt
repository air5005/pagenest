package com.air5005.pagenest.discovery.config

import com.air5005.pagenest.discovery.source.OnlineCatalogSource

enum class SourceDisabledReason {
    AUTHORIZATION_REQUIRED,
}

data class DiscoverySourceStatus(
    val id: String,
    val enabled: Boolean,
    val reason: SourceDisabledReason? = null,
)

class DiscoverySourceRegistry private constructor(
    val enabledSources: List<OnlineCatalogSource>,
    val statuses: List<DiscoverySourceStatus>,
) {
    companion object {
        fun create(
            gutendex: OnlineCatalogSource,
            gutenberg: OnlineCatalogSource,
            openLibrary: OnlineCatalogSource,
            standardEbooksFactory: () -> OnlineCatalogSource,
            standardEbooksAuthorized: Boolean,
        ): DiscoverySourceRegistry {
            require(gutendex.id == GUTENDEX_ID) { "Unexpected Gutendex source id" }
            require(gutenberg.id == GUTENBERG_ID) { "Unexpected Gutenberg source id" }
            require(openLibrary.id == OPEN_LIBRARY_ID) { "Unexpected Open Library source id" }
            val standard = standardEbooksFactory.takeIf { standardEbooksAuthorized }?.invoke()
            if (standard != null) {
                require(standard.id == STANDARD_EBOOKS_ID) { "Unexpected Standard Ebooks source id" }
            }
            return DiscoverySourceRegistry(
                enabledSources = listOfNotNull(gutendex, gutenberg, openLibrary, standard),
                statuses = listOf(
                    DiscoverySourceStatus(GUTENDEX_ID, enabled = true),
                    DiscoverySourceStatus(GUTENBERG_ID, enabled = true),
                    DiscoverySourceStatus(OPEN_LIBRARY_ID, enabled = true),
                    DiscoverySourceStatus(
                        id = STANDARD_EBOOKS_ID,
                        enabled = standard != null,
                        reason = if (standard == null) SourceDisabledReason.AUTHORIZATION_REQUIRED else null,
                    ),
                ),
            )
        }

        const val GUTENDEX_ID = "gutendex"
        const val GUTENBERG_ID = "gutenberg-opds"
        const val OPEN_LIBRARY_ID = "openlibrary"
        const val STANDARD_EBOOKS_ID = "standard-ebooks"
    }
}

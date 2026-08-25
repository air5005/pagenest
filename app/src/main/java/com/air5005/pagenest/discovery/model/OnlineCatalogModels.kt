package com.air5005.pagenest.discovery.model

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogKind {
    RECOMMENDED,
    POPULAR,
    LATEST,
    SUBJECT,
    SEARCH,
}

@Serializable
enum class CatalogLanguage {
    ALL,
    ZH,
    EN,
}

@Serializable
enum class OnlineBookFormat {
    EPUB,
    TXT,
    HTML,
    PDF,
    UNKNOWN,
}

@Serializable
enum class RightsStatus {
    PUBLIC_DOMAIN,
    FREE_FULL,
    PREVIEW_ONLY,
    BORROW_ONLY,
    UNKNOWN,
}

@Serializable
enum class AcquisitionAccess {
    FREE_FULL,
    PREVIEW,
    BORROW,
    EXTERNAL,
}

@Serializable
data class CatalogRequest(
    val kind: CatalogKind,
    val language: CatalogLanguage = CatalogLanguage.ALL,
    val subject: String? = null,
    val query: String? = null,
    val pageToken: String? = null,
    val pageSize: Int = 20,
)

@Serializable
data class OnlineAcquisition(
    val sourceId: String,
    val format: OnlineBookFormat,
    val url: String,
    val access: AcquisitionAccess,
    val qualityPriority: Int,
) {
    val canReadDirectly: Boolean
        get() = access == AcquisitionAccess.FREE_FULL && url.startsWith("https://")
}

@Serializable
data class SourceReference(
    val sourceId: String,
    val sourceBookId: String,
)

@Serializable
data class OnlineBook(
    val stableKey: String,
    val title: String,
    val authors: List<String>,
    val summary: String?,
    val languages: List<String>,
    val subjects: List<String>,
    val coverUrl: String?,
    val sourceRank: Int,
    val popularity: Double?,
    val catalogUpdatedAtEpochMillis: Long?,
    val rightsStatus: RightsStatus,
    val sourceReferences: List<SourceReference>,
    val acquisitions: List<OnlineAcquisition>,
) {
    fun bestReadableAcquisition(): OnlineAcquisition? = acquisitions
        .asSequence()
        .filter(OnlineAcquisition::canReadDirectly)
        .sortedWith(
            compareBy<OnlineAcquisition> { it.qualityPriority }
                .thenBy { it.format.ordinal }
                .thenBy { it.sourceId }
                .thenBy { it.url },
        )
        .firstOrNull()
}

@Serializable
data class CatalogPage(
    val books: List<OnlineBook>,
    val nextPageToken: String?,
    val sourceWarnings: List<String> = emptyList(),
)

@Serializable
data class SourceBookDetails(
    val book: OnlineBook,
    val related: List<SourceReference> = emptyList(),
)

enum class CatalogSourceFailure {
    NETWORK,
    TIMEOUT,
    HTTP,
    RESPONSE_TOO_LARGE,
    MALFORMED,
    UNTRUSTED_URL,
}

class CatalogSourceException(
    val failure: CatalogSourceFailure,
) : RuntimeException(failure.name)

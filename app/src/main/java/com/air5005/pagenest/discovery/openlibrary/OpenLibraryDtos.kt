package com.air5005.pagenest.discovery.openlibrary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class OpenLibrarySearchDto(
    val docs: List<OpenLibraryWorkDto> = emptyList(),
)

@Serializable
internal data class OpenLibraryWorkDto(
    val key: String,
    val title: String,
    @SerialName("author_name") val authorNames: List<String> = emptyList(),
    @SerialName("cover_i") val coverId: Long? = null,
    @SerialName("first_publish_year") val firstPublishYear: Int? = null,
    @SerialName("edition_count") val editionCount: Int? = null,
    @SerialName("public_scan_b") val publicScan: Boolean = false,
    @SerialName("ebook_access") val ebookAccess: String? = null,
)

@Serializable
enum class OpenLibraryEbookAccess {
    PUBLIC,
    BORROWABLE,
    PREVIEW,
    NONE,
    UNKNOWN,
}

@Serializable
data class OpenLibraryMetadata(
    val workId: String,
    val coverUrl: String?,
    val firstPublishYear: Int?,
    val editionCount: Int?,
    val publicScan: Boolean,
    val ebookAccess: OpenLibraryEbookAccess,
    val sourcePageUrl: String,
)

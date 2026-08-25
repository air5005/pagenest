package com.air5005.pagenest.discovery.source.gutendex

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GutendexPageDto(
    val count: Int = 0,
    val next: String? = null,
    val previous: String? = null,
    val results: List<GutendexBookDto> = emptyList(),
)

@Serializable
internal data class GutendexBookDto(
    val id: Int,
    val title: String,
    val authors: List<GutendexPersonDto> = emptyList(),
    val summaries: List<String> = emptyList(),
    val subjects: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val copyright: Boolean? = null,
    val formats: Map<String, String> = emptyMap(),
    @SerialName("download_count") val downloadCount: Int = 0,
)

@Serializable
internal data class GutendexPersonDto(
    val name: String,
)

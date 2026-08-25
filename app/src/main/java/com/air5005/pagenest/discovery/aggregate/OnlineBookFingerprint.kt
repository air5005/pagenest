package com.air5005.pagenest.discovery.aggregate

import com.air5005.pagenest.discovery.model.OnlineBook
import java.text.Normalizer
import java.util.Locale

object OnlineBookFingerprint {
    fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(PUNCTUATION_OR_SPACE, " ")
        .trim()

    fun metadata(book: OnlineBook): String? {
        val title = normalize(book.title)
        val author = book.authors.firstOrNull()?.let(::normalize).orEmpty()
        val language = book.languages.firstOrNull()?.let(::normalize).orEmpty()
        if (title.isBlank() || author.isBlank() || language.isBlank()) return null
        return "$title|$author|$language"
    }

    fun matches(first: OnlineBook, second: OnlineBook): Boolean {
        val firstIds = explicitIds(first)
        val secondIds = explicitIds(second)
        if (firstIds.any { (namespace, ids) -> ids.any { it in secondIds[namespace].orEmpty() } }) {
            return true
        }
        if (hasConflictingIds(firstIds, secondIds)) return false
        val firstMetadata = metadata(first) ?: return false
        return firstMetadata == metadata(second)
    }

    internal fun explicitIds(book: OnlineBook): Map<String, Set<String>> = book.sourceReferences
        .groupBy(
            keySelector = { reference -> canonicalNamespace(reference.sourceId) },
            valueTransform = { reference -> reference.sourceBookId.trim() },
        )
        .mapValues { (_, ids) -> ids.filter(String::isNotBlank).toSet() }

    internal fun canonicalStableKey(books: List<OnlineBook>): String {
        val explicit = books.flatMap { book ->
            explicitIds(book).flatMap { (namespace, ids) -> ids.map { namespace to it } }
        }
        explicit.filter { it.first == GUTENBERG_NAMESPACE }
            .minByOrNull { it.second }
            ?.let { return "${it.first}:${it.second}" }
        return books.minWithOrNull(
            compareBy<OnlineBook> { it.bestReadableAcquisition()?.qualityPriority ?: Int.MAX_VALUE }
                .thenBy { it.stableKey },
        )?.stableKey.orEmpty()
    }

    private fun hasConflictingIds(
        first: Map<String, Set<String>>,
        second: Map<String, Set<String>>,
    ): Boolean = first.any { (namespace, ids) ->
        val otherIds = second[namespace].orEmpty()
        otherIds.isNotEmpty() && ids.intersect(otherIds).isEmpty()
    }

    private fun canonicalNamespace(sourceId: String): String = when (sourceId) {
        "gutendex", "gutenberg-opds" -> GUTENBERG_NAMESPACE
        else -> sourceId
    }

    private const val GUTENBERG_NAMESPACE = "gutenberg"
    private val PUNCTUATION_OR_SPACE = Regex("[\\p{P}\\p{S}\\s]+")
}

package com.air5005.pagenest.speech.model

import com.wxn.base.bean.Locator

data class SpeechPosition(
    val bookId: Long,
    val chapterIndex: Int,
    val pageIndex: Int?,
    val paragraphIndex: Int,
    val textOffset: Int,
)

data class SpeechSegment(
    val id: String,
    val position: SpeechPosition,
    val partIndex: Int,
    val text: String,
    val locator: Locator,
)

enum class SpeechMode { OFFLINE, ONLINE, AUTO }

sealed interface SpeechError {
    val kind: Kind

    enum class Kind { NETWORK, RATE_LIMIT, SERVICE, AUTH, CONFIGURATION, QUOTA, CONTENT, ENGINE, DECODE }

    data object NetworkTimeout : SpeechError { override val kind = Kind.NETWORK }
    data object RateLimited : SpeechError { override val kind = Kind.RATE_LIMIT }
    data object ServiceUnavailable : SpeechError { override val kind = Kind.SERVICE }
    data object InvalidCredentials : SpeechError { override val kind = Kind.AUTH }
    data object InvalidRegion : SpeechError { override val kind = Kind.CONFIGURATION }
    data object QuotaExceeded : SpeechError { override val kind = Kind.QUOTA }
    data object NoExtractableText : SpeechError { override val kind = Kind.CONTENT }
    data object SystemTtsUnavailable : SpeechError { override val kind = Kind.ENGINE }
    data object MissingLanguageData : SpeechError { override val kind = Kind.ENGINE }
    data object UnsupportedLocale : SpeechError { override val kind = Kind.ENGINE }
    data object AudioDecodeFailure : SpeechError { override val kind = Kind.DECODE }
}

sealed interface SpeechPlaybackState {
    data object Idle : SpeechPlaybackState
    data class Preparing(val segment: SpeechSegment) : SpeechPlaybackState
    data class Playing(val segment: SpeechSegment, val engineId: String) : SpeechPlaybackState
    data class Paused(val segment: SpeechSegment) : SpeechPlaybackState
    data object Completed : SpeechPlaybackState
    data class Error(val error: SpeechError, val segment: SpeechSegment?) : SpeechPlaybackState
}

fun SpeechPlaybackState.currentSegment(): SpeechSegment? = when (this) {
    is SpeechPlaybackState.Preparing -> segment
    is SpeechPlaybackState.Playing -> segment
    is SpeechPlaybackState.Paused -> segment
    is SpeechPlaybackState.Error -> segment
    SpeechPlaybackState.Idle, SpeechPlaybackState.Completed -> null
}

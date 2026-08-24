package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.model.SpeechError

data class RetryPolicy(
    val delaysMillis: List<Long> = listOf(500L, 1_500L),
    val retryable: Set<SpeechError.Kind> = setOf(
        SpeechError.Kind.NETWORK,
        SpeechError.Kind.RATE_LIMIT,
        SpeechError.Kind.SERVICE,
    ),
)

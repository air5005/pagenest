package com.air5005.pagenest.speech.settings

import com.air5005.pagenest.speech.model.SpeechError

/** Allows one classified AUTO-fallback notice for each prepared reader session. */
internal class FallbackNoticePolicy {
    private var shown = false

    fun startSession() {
        shown = false
    }

    fun noticeFor(error: SpeechError): SpeechError? {
        if (shown) return null
        shown = true
        return error
    }
}

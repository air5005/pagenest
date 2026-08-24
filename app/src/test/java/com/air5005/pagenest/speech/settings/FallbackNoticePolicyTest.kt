package com.air5005.pagenest.speech.settings

import com.air5005.pagenest.speech.model.SpeechError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FallbackNoticePolicyTest {
    @Test
    fun `persistent outage is reported once per reader session`() {
        val policy = FallbackNoticePolicy()

        policy.startSession()
        assertEquals(SpeechError.NetworkTimeout, policy.noticeFor(SpeechError.NetworkTimeout))
        assertNull(policy.noticeFor(SpeechError.NetworkTimeout))
        assertNull(policy.noticeFor(SpeechError.ServiceUnavailable))

        policy.startSession()
        assertEquals(SpeechError.ServiceUnavailable, policy.noticeFor(SpeechError.ServiceUnavailable))
    }
}

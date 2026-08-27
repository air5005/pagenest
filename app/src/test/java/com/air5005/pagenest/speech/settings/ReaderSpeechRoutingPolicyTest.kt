package com.air5005.pagenest.speech.settings

import com.air5005.pagenest.speech.model.SpeechMode
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderSpeechRoutingPolicyTest {
    @Test
    fun `auto uses system when online consent has not been granted`() {
        assertEquals(
            SpeechMode.OFFLINE,
            ReaderSpeechRoutingPolicy.effectiveMode(
                requested = SpeechMode.AUTO,
                onlineConsentGranted = false,
                azureConfigured = true,
            ),
        )
    }

    @Test
    fun `auto uses system when azure credentials are absent`() {
        assertEquals(
            SpeechMode.OFFLINE,
            ReaderSpeechRoutingPolicy.effectiveMode(
                requested = SpeechMode.AUTO,
                onlineConsentGranted = true,
                azureConfigured = false,
            ),
        )
    }

    @Test
    fun `auto remains automatic when online use is configured and consented`() {
        assertEquals(
            SpeechMode.AUTO,
            ReaderSpeechRoutingPolicy.effectiveMode(
                requested = SpeechMode.AUTO,
                onlineConsentGranted = true,
                azureConfigured = true,
            ),
        )
    }

    @Test
    fun `explicit modes are preserved`() {
        assertEquals(
            SpeechMode.OFFLINE,
            ReaderSpeechRoutingPolicy.effectiveMode(SpeechMode.OFFLINE, false, false),
        )
        assertEquals(
            SpeechMode.ONLINE,
            ReaderSpeechRoutingPolicy.effectiveMode(SpeechMode.ONLINE, true, false),
        )
    }
}

package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPlaybackState
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.wxn.base.bean.Locator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechControlPolicyTest {
    private val policy = SpeechControlPolicy

    @Test fun `scan PDF error maps to exact Chinese message`() {
        assertEquals("此 PDF 为扫描版，暂不支持语音朗读", policy.messageFor(SpeechError.NoExtractableText))
    }

    @Test fun `online failures map to actionable exact Chinese messages`() {
        val cases = mapOf(
            SpeechError.NetworkTimeout to "网络连接超时，请检查网络后重试",
            SpeechError.InvalidCredentials to "Azure Speech Key 无效，请检查后重试",
            SpeechError.InvalidRegion to "Azure Region 无效或与 Key 不匹配",
            SpeechError.RateLimited to "请求过于频繁，请稍后再试",
            SpeechError.QuotaExceeded to "Azure 余额不足，请充值后重试",
            SpeechError.ServiceUnavailable to "Azure 服务暂时不可用，请稍后重试",
        )
        cases.forEach { (error, message) -> assertEquals(message, policy.messageFor(error)) }
    }

    @Test fun `engine indicator exposes actual engine and fallback`() {
        assertEquals("Azure 在线", policy.engineLabel("azure", fellBack = false))
        assertEquals("系统离线（已从在线回退）", policy.engineLabel("system", fellBack = true))
        assertEquals("系统离线", policy.engineLabel("system", fellBack = false))
    }

    @Test fun `timer choices are off fifteen thirty and sixty minutes`() {
        assertEquals(listOf(null, 15, 30, 60), policy.sleepTimerChoices)
    }

    @Test fun `terminal playback must prepare a reader while paused playback resumes`() {
        assertTrue(policy.requiresPreparation(SpeechPlaybackState.Completed))
        assertTrue(policy.requiresPreparation(SpeechPlaybackState.Error(SpeechError.NetworkTimeout, segment())))
        assertTrue(policy.requiresPreparation(SpeechPlaybackState.Idle))
        assertFalse(policy.requiresPreparation(SpeechPlaybackState.Paused(segment())))
    }

    @Test fun `settings event policy renders consent as dialog and exact messages as snackbar`() {
        assertEquals(
            SpeechSettingsEventPresentation.OnlineConsentDialog,
            SpeechSettingsEventPolicy.presentationFor(SpeechUiEvent.RequestOnlineConsent),
        )
        assertEquals(
            SpeechSettingsEventPresentation.Snackbar("Azure 连接成功"),
            SpeechSettingsEventPolicy.presentationFor(SpeechUiEvent.ShowMessage("Azure 连接成功")),
        )
        assertEquals(
            SpeechSettingsEventPresentation.Snackbar("网络连接超时，请检查网络后重试"),
            SpeechSettingsEventPolicy.presentationFor(
                SpeechUiEvent.ShowFallbackMessage("网络连接超时，请检查网络后重试"),
            ),
        )
    }

    private fun segment() = SpeechSegment(
        id = "segment",
        position = SpeechPosition(1, 0, 0, 0, 0),
        partIndex = 0,
        text = "正文",
        locator = Locator(text = "", progression = 0.0),
    )
}

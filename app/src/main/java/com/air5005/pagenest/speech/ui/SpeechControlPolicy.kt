package com.air5005.pagenest.speech.ui

import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechPlaybackState

object SpeechControlPolicy {
    const val ONLINE_CONSENT = "在线朗读会将当前段落文本发送给 Azure 生成语音，是否继续？"
    val sleepTimerChoices: List<Int?> = listOf(null, 15, 30, 60)

    fun messageFor(error: SpeechError): String = when (error) {
        SpeechError.NoExtractableText -> "此 PDF 为扫描版，暂不支持语音朗读"
        SpeechError.NetworkTimeout -> "网络连接超时，请检查网络后重试"
        SpeechError.InvalidCredentials -> "Azure Speech Key 无效，请检查后重试"
        SpeechError.InvalidRegion -> "Azure Region 无效或与 Key 不匹配"
        SpeechError.RateLimited -> "请求过于频繁，请稍后再试"
        SpeechError.QuotaExceeded -> "Azure 余额不足，请充值后重试"
        SpeechError.ServiceUnavailable -> "Azure 服务暂时不可用，请稍后重试"
        SpeechError.SystemTtsUnavailable,
        SpeechError.SystemTtsInitializationFailed,
        SpeechError.SystemTtsStartFailed,
        SpeechError.SystemTtsPlaybackFailed,
        SpeechError.NoOfflineVoiceAvailable -> "系统语音引擎不可用"
        SpeechError.MissingLanguageData -> "系统缺少所选语言的语音数据"
        SpeechError.UnsupportedLocale -> "系统语音引擎不支持所选语言"
        SpeechError.AudioDecodeFailure -> "语音音频解码失败"
    }

    fun engineLabel(engineId: String, fellBack: Boolean): String = when {
        fellBack -> "系统离线（已从在线回退）"
        engineId == "azure" -> "Azure 在线"
        engineId == "system" -> "系统离线"
        else -> "等待朗读"
    }

    fun readerStatusLabel(playback: SpeechPlaybackState, engineLabel: String): String =
        (playback as? SpeechPlaybackState.Error)?.let { messageFor(it.error) } ?: engineLabel

    fun requiresPreparation(playback: SpeechPlaybackState): Boolean =
        playback !is SpeechPlaybackState.Paused
}

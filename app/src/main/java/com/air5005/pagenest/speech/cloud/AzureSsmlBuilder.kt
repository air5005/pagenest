package com.air5005.pagenest.speech.cloud

import java.util.Locale
import kotlin.math.roundToInt

object AzureSsmlBuilder {
    const val MAX_TEXT_CODE_POINTS = 500

    fun build(
        text: String,
        localeTag: String,
        voiceId: String?,
        rate: Float,
        pitch: Float,
    ): String {
        require(text.isNotBlank()) { "Speech text is empty" }
        require(text.codePointCount(0, text.length) <= MAX_TEXT_CODE_POINTS) { "Speech text is too large" }
        require(rate.isFinite() && rate > 0f) { "Speech rate is invalid" }
        require(pitch.isFinite() && pitch > 0f) { "Speech pitch is invalid" }

        val escapedText = escapeXml(text)
        val escapedLocale = escapeXml(localeTag.ifBlank { DEFAULT_LOCALE })
        val selectedVoice = escapeXml(voiceId?.takeIf(String::isNotBlank) ?: DEFAULT_VOICE)
        val ratePercent = ((rate - 1f) * 100f).roundToInt().coerceIn(-100, 200)
        val pitchPercent = ((pitch - 1f) * 100f).roundToInt().coerceIn(-100, 100)
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" " +
            "xml:lang=\"$escapedLocale\"><voice name=\"$selectedVoice\"><prosody " +
            "rate=\"${signedPercent(ratePercent)}\" pitch=\"${signedPercent(pitchPercent)}\">" +
            "$escapedText</prosody></voice></speak>"
    }

    private fun signedPercent(value: Int): String = String.format(Locale.ROOT, "%+d%%", value)

    private fun escapeXml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            append(
                when (character) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&apos;"
                    else -> character
                },
            )
        }
    }

    private const val DEFAULT_LOCALE = "zh-CN"
    private const val DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
}

package com.air5005.pagenest.speech.cloud

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AzureSsmlBuilderTest {
    @Test
    fun `text and attributes are escaped in SSML`() {
        val ssml = AzureSsmlBuilder.build(
            text = "甲<&乙\"'",
            localeTag = "zh-CN\" on-load=\"bad",
            voiceId = "zh-CN-XiaoxiaoNeural' bad='true",
            rate = 1f,
            pitch = 1f,
        )

        assertTrue(ssml.contains("甲&lt;&amp;乙&quot;&apos;"))
        assertTrue(ssml.contains("xml:lang=\"zh-CN&quot; on-load=&quot;bad\""))
        assertTrue(ssml.contains("name=\"zh-CN-XiaoxiaoNeural&apos; bad=&apos;true\""))
        assertFalse(ssml.contains("<script"))
    }

    @Test
    fun `five hundred Unicode code points are accepted even when surrogate pairs are used`() {
        val ssml = AzureSsmlBuilder.build(
            text = "😀".repeat(500),
            localeTag = "zh-CN",
            voiceId = null,
            rate = 1f,
            pitch = 1f,
        )

        assertTrue(ssml.contains("😀".repeat(500)))
    }

    @Test
    fun `more than five hundred Unicode code points are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AzureSsmlBuilder.build(
                text = "😀".repeat(501),
                localeTag = "zh-CN",
                voiceId = null,
                rate = 1f,
                pitch = 1f,
            )
        }
    }

    @Test
    fun `blank text is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            AzureSsmlBuilder.build("  ", "zh-CN", null, 1f, 1f)
        }
    }
}

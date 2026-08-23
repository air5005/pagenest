package com.air5005.pagenest.speech.engine

import android.speech.tts.TextToSpeech
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Locale

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTtsEngineTest {
    @Test
    fun `completion resumes exactly once and cancellation stops utterance`() = runTest {
        val platform = FakePlatformTts()
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)
        val firstRequest = request("正文")

        val completed = async { engine.speak(firstRequest) }
        runCurrent()
        platform.complete(platform.spoken.single().utteranceId)
        assertEquals(SpeechEngineResult.Completed, completed.await())

        val cancelled = async { engine.speak(request("下一段")) }
        runCurrent()
        cancelled.cancelAndJoin()
        assertEquals(1, platform.stopCalls)
    }

    @Test
    fun `voices returns only platform voices for the requested locale`() = runTest {
        val platform = FakePlatformTts().apply {
            availableVoices += PlatformSpeechVoice("zh-main", "Chinese", "zh-CN")
            availableVoices += PlatformSpeechVoice("en-main", "English", "en-US")
            availableVoices += PlatformSpeechVoice("zh-alt", "Chinese alternate", "zh-CN")
        }
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)

        assertEquals(
            listOf(
                SpeechVoice("zh-main", "Chinese", "zh-CN"),
                SpeechVoice("zh-alt", "Chinese alternate", "zh-CN"),
            ),
            engine.voices("zh-CN"),
        )
    }

    @Test
    fun `speak applies bounded configuration and uses queue flush with a generation utterance id`() = runTest {
        val platform = FakePlatformTts().apply {
            availableVoices += PlatformSpeechVoice("zh-main", "Chinese", "zh-CN")
        }
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)
        assertEquals("system", engine.id)
        val configured = request("边界").copy(voiceId = "zh-main", rate = 0.1f, pitch = 4f)

        val result = async { engine.speak(configured) }
        runCurrent()

        assertEquals(listOf("zh-CN"), platform.languageTags)
        assertEquals(listOf("zh-main"), platform.selectedVoices)
        assertEquals(listOf(0.25f), platform.rates)
        assertEquals(listOf(2f), platform.pitches)
        assertEquals(
            FakePlatformTts.Spoken("边界", TextToSpeech.QUEUE_FLUSH, "7:segment-边界"),
            platform.spoken.single(),
        )
        platform.complete("7:segment-边界")
        assertEquals(SpeechEngineResult.Completed, result.await())

        val oppositeBounds = request("反向边界").copy(rate = 4f, pitch = 0.1f)
        val oppositeResult = async { engine.speak(oppositeBounds) }
        runCurrent()
        assertEquals(listOf(0.25f, 2f), platform.rates)
        assertEquals(listOf(2f, 0.25f), platform.pitches)
        platform.complete(platform.spoken.last().utteranceId)
        assertEquals(SpeechEngineResult.Completed, oppositeResult.await())
    }

    @Test
    fun `speak categorizes initialization and locale availability failures`() = runTest {
        val unavailable = SystemTtsEngine(PlatformTextToSpeechFactory { null }, backgroundScope)
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.SystemTtsUnavailable),
            unavailable.speak(request("无引擎")),
        )

        val missingPlatform = FakePlatformTts().apply {
            setLanguageResult = TextToSpeech.LANG_MISSING_DATA
        }
        val missing = SystemTtsEngine(
            PlatformTextToSpeechFactory { missingPlatform },
            backgroundScope,
        )
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.MissingLanguageData),
            withTimeout(100) { missing.speak(request("缺数据")) },
        )

        val unsupportedPlatform = FakePlatformTts().apply {
            setLanguageResult = TextToSpeech.LANG_NOT_SUPPORTED
        }
        val unsupported = SystemTtsEngine(
            PlatformTextToSpeechFactory { unsupportedPlatform },
            backgroundScope,
        )
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.UnsupportedLocale),
            withTimeout(100) { unsupported.speak(request("不支持")) },
        )
    }

    @Test
    fun `platform start and callback errors return engine failure`() = runTest {
        val callbackPlatform = FakePlatformTts()
        val callbackEngine = SystemTtsEngine(
            PlatformTextToSpeechFactory { callbackPlatform },
            backgroundScope,
        )
        val callbackResult = async { callbackEngine.speak(request("回调错误")) }
        runCurrent()
        callbackPlatform.error(callbackPlatform.spoken.single().utteranceId)
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.SystemTtsUnavailable),
            withTimeout(100) { callbackResult.await() },
        )

        val startPlatform = FakePlatformTts().apply { speakResult = TextToSpeech.ERROR }
        val startEngine = SystemTtsEngine(
            PlatformTextToSpeechFactory { startPlatform },
            backgroundScope,
        )
        assertEquals(
            SpeechEngineResult.Failed(com.air5005.pagenest.speech.model.SpeechError.SystemTtsUnavailable),
            withTimeout(100) { startEngine.speak(request("启动错误")) },
        )
    }

    @Test
    fun `stop cancels the active utterance and stale callbacks cannot complete the next one`() = runTest {
        val platform = FakePlatformTts()
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)
        val first = async { engine.speak(request("旧段")) }
        runCurrent()
        val oldId = platform.spoken.single().utteranceId

        engine.stop()
        assertEquals(SpeechEngineResult.Cancelled, withTimeout(100) { first.await() })
        assertEquals(1, platform.stopCalls)

        val second = async { engine.speak(request("新段")) }
        runCurrent()
        val newId = platform.spoken.last().utteranceId
        platform.complete(oldId)
        platform.error(oldId)
        assertFalse(second.isCompleted)
        platform.complete(newId)
        assertEquals(SpeechEngineResult.Completed, second.await())
    }

    @Test
    fun `close cancels playback and releases platform resources exactly once`() = runTest {
        val platform = FakePlatformTts()
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)
        val playing = async { engine.speak(request("释放")) }
        runCurrent()

        engine.close()
        engine.close()

        assertEquals(SpeechEngineResult.Cancelled, withTimeout(100) { playing.await() })
        assertEquals(1, platform.stopCalls)
        assertEquals(1, platform.shutdownCalls)
    }

    @Test
    fun `duplicate callbacks and cancellation complete each continuation at most once under stress`() = runTest {
        val platform = FakePlatformTts()
        val engine = SystemTtsEngine(PlatformTextToSpeechFactory { platform }, backgroundScope)

        repeat(20) { iteration ->
            val job = async {
                engine.speak(request("压力-$iteration").copy(generationId = iteration.toLong()))
            }
            runCurrent()
            val utteranceId = platform.spoken.last().utteranceId
            if (iteration % 2 == 0) {
                platform.complete(utteranceId)
                platform.complete(utteranceId)
                platform.error(utteranceId)
                platform.stopped(utteranceId)
                assertEquals(SpeechEngineResult.Completed, job.await())
            } else {
                job.cancelAndJoin()
                platform.complete(utteranceId)
                platform.error(utteranceId)
                platform.stopped(utteranceId)
            }
        }

        assertEquals(10, platform.stopCalls)
        assertEquals(1, platform.listenerInstallCalls)
    }

    private fun request(text: String) = SpeechRequest(
        generationId = 7,
        segment = SpeechSegment(
            id = "segment-$text",
            position = SpeechPosition(
                bookId = 11,
                chapterIndex = 2,
                pageIndex = null,
                paragraphIndex = 3,
                textOffset = 0,
            ),
            partIndex = 0,
            text = text,
            locator = Locator(text = text, progression = 0.25),
        ),
        localeTag = "zh-CN",
        voiceId = null,
        rate = 1f,
        pitch = 1f,
    )
}

private class FakePlatformTts : PlatformTextToSpeech {
    data class Spoken(val text: String, val queueMode: Int, val utteranceId: String)

    var listener: PlatformUtteranceProgressListener? = null
    val spoken = mutableListOf<Spoken>()
    val availableVoices = mutableListOf<PlatformSpeechVoice>()
    val selectedVoices = mutableListOf<String>()
    val languageTags = mutableListOf<String>()
    val rates = mutableListOf<Float>()
    val pitches = mutableListOf<Float>()
    var stopCalls = 0
    var shutdownCalls = 0
    var listenerInstallCalls = 0
    var setLanguageResult = TextToSpeech.LANG_AVAILABLE
    var speakResult = TextToSpeech.SUCCESS

    override fun setProgressListener(listener: PlatformUtteranceProgressListener) {
        listenerInstallCalls++
        this.listener = listener
    }

    override fun languageStatus(locale: Locale): Int = TextToSpeech.LANG_AVAILABLE

    override fun voices(): List<PlatformSpeechVoice> = availableVoices.toList()

    override fun selectVoice(id: String): Boolean {
        selectedVoices += id
        return availableVoices.any { it.id == id }
    }

    override fun setLanguage(locale: Locale): Int {
        languageTags += locale.toLanguageTag()
        return setLanguageResult
    }

    override fun setRate(rate: Float): Int {
        rates += rate
        return TextToSpeech.SUCCESS
    }

    override fun setPitch(pitch: Float): Int {
        pitches += pitch
        return TextToSpeech.SUCCESS
    }

    override fun speak(text: String, queueMode: Int, utteranceId: String): Int {
        spoken += Spoken(text, queueMode, utteranceId)
        return speakResult
    }

    override fun stop(): Int {
        stopCalls++
        return TextToSpeech.SUCCESS
    }

    override fun shutdown() {
        shutdownCalls++
    }

    fun complete(utteranceId: String) {
        listener?.onDone(utteranceId)
    }

    fun error(utteranceId: String) {
        listener?.onError(utteranceId)
    }

    fun stopped(utteranceId: String) {
        listener?.onStop(utteranceId)
    }
}

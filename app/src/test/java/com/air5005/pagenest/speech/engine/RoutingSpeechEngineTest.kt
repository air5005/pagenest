package com.air5005.pagenest.speech.engine

import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechSegment
import com.wxn.base.bean.Locator
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoutingSpeechEngineTest {
    @Test
    fun `routed result exposes actual engine and fallback without changing result`() = runTest {
        val indicators = mutableListOf<SpeechRouteIndicator>()
        val engine = RoutingSpeechEngine(
            mode = SpeechMode.AUTO,
            route = { _, _, onSelected ->
                onSelected(SpeechRouteIndicator("system", fellBack = true))
                RoutedSpeechResult(SpeechEngineResult.Completed, "system", fellBack = true)
            },
            stopRoute = {},
            onRoute = indicators::add,
        )

        assertEquals(SpeechEngineResult.Completed, engine.speak(request()))
        assertEquals(listOf(SpeechRouteIndicator("system", fellBack = true)), indicators)
        assertEquals("azure", engine.id)
    }

    @Test
    fun `stop delegates while close stays non terminal for singleton engines`() = runTest {
        var stops = 0
        val engine = RoutingSpeechEngine(
            mode = SpeechMode.OFFLINE,
            route = { _, _, onSelected ->
                onSelected(SpeechRouteIndicator("system", false))
                RoutedSpeechResult(SpeechEngineResult.Completed, "system")
            },
            stopRoute = { stops++ },
            onRoute = {},
        )

        engine.stop()
        engine.close()

        assertEquals(1, stops)
        assertEquals("system", engine.id)
        assertTrue(engine.voices("zh-CN").isEmpty())
    }

    @Test
    fun `fallback indicator is published before fallback playback completes`() = runTest {
        val playbackGate = CompletableDeferred<Unit>()
        val indicators = mutableListOf<SpeechRouteIndicator>()
        val engine = RoutingSpeechEngine(
            mode = SpeechMode.AUTO,
            route = { _, _, onSelected ->
                onSelected(SpeechRouteIndicator("azure", false))
                onSelected(SpeechRouteIndicator("system", true))
                playbackGate.await()
                RoutedSpeechResult(SpeechEngineResult.Completed, "system", true)
            },
            stopRoute = {},
            onRoute = indicators::add,
        )

        val speaking = async { engine.speak(request()) }
        runCurrent()

        assertEquals(SpeechRouteIndicator("system", true), indicators.last())
        playbackGate.complete(Unit)
        assertEquals(SpeechEngineResult.Completed, speaking.await())
    }

    private fun request() = SpeechRequest(
        generationId = 1,
        segment = SpeechSegment(
            id = "s",
            position = SpeechPosition(1, 0, 0, 0, 0),
            partIndex = 0,
            text = "正文",
            locator = Locator(text = "", progression = 0.0),
        ),
        localeTag = "zh-CN",
        voiceId = null,
        rate = 1f,
        pitch = 1f,
    )
}

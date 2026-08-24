package com.air5005.pagenest.speech.settings

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.air5005.pagenest.speech.content.SpeechContentSource
import com.air5005.pagenest.speech.engine.RoutingSpeechEngine
import com.air5005.pagenest.speech.engine.SpeechEngineRouter
import com.air5005.pagenest.speech.engine.SpeechRouteIndicator
import com.air5005.pagenest.speech.model.SpeechPosition
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.playback.AppSpeechController
import com.air5005.pagenest.speech.playback.SpeechControllerSnapshot
import com.air5005.pagenest.speech.playback.SpeechNowPlaying
import com.air5005.pagenest.speech.playback.SpeechPlaybackService
import com.air5005.pagenest.speech.progress.RoomSpeechProgressCommitter
import com.air5005.pagenest.speech.session.SpeechHighlightSink
import com.air5005.pagenest.speech.session.SpeechOptions
import com.air5005.pagenest.speech.session.SpeechSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class ReaderSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val router: SpeechEngineRouter,
    private val preferencesRepository: SpeechPreferencesRepository,
    private val progressCommitter: RoomSpeechProgressCommitter,
) : SpeechPlaybackActions {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _routeIndicator = MutableStateFlow(SpeechRouteIndicator("system", false))
    override val routeIndicator: StateFlow<SpeechRouteIndicator> = _routeIndicator
    private val _fallbackErrors = MutableSharedFlow<SpeechError>(extraBufferCapacity = 1)
    override val fallbackErrors: SharedFlow<SpeechError> = _fallbackErrors
    private val fallbackNoticePolicy = FallbackNoticePolicy()
    override val playbackSnapshot: StateFlow<SpeechControllerSnapshot> = AppSpeechController.snapshot

    private var pending: PendingReader? = null
    private var activeSession: SpeechSession? = null
    val isActive: Boolean
        get() = when (playbackSnapshot.value.playbackState) {
            is com.air5005.pagenest.speech.model.SpeechPlaybackState.Preparing,
            is com.air5005.pagenest.speech.model.SpeechPlaybackState.Playing,
            is com.air5005.pagenest.speech.model.SpeechPlaybackState.Paused -> true
            else -> false
        }

    fun prepare(
        source: SpeechContentSource,
        highlightSink: SpeechHighlightSink,
        nowPlaying: SpeechNowPlaying,
        initialPosition: SpeechPosition? = null,
    ) {
        pending?.source?.close()
        pending = PendingReader(source, highlightSink, nowPlaying, initialPosition)
    }

    override fun start() {
        scope.launch {
            val prepared = pending
            if (prepared == null) {
                AppSpeechController.resume()
                return@launch
            }
            pending = null
            val preferences = preferencesRepository.preferences.first()
            fallbackNoticePolicy.startSession()
            _routeIndicator.value = SpeechRouteIndicator(
                engineId = if (preferences.mode == com.air5005.pagenest.speech.model.SpeechMode.OFFLINE) "system" else "azure",
                fellBack = false,
            )
            prepared.initialPosition?.let { prepared.source.seek(it) }
            val engine = RoutingSpeechEngine(
                mode = preferences.mode,
                route = { request, mode, onRoute -> router.speak(request, mode, onRoute) },
                stopRoute = router::stop,
                onRoute = { indicator ->
                    _routeIndicator.value = indicator
                    indicator.fallbackError
                        ?.let(fallbackNoticePolicy::noticeFor)
                        ?.let(_fallbackErrors::tryEmit)
                },
            )
            val session = SpeechSession(
                engine = engine,
                progressCommitter = progressCommitter,
                highlightSink = prepared.highlightSink,
                ownerScope = scope,
            )
            activeSession = session
            AppSpeechController.attach(session, prepared.nowPlaying)
            session.start(
                prepared.source,
                SpeechOptions(
                    mode = preferences.mode,
                    localeTag = preferences.localeTag,
                    voiceId = preferences.voiceId,
                    rate = preferences.rate,
                    pitch = preferences.pitch,
                ),
            )
            ContextCompat.startForegroundService(context, Intent(context, SpeechPlaybackService::class.java))
        }
    }

    override fun pause() = AppSpeechController.pause()
    override fun next() = AppSpeechController.next()
    override fun previous() = AppSpeechController.previous()

    override fun stop() {
        pending?.source?.close()
        pending = null
        AppSpeechController.stop()
        context.stopService(Intent(context, SpeechPlaybackService::class.java))
    }

    override fun setSleepTimer(minutes: Int?) {
        scope.launch {
            activeSession?.setSleepTimer(
                minutes?.let { SystemClock.elapsedRealtime() + it * 60_000L },
            )
        }
    }

    fun seek(position: SpeechPosition) {
        scope.launch { activeSession?.seek(position) }
    }

    override fun applyPreferences() {
        scope.launch {
            val preferences = preferencesRepository.preferences.first()
            activeSession?.updateOptions(
                SpeechOptions(
                    mode = preferences.mode,
                    localeTag = preferences.localeTag,
                    voiceId = preferences.voiceId,
                    rate = preferences.rate,
                    pitch = preferences.pitch,
                ),
            )
        }
    }

    private data class PendingReader(
        val source: SpeechContentSource,
        val highlightSink: SpeechHighlightSink,
        val nowPlaying: SpeechNowPlaying,
        val initialPosition: SpeechPosition?,
    )
}

package com.air5005.pagenest.speech

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.cache.SpeechCacheScopeToken
import com.air5005.pagenest.speech.cloud.AzureResult
import com.air5005.pagenest.speech.cloud.AzureSpeechService
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.security.AzureCredentials
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import com.air5005.pagenest.speech.settings.SpeechPlaybackActions
import com.air5005.pagenest.speech.settings.SpeechPreferences
import com.air5005.pagenest.speech.settings.SpeechPreferencesRepository
import com.air5005.pagenest.speech.settings.SpeechSettingsViewModel
import com.air5005.pagenest.speech.settings.SpeechUiEvent
import com.air5005.pagenest.speech.ui.SpeechControlPolicy
import com.wxn.reader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SpeechConsentInstrumentedTest {
    private val dispatcher = StandardTestDispatcher()
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun firstAzureUseExplainsCurrentParagraphAndDeclineSendsNothing() = runTest(dispatcher) {
        val fixture = fixture()
        advanceUntilIdle()

        fixture.viewModel.start()

        assertEquals(SpeechUiEvent.RequestOnlineConsent, fixture.viewModel.events.first())
        assertEquals(
            "在线朗读会将当前段落文本发送给 Azure 生成语音，是否继续？",
            context.getString(R.string.speech_online_consent),
        )
        assertEquals(context.getString(R.string.speech_online_consent), SpeechControlPolicy.ONLINE_CONSENT)
        assertTrue(fixture.viewModel.state.value.onlineConsentPending)

        fixture.viewModel.cancelOnlineConsent()
        advanceUntilIdle()

        assertFalse(fixture.viewModel.state.value.onlineConsentPending)
        assertFalse(fixture.repository.value.onlineConsentGranted)
        assertEquals(0, fixture.actions.startCalls)
        assertEquals(0, fixture.azure.voiceCalls)
    }

    @Test
    fun acceptingFirstAzureUsePersistsConsentAndEnablesOneStart() = runTest(dispatcher) {
        val fixture = fixture()
        advanceUntilIdle()

        fixture.viewModel.start()
        assertEquals(SpeechUiEvent.RequestOnlineConsent, fixture.viewModel.events.first())
        fixture.viewModel.confirmOnlineConsent()
        advanceUntilIdle()

        assertTrue(fixture.repository.value.onlineConsentGranted)
        assertFalse(fixture.viewModel.state.value.onlineConsentPending)
        assertEquals(1, fixture.actions.startCalls)
        assertEquals(0, fixture.azure.voiceCalls)
    }

    private fun fixture(): Fixture {
        val repository = FakePreferencesRepository(SpeechPreferences(mode = SpeechMode.AUTO))
        val actions = FakeActions()
        val azure = FakeAzureService()
        return Fixture(
            viewModel = SpeechSettingsViewModel(
                repository,
                FakeCredentialStore(),
                FakeCache(),
                azure,
                actions,
            ),
            repository = repository,
            actions = actions,
            azure = azure,
        )
    }

    private data class Fixture(
        val viewModel: SpeechSettingsViewModel,
        val repository: FakePreferencesRepository,
        val actions: FakeActions,
        val azure: FakeAzureService,
    )

    private class FakePreferencesRepository(initial: SpeechPreferences) : SpeechPreferencesRepository {
        private val flow = MutableStateFlow(initial)
        val value get() = flow.value
        override val preferences: Flow<SpeechPreferences> = flow
        override suspend fun update(transform: (SpeechPreferences) -> SpeechPreferences) {
            flow.value = transform(flow.value)
        }
    }

    private class FakeActions : SpeechPlaybackActions {
        override val fallbackErrors = MutableSharedFlow<SpeechError>()
        var startCalls = 0
        override fun start() { startCalls++ }
        override fun pause() = Unit
        override fun stop() = Unit
        override fun next() = Unit
        override fun previous() = Unit
        override fun setSleepTimer(minutes: Int?) = Unit
    }

    private class FakeCredentialStore : SpeechCredentialStore {
        override suspend fun saveAzure(key: String, region: String) = Unit
        override suspend fun loadAzure(): AzureCredentials? = null
        override suspend fun clearAzure() = Unit
    }

    private class FakeAzureService : AzureSpeechService {
        var voiceCalls = 0
        override suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>> {
            voiceCalls++
            return AzureResult(value = emptyList())
        }

        override suspend fun synthesize(credentials: AzureCredentials, request: SpeechRequest) =
            AzureResult<ByteArray>()
    }

    private class FakeCache : SpeechAudioCache {
        override suspend fun retainScope(requestGeneration: Long, request: SpeechRequest): SpeechCacheScopeToken =
            throw UnsupportedOperationException()
        override suspend fun get(token: SpeechCacheScopeToken, request: SpeechRequest, nowMillis: Long) = null
        override suspend fun put(
            token: SpeechCacheScopeToken,
            request: SpeechRequest,
            audio: ByteArray,
            nowMillis: Long,
        ) = Unit
        override suspend fun remove(token: SpeechCacheScopeToken, request: SpeechRequest) = Unit
        override suspend fun clear() = Unit
    }
}

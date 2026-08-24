package com.air5005.pagenest.speech.settings

import com.air5005.pagenest.speech.cache.SpeechAudioCache
import com.air5005.pagenest.speech.cache.SpeechCacheScopeToken
import com.air5005.pagenest.speech.cloud.AzureResult
import com.air5005.pagenest.speech.cloud.AzureSpeechService
import com.air5005.pagenest.speech.engine.SpeechRequest
import com.air5005.pagenest.speech.engine.SpeechVoice
import com.air5005.pagenest.speech.model.SpeechMode
import com.air5005.pagenest.speech.model.SpeechError
import com.air5005.pagenest.speech.security.AzureCredentials
import com.air5005.pagenest.speech.security.SpeechCredentialStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechSettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `auto mode consent is required before first Azure request`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.viewModel.selectMode(SpeechMode.AUTO)
        advanceUntilIdle()

        fixture.viewModel.start()
        assertEquals(SpeechUiEvent.RequestOnlineConsent, fixture.viewModel.events.first())
        assertEquals(0, fixture.actions.startCalls)
    }

    @Test
    fun `confirming online consent persists it and starts prepared reader`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.viewModel.selectMode(SpeechMode.ONLINE)
        advanceUntilIdle()

        fixture.viewModel.confirmOnlineConsent()
        advanceUntilIdle()

        assertTrue(fixture.repository.value.onlineConsentGranted)
        assertEquals(1, fixture.actions.startCalls)
    }

    @Test
    fun `offline mode starts without online consent`() = runTest(dispatcher) {
        val fixture = fixture()
        fixture.viewModel.selectMode(SpeechMode.OFFLINE)
        advanceUntilIdle()

        fixture.viewModel.start()
        advanceUntilIdle()

        assertEquals(1, fixture.actions.startCalls)
    }

    @Test
    fun `changing mode cancels the old speech generation`() = runTest(dispatcher) {
        val fixture = fixture(SpeechPreferences(mode = SpeechMode.OFFLINE))

        fixture.viewModel.selectMode(SpeechMode.AUTO)
        advanceUntilIdle()

        assertEquals(1, fixture.actions.stopCalls)
        assertEquals(SpeechMode.AUTO, fixture.repository.value.mode)
    }

    @Test
    fun `playback controls and timer are forwarded exactly once`() = runTest(dispatcher) {
        val fixture = fixture(SpeechPreferences(mode = SpeechMode.OFFLINE))

        fixture.viewModel.pause()
        fixture.viewModel.previous()
        fixture.viewModel.next()
        fixture.viewModel.setSleepTimer(30)
        fixture.viewModel.stop()

        assertEquals(1, fixture.actions.pauseCalls)
        assertEquals(1, fixture.actions.previousCalls)
        assertEquals(1, fixture.actions.nextCalls)
        assertEquals(listOf(30), fixture.actions.timers)
        assertEquals(1, fixture.actions.stopCalls)
    }

    @Test
    fun `rate and pitch are clamped to supported bounds before persistence`() = runTest(dispatcher) {
        val fixture = fixture()

        fixture.viewModel.setRate(9f)
        fixture.viewModel.setPitch(0.01f)
        advanceUntilIdle()

        assertEquals(2f, fixture.repository.value.rate)
        assertEquals(0.25f, fixture.repository.value.pitch)
    }

    @Test
    fun `saved key is never echoed and overwrite keeps only replacement`() = runTest(dispatcher) {
        val fixture = fixture()

        fixture.viewModel.saveAzure("first-secret", "eastasia")
        advanceUntilIdle()
        fixture.viewModel.saveAzure("replacement-secret", "eastasia")
        advanceUntilIdle()

        assertEquals(AzureCredentials("replacement-secret", "eastasia"), fixture.credentials.value)
        assertTrue(fixture.viewModel.state.value.keyConfigured)
        assertEquals("eastasia", fixture.viewModel.state.value.region)
        assertEquals("", fixture.viewModel.state.value.keyDraft)
        assertFalse(fixture.viewModel.state.value.toString().contains("replacement-secret"))
    }

    @Test
    fun `invalid Region is rejected without storing key`() = runTest(dispatcher) {
        val fixture = fixture()

        fixture.viewModel.saveAzure("secret", "https://eastasia")
        advanceUntilIdle()

        assertNull(fixture.credentials.value)
        assertEquals(SpeechUiEvent.ShowMessage("Azure Region 无效或与 Key 不匹配"), fixture.viewModel.events.first())
    }

    @Test
    fun `deleting Azure credentials also clears speech cache`() = runTest(dispatcher) {
        val fixture = fixture(credentials = AzureCredentials("secret", "eastasia"))
        advanceUntilIdle()

        fixture.viewModel.deleteAzure()
        advanceUntilIdle()

        assertNull(fixture.credentials.value)
        assertEquals(1, fixture.cache.clearCalls)
        assertFalse(fixture.viewModel.state.value.keyConfigured)
    }

    @Test
    fun `connection test uses stored credentials and reports success`() = runTest(dispatcher) {
        val fixture = fixture(credentials = AzureCredentials("secret", "eastasia"))
        fixture.azure.result = AzureResult(value = listOf(SpeechVoice("zh", "晓晓", "zh-CN")))

        fixture.viewModel.testConnection()
        advanceUntilIdle()

        assertEquals(1, fixture.azure.voiceCalls)
        assertEquals(SpeechUiEvent.ShowMessage("Azure 连接成功"), fixture.viewModel.events.first())
    }

    @Test
    fun `auto fallback emits its exact classified error once without changing playback`() = runTest(dispatcher) {
        val fixture = fixture()
        val event = async { fixture.viewModel.events.first() }
        advanceUntilIdle()

        fixture.actions.fallbackErrors.emit(SpeechError.NetworkTimeout)
        advanceUntilIdle()

        assertEquals(
            SpeechUiEvent.ShowFallbackMessage("网络连接超时，请检查网络后重试"),
            event.await(),
        )
    }

    private fun fixture(
        preferences: SpeechPreferences = SpeechPreferences(),
        credentials: AzureCredentials? = null,
    ): Fixture {
        val repository = FakePreferencesRepository(preferences)
        val credentialStore = FakeCredentialStore(credentials)
        val cache = FakeCache()
        val azure = FakeAzureService()
        val actions = FakeActions()
        return Fixture(
            SpeechSettingsViewModel(repository, credentialStore, cache, azure, actions),
            repository,
            credentialStore,
            cache,
            azure,
            actions,
        )
    }

    private data class Fixture(
        val viewModel: SpeechSettingsViewModel,
        val repository: FakePreferencesRepository,
        val credentials: FakeCredentialStore,
        val cache: FakeCache,
        val azure: FakeAzureService,
        val actions: FakeActions,
    )

    private class FakePreferencesRepository(initial: SpeechPreferences) : SpeechPreferencesRepository {
        private val flow = MutableStateFlow(initial)
        val value get() = flow.value
        override val preferences: Flow<SpeechPreferences> = flow
        override suspend fun update(transform: (SpeechPreferences) -> SpeechPreferences) {
            flow.value = transform(flow.value)
        }
    }

    private class FakeCredentialStore(initial: AzureCredentials?) : SpeechCredentialStore {
        var value = initial
        override suspend fun saveAzure(key: String, region: String) { value = AzureCredentials(key, region) }
        override suspend fun loadAzure(): AzureCredentials? = value
        override suspend fun clearAzure() { value = null }
    }

    private class FakeActions : SpeechPlaybackActions {
        override val fallbackErrors = MutableSharedFlow<SpeechError>()
        var startCalls = 0
        var pauseCalls = 0
        var stopCalls = 0
        var nextCalls = 0
        var previousCalls = 0
        val timers = mutableListOf<Int?>()
        override fun start() { startCalls++ }
        override fun pause() { pauseCalls++ }
        override fun stop() { stopCalls++ }
        override fun next() { nextCalls++ }
        override fun previous() { previousCalls++ }
        override fun setSleepTimer(minutes: Int?) { timers += minutes }
    }

    private class FakeAzureService : AzureSpeechService {
        var voiceCalls = 0
        var result: AzureResult<List<SpeechVoice>> = AzureResult(value = emptyList())
        override suspend fun voices(credentials: AzureCredentials): AzureResult<List<SpeechVoice>> {
            voiceCalls++
            return result
        }
        override suspend fun synthesize(credentials: AzureCredentials, request: SpeechRequest) =
            AzureResult<ByteArray>()
    }

    private class FakeCache : SpeechAudioCache {
        var clearCalls = 0
        override suspend fun clear() { clearCalls++ }
        override suspend fun retainScope(requestGeneration: Long, request: SpeechRequest) =
            throw UnsupportedOperationException()
        override suspend fun get(token: SpeechCacheScopeToken, request: SpeechRequest, nowMillis: Long) = null
        override suspend fun put(token: SpeechCacheScopeToken, request: SpeechRequest, audio: ByteArray, nowMillis: Long) = Unit
        override suspend fun remove(token: SpeechCacheScopeToken, request: SpeechRequest) = Unit
    }
}

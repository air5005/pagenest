package com.air5005.pagenest.discovery.ui

import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.download.DownloadProgress
import com.air5005.pagenest.discovery.importing.OnlineImportCoordinator
import com.air5005.pagenest.discovery.importing.OnlineImportFailure
import com.air5005.pagenest.discovery.importing.OnlineImportProgress
import com.air5005.pagenest.discovery.importing.OnlineImportResult
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogPage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.SourceBookDetails
import com.air5005.pagenest.discovery.model.SourceReference
import com.air5005.pagenest.discovery.openlibrary.OnlineBookEnricher
import com.air5005.pagenest.discovery.openlibrary.OpenLibraryMetadata
import com.air5005.pagenest.discovery.repository.DiscoveryCatalogRepository
import com.air5005.pagenest.discovery.repository.DiscoveryResult
import com.air5005.pagenest.discovery.source.OnlineCatalogSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class DiscoveryViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `initial load publishes recommended sections and stale warning`() = runTest(dispatcher) {
        val repository = FakeRepository { request ->
            assertEquals(CatalogKind.RECOMMENDED, request.kind)
            DiscoveryResult(
                CatalogPage(listOf(discoveryBook("one", "One")), null, listOf("gutenberg-opds")),
                fromStaleCache = true,
                unavailableSourceIds = listOf("gutenberg-opds"),
            )
        }
        val viewModel = viewModel(repository)

        advanceUntilIdle()

        assertEquals(listOf("One"), viewModel.state.value.sections.ranking.map { it.title })
        assertTrue(viewModel.state.value.fromStaleCache)
        assertEquals(listOf("gutenberg-opds"), viewModel.state.value.unavailableSourceIds)
        assertFalse(viewModel.state.value.isLoading)
    }

    @Test
    fun `tab and language changes create typed requests`() = runTest(dispatcher) {
        val repository = FakeRepository { DiscoveryResult(CatalogPage(emptyList(), null), false, emptyList()) }
        val viewModel = viewModel(repository)
        advanceUntilIdle()

        viewModel.selectTab(DiscoveryTab.LATEST)
        viewModel.selectLanguage(CatalogLanguage.ZH)
        advanceUntilIdle()

        assertEquals(CatalogKind.LATEST, repository.requests.last().kind)
        assertEquals(CatalogLanguage.ZH, repository.requests.last().language)
        assertEquals(DiscoveryTab.LATEST, viewModel.state.value.selectedTab)
    }

    @Test
    fun `search waits for debounce and supersedes previous query`() = runTest(dispatcher) {
        val repository = FakeRepository { DiscoveryResult(CatalogPage(emptyList(), null), false, emptyList()) }
        val viewModel = viewModel(repository)
        advanceUntilIdle()
        val baseline = repository.requests.size

        viewModel.updateSearchQuery("pride")
        advanceTimeBy(200)
        viewModel.updateSearchQuery("pride prejudice")
        advanceTimeBy(349)
        assertEquals(baseline, repository.requests.size)
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(CatalogKind.SEARCH, repository.requests.last().kind)
        assertEquals("pride prejudice", repository.requests.last().query)
    }

    @Test
    fun `book selection enriches detail and close clears it`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val metadata = OpenLibraryMetadata("OL1W", null, 1900, 2, false,
            com.air5005.pagenest.discovery.openlibrary.OpenLibraryEbookAccess.NONE,
            "https://openlibrary.org/works/OL1W")
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            enricher = OnlineBookEnricher { metadata },
        )
        advanceUntilIdle()

        viewModel.selectBook(book)
        advanceUntilIdle()
        assertEquals(book, viewModel.state.value.selectedBook)
        assertEquals(metadata, viewModel.state.value.detailMetadata)

        viewModel.closeDetail()
        assertNull(viewModel.state.value.selectedBook)
        assertNull(viewModel.state.value.detailMetadata)
    }

    @Test
    fun `add to shelf exposes deterministic phases and remains on added detail`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val coordinator = FakeImportCoordinator { _, progress ->
            progress(OnlineImportProgress.Downloading(DownloadProgress(10, 100)))
            progress(OnlineImportProgress.Validating)
            progress(OnlineImportProgress.Importing)
            OnlineImportResult.Added(42L, duplicate = false)
        }
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            coordinator = coordinator,
        )
        advanceUntilIdle()
        viewModel.selectBook(book)
        advanceUntilIdle()
        val observed = mutableListOf<DiscoveryAcquisitionState>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.state.map { it.acquisition }.distinctUntilChanged().collect(observed::add)
        }

        viewModel.addToShelf()
        advanceUntilIdle()

        assertTrue(observed.contains(DiscoveryAcquisitionState.Downloading(10, 100)))
        assertTrue(observed.contains(DiscoveryAcquisitionState.Validating))
        assertTrue(observed.contains(DiscoveryAcquisitionState.Importing))
        assertEquals(DiscoveryAcquisitionState.Added(42L), viewModel.state.value.acquisition)
        assertEquals(book, viewModel.state.value.selectedBook)
        assertEquals(1, coordinator.calls)
        collector.cancel()
    }

    @Test
    fun `start reading emits local book id exactly once after import`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val coordinator = FakeImportCoordinator { _, _ -> OnlineImportResult.Added(51L, duplicate = true) }
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            coordinator = coordinator,
        )
        advanceUntilIdle()
        viewModel.selectBook(book)
        advanceUntilIdle()
        val events = mutableListOf<Long>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.readerBookIds.collect(events::add)
        }

        viewModel.startReading()
        advanceUntilIdle()

        assertEquals(listOf(51L), events)
        assertEquals(DiscoveryAcquisitionState.Added(51L), viewModel.state.value.acquisition)
        assertEquals(1, coordinator.calls)
        collector.cancel()
    }

    @Test
    fun `double taps do not start parallel imports and cancel returns idle`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = FakeImportCoordinator { _, progress ->
            progress(OnlineImportProgress.Downloading(DownloadProgress(1, null)))
            entered.complete(Unit)
            release.await()
            OnlineImportResult.Added(1L, duplicate = false)
        }
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            coordinator = coordinator,
        )
        advanceUntilIdle()
        viewModel.selectBook(book)
        advanceUntilIdle()

        viewModel.addToShelf()
        viewModel.addToShelf()
        entered.await()
        assertEquals(1, coordinator.calls)
        assertTrue(viewModel.state.value.acquisition is DiscoveryAcquisitionState.Downloading)

        viewModel.cancelAcquisition()
        advanceUntilIdle()
        assertEquals(DiscoveryAcquisitionState.Idle, viewModel.state.value.acquisition)
    }

    @Test
    fun `close detail cancels acquisition and stale completion cannot update new state`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val coordinator = FakeImportCoordinator { _, _ ->
            entered.complete(Unit)
            release.await()
            OnlineImportResult.Added(99L, duplicate = false)
        }
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            coordinator = coordinator,
        )
        advanceUntilIdle()
        viewModel.selectBook(book)
        advanceUntilIdle()
        viewModel.addToShelf()
        entered.await()

        viewModel.closeDetail()
        release.complete(Unit)
        advanceUntilIdle()

        assertNull(viewModel.state.value.selectedBook)
        assertEquals(DiscoveryAcquisitionState.Idle, viewModel.state.value.acquisition)
    }

    @Test
    fun `typed coordinator failure becomes typed acquisition error`() = runTest(dispatcher) {
        val book = discoveryBook("one", "One")
        val viewModel = viewModel(
            FakeRepository { DiscoveryResult(CatalogPage(listOf(book), null), false, emptyList()) },
            coordinator = FakeImportCoordinator { _, _ ->
                OnlineImportResult.Failed(OnlineImportFailure.RESPONSE_TOO_LARGE)
            },
        )
        advanceUntilIdle()
        viewModel.selectBook(book)
        advanceUntilIdle()

        viewModel.addToShelf()
        advanceUntilIdle()

        assertEquals(
            DiscoveryAcquisitionState.Error(OnlineImportFailure.RESPONSE_TOO_LARGE),
            viewModel.state.value.acquisition,
        )
    }

    private fun viewModel(
        repository: DiscoveryCatalogRepository,
        enricher: OnlineBookEnricher = OnlineBookEnricher { null },
        coordinator: OnlineImportCoordinator = FakeImportCoordinator { _, _ ->
            OnlineImportResult.Failed(OnlineImportFailure.NO_ELIGIBLE_ACQUISITION)
        },
    ) = DiscoveryViewModel(repository, enricher, registry(), coordinator)

    private fun registry(): DiscoverySourceRegistry = DiscoverySourceRegistry.create(
        source("gutendex"), source("gutenberg-opds"), { source("standard-ebooks") }, false,
    )

    private fun source(id: String) = object : OnlineCatalogSource {
        override val id = id
        override suspend fun browse(request: CatalogRequest) = CatalogPage(emptyList(), null)
        override suspend fun details(reference: SourceReference): SourceBookDetails? = null
    }

    private class FakeRepository(
        private val result: suspend (CatalogRequest) -> DiscoveryResult,
    ) : DiscoveryCatalogRepository {
        val requests = mutableListOf<CatalogRequest>()
        override suspend fun discover(request: CatalogRequest): DiscoveryResult {
            requests += request
            return result(request)
        }
    }

    private class FakeImportCoordinator(
        private val handler: suspend (OnlineBook, (OnlineImportProgress) -> Unit) -> OnlineImportResult,
    ) : OnlineImportCoordinator {
        var calls = 0
        override suspend fun import(
            book: OnlineBook,
            onProgress: (OnlineImportProgress) -> Unit,
        ): OnlineImportResult {
            calls++
            return handler(book, onProgress)
        }
    }
}

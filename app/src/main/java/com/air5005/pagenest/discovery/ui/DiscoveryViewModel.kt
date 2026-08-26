package com.air5005.pagenest.discovery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.importing.OnlineImportCoordinator
import com.air5005.pagenest.discovery.importing.OnlineImportProgress
import com.air5005.pagenest.discovery.importing.OnlineImportResult
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.openlibrary.OnlineBookEnricher
import com.air5005.pagenest.discovery.repository.DiscoveryCatalogRepository
import com.wxn.base.util.Logger
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val repository: DiscoveryCatalogRepository,
    private val enricher: OnlineBookEnricher,
    registry: DiscoverySourceRegistry,
    private val importCoordinator: OnlineImportCoordinator,
) : ViewModel() {
    private val _state = MutableStateFlow(
        DiscoveryUiState(sourceStatuses = registry.statuses),
    )
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()
    private val readerBookIdChannel = Channel<Long>(Channel.BUFFERED)
    val readerBookIds = readerBookIdChannel.receiveAsFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null
    private var acquisitionJob: Job? = null

    init {
        load(showInitialLoading = true)
    }

    fun selectTab(tab: DiscoveryTab) {
        searchJob?.cancel()
        _state.update { it.copy(selectedTab = tab, searchQuery = "") }
        if (tab != DiscoveryTab.SOURCES) load(showInitialLoading = false)
    }

    fun selectLanguage(language: CatalogLanguage) {
        if (_state.value.selectedLanguage == language) return
        _state.update { it.copy(selectedLanguage = language) }
        if (_state.value.selectedTab != DiscoveryTab.SOURCES) load(showInitialLoading = false)
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        searchJob?.cancel()
        if (query.isBlank()) return
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MILLIS)
            load(showInitialLoading = false, searchQuery = query)
        }
    }

    fun submitSearch() {
        val query = _state.value.searchQuery.trim()
        if (query.isEmpty()) return
        Logger.running("DISCOVERY", "Search submitted queryLength=${query.length}")
        searchJob?.cancel()
        searchJob = null
        load(showInitialLoading = false, searchQuery = query)
    }

    fun retry() = load(showInitialLoading = _state.value.sections.ranking.isEmpty())

    fun selectBook(book: OnlineBook) {
        detailJob?.cancel()
        acquisitionJob?.cancel()
        acquisitionJob = null
        _state.update {
            it.copy(
                selectedBook = book,
                isDetailLoading = true,
                detailMetadata = null,
                acquisition = DiscoveryAcquisitionState.Idle,
            )
        }
        detailJob = viewModelScope.launch {
            val metadata = try {
                enricher.enrich(book)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                null
            }
            _state.update { current ->
                if (current.selectedBook?.stableKey != book.stableKey) current
                else current.copy(isDetailLoading = false, detailMetadata = metadata)
            }
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        acquisitionJob?.cancel()
        acquisitionJob = null
        _state.update {
            it.copy(
                selectedBook = null,
                isDetailLoading = false,
                detailMetadata = null,
                acquisition = DiscoveryAcquisitionState.Idle,
            )
        }
    }

    fun addToShelf() = startAcquisition(openAfterImport = false)

    fun startReading() {
        val added = _state.value.acquisition as? DiscoveryAcquisitionState.Added
        if (added != null) {
            readerBookIdChannel.trySend(added.bookId)
            return
        }
        startAcquisition(openAfterImport = true)
    }

    fun cancelAcquisition() {
        acquisitionJob?.cancel()
        acquisitionJob = null
        _state.update { it.copy(acquisition = DiscoveryAcquisitionState.Idle) }
    }

    private fun startAcquisition(openAfterImport: Boolean) {
        val book = _state.value.selectedBook ?: return
        if (acquisitionJob?.isActive == true) return
        val stableKey = book.stableKey
        _state.update {
            if (it.selectedBook?.stableKey != stableKey) it
            else it.copy(acquisition = DiscoveryAcquisitionState.Downloading(0L, null))
        }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                when (val result = importCoordinator.import(book) { progress ->
                    updateAcquisitionProgress(stableKey, progress)
                }) {
                    is OnlineImportResult.Added -> {
                        val stillSelected = _state.value.selectedBook?.stableKey == stableKey
                        if (stillSelected) {
                            _state.update {
                                it.copy(acquisition = DiscoveryAcquisitionState.Added(result.bookId))
                            }
                            if (openAfterImport) readerBookIdChannel.send(result.bookId)
                        }
                    }
                    is OnlineImportResult.Failed -> _state.update {
                        if (it.selectedBook?.stableKey != stableKey) it
                        else it.copy(acquisition = DiscoveryAcquisitionState.Error(result.reason))
                    }
                }
            } catch (cancelled: CancellationException) {
                _state.update {
                    if (it.selectedBook?.stableKey != stableKey) it
                    else it.copy(acquisition = DiscoveryAcquisitionState.Idle)
                }
                throw cancelled
            }
        }
        acquisitionJob = job
        job.start()
    }

    private fun updateAcquisitionProgress(stableKey: String, progress: OnlineImportProgress) {
        _state.update { current ->
            if (current.selectedBook?.stableKey != stableKey) return@update current
            current.copy(
                acquisition = when (progress) {
                    is OnlineImportProgress.Downloading -> DiscoveryAcquisitionState.Downloading(
                        progress.progress.bytesRead,
                        progress.progress.totalBytes,
                    )
                    OnlineImportProgress.Validating -> DiscoveryAcquisitionState.Validating
                    OnlineImportProgress.Importing -> DiscoveryAcquisitionState.Importing
                },
            )
        }
    }

    private fun load(showInitialLoading: Boolean, searchQuery: String? = null) {
        loadJob?.cancel()
        val snapshot = _state.value
        val request = CatalogRequest(
            kind = searchQuery?.let { CatalogKind.SEARCH } ?: snapshot.selectedTab.catalogKind(),
            language = snapshot.selectedLanguage,
            query = searchQuery,
        )
        loadJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = showInitialLoading,
                    isRefreshing = !showInitialLoading,
                    hasLoadError = false,
                )
            }
            try {
                val result = repository.discover(request)
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        sections = DiscoverySections.from(result.page.books),
                        fromStaleCache = result.fromStaleCache,
                        unavailableSourceIds = result.unavailableSourceIds,
                    )
                }
                Logger.running(
                    "DISCOVERY",
                    "Catalog loaded kind=${request.kind.name} books=${result.page.books.size} stale=${result.fromStaleCache}",
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, hasLoadError = true)
                }
                Logger.warning("DISCOVERY", "Catalog load failed type=${failure.javaClass.simpleName}")
            }
        }
    }

    private fun DiscoveryTab.catalogKind(): CatalogKind = when (this) {
        DiscoveryTab.RECOMMENDED -> CatalogKind.RECOMMENDED
        DiscoveryTab.POPULAR -> CatalogKind.POPULAR
        DiscoveryTab.LATEST -> CatalogKind.LATEST
        DiscoveryTab.SOURCES -> CatalogKind.RECOMMENDED
    }

    companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L
    }
}

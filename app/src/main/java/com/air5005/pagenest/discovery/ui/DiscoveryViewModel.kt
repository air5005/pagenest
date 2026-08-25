package com.air5005.pagenest.discovery.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.air5005.pagenest.discovery.config.DiscoverySourceRegistry
import com.air5005.pagenest.discovery.model.CatalogKind
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.CatalogRequest
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.openlibrary.OnlineBookEnricher
import com.air5005.pagenest.discovery.repository.DiscoveryCatalogRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class DiscoveryViewModel @Inject constructor(
    private val repository: DiscoveryCatalogRepository,
    private val enricher: OnlineBookEnricher,
    registry: DiscoverySourceRegistry,
) : ViewModel() {
    private val _state = MutableStateFlow(
        DiscoveryUiState(sourceStatuses = registry.statuses),
    )
    val state: StateFlow<DiscoveryUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var detailJob: Job? = null

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

    fun retry() = load(showInitialLoading = _state.value.sections.ranking.isEmpty())

    fun selectBook(book: OnlineBook) {
        detailJob?.cancel()
        _state.update {
            it.copy(selectedBook = book, isDetailLoading = true, detailMetadata = null)
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
        _state.update {
            it.copy(selectedBook = null, isDetailLoading = false, detailMetadata = null)
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
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, hasLoadError = true)
                }
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

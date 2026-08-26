package com.air5005.pagenest.discovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.air5005.pagenest.discovery.config.SourceDisabledReason
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.OnlineBook
import com.wxn.reader.R
import kotlinx.coroutines.flow.Flow

@Composable
fun DiscoveryRoute(
    modifier: Modifier = Modifier,
    viewModel: DiscoveryViewModel = hiltViewModel(),
    onBookReady: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DiscoveryReaderEventEffect(viewModel.readerBookIds, onBookReady)
    val selected = state.selectedBook
    if (selected != null) {
        OnlineBookDetailScreen(
            book = selected,
            modifier = modifier,
            isLoading = state.isDetailLoading,
            metadata = state.detailMetadata,
            acquisition = state.acquisition,
            onBack = viewModel::closeDetail,
            onAddToShelf = viewModel::addToShelf,
            onStartReading = viewModel::startReading,
            onCancelAcquisition = viewModel::cancelAcquisition,
        )
    } else {
        DiscoveryScreen(
            state = state,
            modifier = modifier,
            onTabSelected = viewModel::selectTab,
            onLanguageSelected = viewModel::selectLanguage,
            onQueryChanged = viewModel::updateSearchQuery,
            onSearch = viewModel::submitSearch,
            onRetry = viewModel::retry,
            onBookSelected = viewModel::selectBook,
        )
    }
}

@Composable
fun DiscoveryReaderEventEffect(
    events: Flow<Long>,
    onBookReady: (Long) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onBookReady)
    LaunchedEffect(events) {
        events.collect(currentCallback)
    }
}

@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
    onTabSelected: (DiscoveryTab) -> Unit = {},
    onLanguageSelected: (CatalogLanguage) -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
    onSearch: () -> Unit = {},
    onRetry: () -> Unit = {},
    onBookSelected: (OnlineBook) -> Unit = {},
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item {
            DiscoveryHeader(
                state = state,
                onTabSelected = onTabSelected,
                onLanguageSelected = onLanguageSelected,
                onQueryChanged = onQueryChanged,
                onSearch = onSearch,
            )
        }
        if (state.fromStaleCache) {
            item { StatusBanner(stringResource(R.string.discovery_stale_cache)) }
        }
        if (state.unavailableSourceIds.isNotEmpty()) {
            item { StatusBanner(stringResource(R.string.discovery_partial_sources)) }
        }
        when {
            state.selectedTab == DiscoveryTab.SOURCES -> item { SourceStatusContent(state) }
            state.isLoading -> item {
                Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.sections.ranking.isEmpty() -> item {
                EmptyContent(hasError = state.hasLoadError, onRetry = onRetry)
            }
            else -> {
                item { DiscoveryBanner() }
                item { SectionTitle(
                    stringResource(R.string.discovery_curated),
                    stringResource(R.string.discovery_curated_subtitle),
                ) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(state.sections.curated, key = { it.stableKey }) { book ->
                            CuratedBookCard(book) { onBookSelected(book) }
                        }
                    }
                }
                item { SectionTitle(
                    stringResource(R.string.discovery_ranking),
                    stringResource(R.string.discovery_ranking_subtitle),
                ) }
                items(state.sections.ranking, key = { it.stableKey }) { book ->
                    Box(Modifier.padding(horizontal = 20.dp)) {
                        RankingBookRow(state.sections.ranking.indexOf(book) + 1, book) {
                            onBookSelected(book)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoveryHeader(
    state: DiscoveryUiState,
    onTabSelected: (DiscoveryTab) -> Unit,
    onLanguageSelected: (CatalogLanguage) -> Unit,
    onQueryChanged: (String) -> Unit,
    onSearch: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiscoveryGradient)
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text(stringResource(R.string.discovery_title), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = Color.White)
        Text(stringResource(R.string.discovery_subtitle), color = Color.White.copy(alpha = .78f))
        Spacer(Modifier.height(18.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.discovery_search_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    onSearch()
                }),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    focusManager.clearFocus()
                    onSearch()
                },
                enabled = state.searchQuery.isNotBlank(),
            ) {
                Text(stringResource(R.string.discovery_search))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DiscoveryTab.entries.forEach { tab ->
                FilterChip(
                    selected = state.selectedTab == tab,
                    onClick = { onTabSelected(tab) },
                    label = { Text(tab.label()) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CatalogLanguage.entries.forEach { language ->
                AssistChip(
                    onClick = { onLanguageSelected(language) },
                    label = { Text(language.label()) },
                    leadingIcon = if (state.selectedLanguage == language) {
                        { Icon(Icons.Rounded.Language, contentDescription = null) }
                    } else null,
                )
            }
        }
    }
}

@Composable
private fun DiscoveryBanner() {
    Surface(
        modifier = Modifier.padding(20.dp).fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null,
                tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.discovery_inspiration), fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.discovery_inspiration_body),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, top = 18.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusBanner(text: String) {
    Text(
        text,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun EmptyContent(hasError: Boolean, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(if (hasError) Icons.Rounded.NewReleases else Icons.Rounded.LocalFireDepartment,
            contentDescription = null)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.discovery_empty), style = MaterialTheme.typography.titleMedium)
        if (hasError) TextButton(onClick = onRetry) {
            Text(stringResource(R.string.discovery_retry))
        }
    }
}

@Composable
private fun SourceStatusContent(state: DiscoveryUiState) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.discovery_online_sources),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        state.sourceStatuses.forEach { source ->
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.id, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (source.enabled) stringResource(R.string.discovery_source_available)
                    else when (source.reason) {
                        SourceDisabledReason.AUTHORIZATION_REQUIRED ->
                            stringResource(R.string.discovery_source_authorization_required)
                        null -> stringResource(R.string.discovery_source_unavailable)
                    })
                }
            }
        }
    }
}

@Composable
private fun DiscoveryTab.label() = when (this) {
    DiscoveryTab.RECOMMENDED -> stringResource(R.string.discovery_tab_recommended)
    DiscoveryTab.POPULAR -> stringResource(R.string.discovery_tab_popular)
    DiscoveryTab.LATEST -> stringResource(R.string.discovery_tab_latest)
    DiscoveryTab.SOURCES -> stringResource(R.string.discovery_tab_sources)
}

@Composable
private fun CatalogLanguage.label() = when (this) {
    CatalogLanguage.ALL -> stringResource(R.string.discovery_language_all)
    CatalogLanguage.ZH -> stringResource(R.string.discovery_language_chinese)
    CatalogLanguage.EN -> stringResource(R.string.discovery_language_english)
}

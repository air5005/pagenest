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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.air5005.pagenest.discovery.config.SourceDisabledReason
import com.air5005.pagenest.discovery.model.CatalogLanguage
import com.air5005.pagenest.discovery.model.OnlineBook

@Composable
fun DiscoveryRoute(viewModel: DiscoveryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val selected = state.selectedBook
    if (selected != null) {
        OnlineBookDetailScreen(
            book = selected,
            isLoading = state.isDetailLoading,
            metadata = state.detailMetadata,
            onBack = viewModel::closeDetail,
        )
    } else {
        DiscoveryScreen(
            state = state,
            onTabSelected = viewModel::selectTab,
            onLanguageSelected = viewModel::selectLanguage,
            onQueryChanged = viewModel::updateSearchQuery,
            onRetry = viewModel::retry,
            onBookSelected = viewModel::selectBook,
        )
    }
}

@Composable
fun DiscoveryScreen(
    state: DiscoveryUiState,
    modifier: Modifier = Modifier,
    onTabSelected: (DiscoveryTab) -> Unit = {},
    onLanguageSelected: (CatalogLanguage) -> Unit = {},
    onQueryChanged: (String) -> Unit = {},
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
            )
        }
        if (state.fromStaleCache) {
            item { StatusBanner("当前显示离线缓存") }
        }
        if (state.unavailableSourceIds.isNotEmpty()) {
            item { StatusBanner("部分在线来源暂时不可用") }
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
                item { SectionTitle("编辑推荐", "适合现在开始阅读") }
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
                item { SectionTitle("热门榜单", "来自可信开放书库") }
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
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(DiscoveryGradient)
            .padding(start = 20.dp, top = 28.dp, end = 20.dp, bottom = 20.dp),
    ) {
        Text("在线发现", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold, color = Color.White)
        Text("发现值得读的开放好书", color = Color.White.copy(alpha = .78f))
        Spacer(Modifier.height(18.dp))
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = onQueryChanged,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索书名或作者") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "搜索") },
            singleLine = true,
            shape = RoundedCornerShape(18.dp),
        )
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
                Text("今日灵感", fontWeight = FontWeight.Bold)
                Text("从公共领域经典开始，让阅读自然发生",
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
        Text("暂时没有找到书籍", style = MaterialTheme.typography.titleMedium)
        if (hasError) TextButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun SourceStatusContent(state: DiscoveryUiState) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("在线来源", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        state.sourceStatuses.forEach { source ->
            Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 1.dp) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(source.id, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    Text(if (source.enabled) "可用" else when (source.reason) {
                        SourceDisabledReason.AUTHORIZATION_REQUIRED -> "需要授权"
                        null -> "不可用"
                    })
                }
            }
        }
    }
}

private fun DiscoveryTab.label() = when (this) {
    DiscoveryTab.RECOMMENDED -> "推荐"
    DiscoveryTab.POPULAR -> "热门"
    DiscoveryTab.LATEST -> "最新"
    DiscoveryTab.SOURCES -> "来源"
}

private fun CatalogLanguage.label() = when (this) {
    CatalogLanguage.ALL -> "全部"
    CatalogLanguage.ZH -> "中文"
    CatalogLanguage.EN -> "English"
}

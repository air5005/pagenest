package com.air5005.pagenest.discovery.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.air5005.pagenest.discovery.model.OnlineBook
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.openlibrary.OpenLibraryMetadata
import com.wxn.reader.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineBookDetailScreen(
    book: OnlineBook,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    metadata: OpenLibraryMetadata? = null,
    onBack: () -> Unit = {},
    onOpenSource: ((String) -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val sourcePage = book.sourceReferences.firstNotNullOfOrNull(OnlineSourceLinkPolicy::sourcePage)
        ?: metadata?.workId?.let(OnlineSourceLinkPolicy::openLibraryWorkPage)
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discovery_detail_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(R.string.discovery_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            DiscoveryCover(
                book = book,
                coverUrl = metadata?.coverUrl ?: book.coverUrl,
                modifier = Modifier.size(width = 144.dp, height = 196.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(book.title, style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold)
            Text(book.authors.joinToString(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            ) {
                AssistChip(onClick = {}, label = { Text(book.rightsStatus.label()) })
                book.acquisitions.map { it.format }.distinct().forEach { format ->
                    AssistChip(onClick = {}, label = { Text(format.name) })
                }
            }
            if (isLoading) {
                Spacer(Modifier.height(18.dp))
                CircularProgressIndicator()
            }
            metadata?.firstPublishYear?.let {
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.discovery_first_published, it))
            }
            book.summary?.takeIf(String::isNotBlank)?.let {
                Spacer(Modifier.height(22.dp))
                Text(it, Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.discovery_phase_three_notice),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            Button(
                enabled = sourcePage != null,
                onClick = {
                    sourcePage?.let { page -> onOpenSource?.invoke(page) ?: uriHandler.openUri(page) }
                },
            ) {
                Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null)
                Text(stringResource(R.string.discovery_view_source), Modifier.padding(start = 8.dp))
            }
        }
    }
}

@Composable
private fun RightsStatus.label() = when (this) {
    RightsStatus.PUBLIC_DOMAIN -> stringResource(R.string.discovery_rights_public_domain)
    RightsStatus.FREE_FULL -> stringResource(R.string.discovery_rights_free_full)
    RightsStatus.PREVIEW_ONLY -> stringResource(R.string.discovery_rights_preview)
    RightsStatus.BORROW_ONLY -> stringResource(R.string.discovery_rights_borrow)
    RightsStatus.UNKNOWN -> stringResource(R.string.discovery_rights_unknown)
}

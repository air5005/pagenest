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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.air5005.pagenest.discovery.model.OnlineBookFormat
import com.air5005.pagenest.discovery.model.RightsStatus
import com.air5005.pagenest.discovery.importing.OnlineImportFailure
import com.air5005.pagenest.discovery.openlibrary.OpenLibraryMetadata
import com.wxn.reader.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineBookDetailScreen(
    book: OnlineBook,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    metadata: OpenLibraryMetadata? = null,
    acquisition: DiscoveryAcquisitionState = DiscoveryAcquisitionState.Idle,
    onBack: () -> Unit = {},
    onOpenSource: ((String) -> Unit)? = null,
    onAddToShelf: () -> Unit = {},
    onStartReading: () -> Unit = {},
    onCancelAcquisition: () -> Unit = {},
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
            if (book.canImport()) {
                Spacer(Modifier.height(24.dp))
                OnlineAcquisitionControls(
                    acquisition = acquisition,
                    onAddToShelf = onAddToShelf,
                    onStartReading = onStartReading,
                    onCancel = onCancelAcquisition,
                )
            }
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
fun OnlineAcquisitionControls(
    acquisition: DiscoveryAcquisitionState,
    onAddToShelf: () -> Unit,
    onStartReading: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (acquisition) {
            DiscoveryAcquisitionState.Idle -> ImportActions(onAddToShelf, onStartReading)
            is DiscoveryAcquisitionState.Downloading -> {
                val total = acquisition.totalBytes
                if (total != null && total > 0L) {
                    val fraction = (acquisition.bytesRead.toFloat() / total).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.discovery_downloading_percent, (fraction * 100).toInt()))
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.discovery_downloading))
                }
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discovery_cancel_acquisition))
                }
            }
            DiscoveryAcquisitionState.Validating,
            DiscoveryAcquisitionState.Importing,
            -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(if (acquisition == DiscoveryAcquisitionState.Validating) {
                    R.string.discovery_validating
                } else {
                    R.string.discovery_importing
                }))
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discovery_cancel_acquisition))
                }
            }
            is DiscoveryAcquisitionState.Added -> {
                Text(
                    stringResource(R.string.discovery_added_to_shelf),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                Button(onClick = onStartReading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.discovery_start_reading))
                }
            }
            is DiscoveryAcquisitionState.Error -> {
                Text(
                    stringResource(acquisition.reason.messageResource()),
                    color = MaterialTheme.colorScheme.error,
                )
                ImportActions(onAddToShelf, onStartReading)
            }
        }
    }
}

@Composable
private fun ImportActions(onAddToShelf: () -> Unit, onStartReading: () -> Unit) {
    OutlinedButton(onClick = onAddToShelf, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.discovery_add_to_shelf))
    }
    Button(onClick = onStartReading, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.discovery_start_reading))
    }
}

private fun OnlineBook.canImport(): Boolean =
    rightsStatus in setOf(RightsStatus.PUBLIC_DOMAIN, RightsStatus.FREE_FULL) &&
        acquisitions.any {
            it.canReadDirectly && it.format in setOf(
                OnlineBookFormat.EPUB,
                OnlineBookFormat.TXT,
                OnlineBookFormat.PDF,
            )
        }

private fun OnlineImportFailure.messageResource(): Int = when (this) {
    OnlineImportFailure.NO_ELIGIBLE_ACQUISITION,
    OnlineImportFailure.NOT_FOUND,
    OnlineImportFailure.HTTP,
    -> R.string.discovery_error_unavailable
    OnlineImportFailure.UNSAFE_URL,
    OnlineImportFailure.REDIRECT_LIMIT,
    OnlineImportFailure.FORMAT_MISMATCH,
    -> R.string.discovery_error_unsafe
    OnlineImportFailure.UNAUTHORIZED,
    OnlineImportFailure.PROTECTED,
    -> R.string.discovery_error_unauthorized
    OnlineImportFailure.RESPONSE_TOO_LARGE -> R.string.discovery_error_too_large
    OnlineImportFailure.NETWORK -> R.string.discovery_error_network
    OnlineImportFailure.UNSUPPORTED_FORMAT -> R.string.discovery_error_unsupported
    OnlineImportFailure.UNREADABLE -> R.string.discovery_error_unreadable
    OnlineImportFailure.PARSE_FAILED -> R.string.discovery_error_parse
    OnlineImportFailure.STORAGE_FAILED -> R.string.discovery_error_storage
    OnlineImportFailure.IMPORT_FAILED -> R.string.discovery_error_import
}

@Composable
private fun RightsStatus.label() = when (this) {
    RightsStatus.PUBLIC_DOMAIN -> stringResource(R.string.discovery_rights_public_domain)
    RightsStatus.FREE_FULL -> stringResource(R.string.discovery_rights_free_full)
    RightsStatus.PREVIEW_ONLY -> stringResource(R.string.discovery_rights_preview)
    RightsStatus.BORROW_ONLY -> stringResource(R.string.discovery_rights_borrow)
    RightsStatus.UNKNOWN -> stringResource(R.string.discovery_rights_unknown)
}

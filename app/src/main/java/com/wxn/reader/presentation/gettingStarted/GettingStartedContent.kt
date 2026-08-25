package com.wxn.reader.presentation.gettingStarted

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.wxn.reader.R
import com.wxn.reader.presentation.gettingStarted.components.ActionButton
import com.wxn.reader.ui.theme.PageNestSpacing

@Composable
fun GettingStartedContent(
    buttonsEnabled: Boolean,
    onSelectDirectory: () -> Unit,
    onSkip: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag("pagenest_onboarding"),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = PageNestSpacing.ScreenHorizontal),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Image(
                painter = painterResource(R.drawable.ic_pagenest_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(112.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.welcome_to_uread),
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(
                    R.string.to_get_started_please_select_a_directory_where_you_would_like_to_load_your_books,
                ),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.you_can_edit_this_later),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            ActionButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PageNestSpacing.MinimumTouchTarget)
                    .testTag("pagenest_select_directory"),
                text = stringResource(R.string.select_directory),
                icon = Icons.Filled.FolderOpen,
                enabled = buttonsEnabled,
                onClick = onSelectDirectory,
                description = stringResource(R.string.select_directory),
            )
        }

        TextButton(
            onClick = onSkip,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .heightIn(min = PageNestSpacing.MinimumTouchTarget)
                .padding(bottom = 8.dp)
                .testTag("pagenest_skip"),
        ) {
            Text(
                text = stringResource(R.string.skip),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

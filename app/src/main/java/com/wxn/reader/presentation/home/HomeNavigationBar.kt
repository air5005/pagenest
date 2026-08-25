package com.wxn.reader.presentation.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.wxn.reader.R

@Composable
fun HomeNavigationBar(
    currentDestination: HomeTopLevelDestination,
    onDestinationSelected: (HomeTopLevelDestination) -> Unit,
) {
    NavigationBar {
        HomeTopLevelDestination.entries.forEach { destination ->
            val label = when (destination) {
                HomeTopLevelDestination.SHELF -> stringResource(R.string.ebooks)
                HomeTopLevelDestination.DISCOVERY -> stringResource(R.string.discovery)
                HomeTopLevelDestination.AUDIO -> stringResource(R.string.audio_books)
                HomeTopLevelDestination.MINE -> stringResource(R.string.mine)
            }
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = when (destination) {
                            HomeTopLevelDestination.SHELF -> Icons.AutoMirrored.Rounded.MenuBook
                            HomeTopLevelDestination.DISCOVERY -> Icons.Default.Explore
                            HomeTopLevelDestination.AUDIO -> Icons.Default.Headset
                            HomeTopLevelDestination.MINE -> Icons.Default.Person
                        },
                        contentDescription = label,
                    )
                },
                label = { Text(label, textAlign = TextAlign.Center) },
                selected = currentDestination == destination,
                onClick = { onDestinationSelected(destination) },
            )
        }
    }
}

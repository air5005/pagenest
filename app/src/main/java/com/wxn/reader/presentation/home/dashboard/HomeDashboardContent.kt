package com.wxn.reader.presentation.home.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.wxn.reader.R
import com.wxn.reader.ui.components.PageNestGradientCard
import com.wxn.reader.ui.theme.PageNestShapes
import com.wxn.reader.ui.theme.PageNestSpacing
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeDashboardContent(
    model: HomeDashboardModel,
    expanded: Boolean,
    onRecentBookClick: (Long) -> Unit,
    onImportClick: () -> Unit,
    onAllBooksClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dateLabel = remember {
        LocalDate.now().format(
            DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.SIMPLIFIED_CHINESE),
        )
    }
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("home_dashboard"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = PageNestSpacing.ScreenHorizontal,
            vertical = 20.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(PageNestSpacing.CardGap),
    ) {
        item {
            Text(
                text = stringResource(R.string.dashboard_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = dateLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            ReadingSummary(model)
        }
        if (model.recentBooks.isNotEmpty()) {
            item {
                SectionTitle(stringResource(R.string.dashboard_recent_reading))
            }
            items(model.recentBooks, key = { it.id }) { book ->
                RecentBookCard(book = book, onClick = { onRecentBookClick(book.id) })
            }
        } else if (model.totalBookCount == 0) {
            item {
                EmptyLibraryCard(onImportClick)
            }
        } else {
            item {
                NoRecentReadingCard(onAllBooksClick)
            }
        }
        item {
            DashboardActions(
                model = model,
                expanded = expanded,
                onImportClick = onImportClick,
                onAllBooksClick = onAllBooksClick,
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun ReadingSummary(model: HomeDashboardModel) {
    PageNestGradientCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("reading_summary"),
    ) {
        Text(
            text = stringResource(R.string.dashboard_reading_summary),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White.copy(alpha = 0.9f),
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SummaryMetric(
                value = stringResource(R.string.dashboard_minutes_format, model.todayMinutes),
                label = stringResource(R.string.dashboard_today_reading),
            )
            SummaryMetric(
                value = stringResource(R.string.dashboard_days_format, model.streakDays),
                label = stringResource(R.string.dashboard_reading_streak),
                alignment = Alignment.End,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(
                R.string.dashboard_week_progress,
                model.weekMinutes,
                model.weeklyGoalMinutes,
            ),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { model.weekProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(7.dp)
                .clip(PageNestShapes.SmallControl),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
        )
    }
}

@Composable
private fun SummaryMetric(
    value: String,
    label: String,
    alignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(horizontalAlignment = alignment) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color.White.copy(alpha = 0.78f),
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RecentBookCard(book: RecentBookModel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_book_${book.id}"),
        shape = PageNestShapes.MediumCard,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BookCover(book)
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        text = book.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { book.progressPercent / 100f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(PageNestShapes.SmallControl),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(
                        R.string.dashboard_last_reading,
                        book.locationLabel,
                        formatLastOpened(book.lastOpenedEpochMillis),
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = stringResource(R.string.dashboard_continue_reading),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun BookCover(book: RecentBookModel) {
    if (book.coverImage.isNullOrBlank()) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 72.dp)
                .clip(PageNestShapes.SmallControl)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        AsyncImage(
            model = book.coverImage,
            contentDescription = stringResource(R.string.dashboard_book_cover, book.title),
            modifier = Modifier
                .size(width = 52.dp, height = 72.dp)
                .clip(PageNestShapes.SmallControl),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun EmptyLibraryCard(onImportClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("empty_library"),
        shape = PageNestShapes.LargeCard,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.MenuBook,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.dashboard_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.dashboard_empty_description),
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onImportClick,
                modifier = Modifier
                    .heightIn(min = PageNestSpacing.MinimumTouchTarget)
                    .testTag("import_books"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.dashboard_import_books))
            }
        }
    }
}

@Composable
private fun NoRecentReadingCard(onAllBooksClick: () -> Unit) {
    Card(
        onClick = onAllBooksClick,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("recent_reading"),
        shape = PageNestShapes.MediumCard,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                text = stringResource(R.string.dashboard_no_recent_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.dashboard_no_recent_description),
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DashboardActions(
    model: HomeDashboardModel,
    expanded: Boolean,
    onImportClick: () -> Unit,
    onAllBooksClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedButton(
            onClick = onImportClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PageNestSpacing.MinimumTouchTarget),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text(stringResource(R.string.dashboard_import_short))
        }
        Button(
            onClick = onAllBooksClick,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = PageNestSpacing.MinimumTouchTarget)
                .testTag("all_books"),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
            ),
        ) {
            Text(
                stringResource(
                    if (expanded) R.string.dashboard_back_to_summary else R.string.dashboard_all_books,
                    model.totalBookCount,
                ),
                maxLines = 1,
            )
        }
    }
}

private fun formatLastOpened(epochMillis: Long): String =
    Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("M月d日"))

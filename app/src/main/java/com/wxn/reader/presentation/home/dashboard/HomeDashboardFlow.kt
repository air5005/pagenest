package com.wxn.reader.presentation.home.dashboard

import com.wxn.base.bean.Book
import com.wxn.reader.domain.model.ReadingActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import java.time.Clock
import java.time.ZoneId

fun observeHomeDashboard(
    books: Flow<List<Book>>,
    activities: Flow<List<ReadingActive>>,
    calculator: HomeDashboardCalculator,
    clock: Clock,
    zoneId: ZoneId,
): Flow<HomeDashboardModel> {
    val recoverableActivities = activities.catch { failure ->
        if (failure is CancellationException) throw failure
        emit(emptyList())
    }
    return combine(books, recoverableActivities) { currentBooks, currentActivities ->
        calculator.calculate(
            books = currentBooks,
            activities = currentActivities,
            now = clock.instant(),
            zoneId = zoneId,
        )
    }
}

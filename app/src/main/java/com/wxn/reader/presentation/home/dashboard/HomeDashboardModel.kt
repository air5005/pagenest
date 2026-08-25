package com.wxn.reader.presentation.home.dashboard

import androidx.compose.runtime.Immutable

@Immutable
data class HomeDashboardModel(
    val todayMinutes: Int = 0,
    val streakDays: Int = 0,
    val weekMinutes: Int = 0,
    val weeklyGoalMinutes: Int = HomeDashboardCalculator.DEFAULT_WEEKLY_GOAL_MINUTES,
    val weekProgress: Float = 0f,
    val totalBookCount: Int = 0,
    val recentBooks: List<RecentBookModel> = emptyList(),
)

@Immutable
data class RecentBookModel(
    val id: Long,
    val title: String,
    val author: String,
    val coverImage: String?,
    val progressPercent: Float,
    val locationLabel: String,
    val lastOpenedEpochMillis: Long,
)

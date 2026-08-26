package com.wxn.reader.presentation.mainReader

import com.wxn.bookread.data.model.preference.ReaderPreferences

enum class ReaderPreferenceChange {
    NONE,
    APPEARANCE_ONLY,
    REPAGINATE,
}

object ReaderPreferenceChangePolicy {
    fun classify(
        previous: ReaderPreferences,
        updated: ReaderPreferences,
    ): ReaderPreferenceChange {
        if (previous == updated) return ReaderPreferenceChange.NONE

        val previousWithUpdatedAppearance = previous.copy(
            backgroundColor = updated.backgroundColor,
            backgroundImage = updated.backgroundImage,
            textColor = updated.textColor,
            colorHistory = updated.colorHistory,
        )
        return if (previousWithUpdatedAppearance == updated) {
            ReaderPreferenceChange.APPEARANCE_ONLY
        } else {
            ReaderPreferenceChange.REPAGINATE
        }
    }
}

package com.wxn.reader.presentation.mainReader

import android.graphics.Color
import com.wxn.bookread.data.source.local.ReaderPreferencesUtil
import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPreferenceChangePolicyTest {
    private val original = ReaderPreferencesUtil.defaultPreferences

    @Test
    fun colorsAndBackgroundsRefreshAppearanceWithoutRepagination() {
        val recolored = original.copy(
            backgroundColor = Color.LTGRAY,
            textColor = Color.DKGRAY,
            backgroundImage = "paper.png",
        )

        assertEquals(
            ReaderPreferenceChange.APPEARANCE_ONLY,
            ReaderPreferenceChangePolicy.classify(original, recolored),
        )
    }

    @Test
    fun typographyChangesStillRepaginateTheChapter() {
        val resized = original.copy(fontSize = original.fontSize + 0.2)

        assertEquals(
            ReaderPreferenceChange.REPAGINATE,
            ReaderPreferenceChangePolicy.classify(original, resized),
        )
    }

    @Test
    fun identicalPreferencesDoNotRefreshTheReader() {
        assertEquals(
            ReaderPreferenceChange.NONE,
            ReaderPreferenceChangePolicy.classify(original, original),
        )
    }
}

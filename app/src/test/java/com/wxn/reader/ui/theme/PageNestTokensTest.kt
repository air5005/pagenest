package com.wxn.reader.ui.theme

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Test

class PageNestTokensTest {
    @Test
    fun brandPaletteUsesApprovedBlueGreenValues() {
        assertEquals(0xFF18A69D.toInt(), PageNestPalette.Teal.toArgb())
        assertEquals(0xFF397DE4.toInt(), PageNestPalette.Blue.toArgb())
        assertEquals(0xFFF5F8F9.toInt(), PageNestPalette.LightBackground.toArgb())
        assertEquals(0xFFF5F1E8.toInt(), PageNestPalette.ReadingPaper.toArgb())
    }

    @Test
    fun touchTargetsAndCardRadiusMeetTheUiContract() {
        assertEquals(48, PageNestSpacing.MinimumTouchTarget.value.toInt())
        assertEquals(22, PageNestSpacing.LargeCardRadius.value.toInt())
    }
}

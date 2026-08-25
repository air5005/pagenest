package com.wxn.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable

@Composable
fun PageNestTheme(
    darkTheme: Boolean,
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) PageNestDarkColorScheme else PageNestLightColorScheme,
        typography = Typography,
        shapes = Shapes(
            extraLarge = PageNestShapes.LargeCard,
            large = PageNestShapes.MediumCard,
            medium = PageNestShapes.SmallControl,
        ),
        content = content,
    )
}

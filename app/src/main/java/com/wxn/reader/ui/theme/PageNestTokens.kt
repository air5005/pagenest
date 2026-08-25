package com.wxn.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

object PageNestPalette {
    val Teal = Color(0xFF18A69D)
    val Blue = Color(0xFF397DE4)
    val LightBackground = Color(0xFFF5F8F9)
    val DarkBackground = Color(0xFF101719)
    val ReadingPaper = Color(0xFFF5F1E8)
    val ReadingInk = Color(0xFF2D312E)
    val LightSurface = Color(0xFFFFFFFF)
    val DarkSurface = Color(0xFF182124)
}

object PageNestSpacing {
    val ScreenHorizontal = 16.dp
    val CardGap = 12.dp
    val MinimumTouchTarget = 48.dp
    val LargeCardRadius = 22.dp
}

object PageNestShapes {
    val LargeCard = RoundedCornerShape(PageNestSpacing.LargeCardRadius)
    val MediumCard = RoundedCornerShape(16.dp)
    val SmallControl = RoundedCornerShape(12.dp)
}

val PageNestBrandGradient = Brush.linearGradient(
    colors = listOf(PageNestPalette.Teal, PageNestPalette.Blue),
)

val PageNestLightColorScheme = lightColorScheme(
    primary = PageNestPalette.Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7F4F0),
    onPrimaryContainer = Color(0xFF003733),
    secondary = Color(0xFF397B82),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD8ECEE),
    onSecondaryContainer = Color(0xFF102F34),
    tertiary = PageNestPalette.Blue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDDE7FF),
    onTertiaryContainer = Color(0xFF102B5C),
    background = PageNestPalette.LightBackground,
    onBackground = Color(0xFF18333C),
    surface = PageNestPalette.LightSurface,
    onSurface = Color(0xFF18333C),
    surfaceVariant = Color(0xFFE8F0F2),
    onSurfaceVariant = Color(0xFF526970),
    outline = Color(0xFFB8C7CB),
)

val PageNestDarkColorScheme = darkColorScheme(
    primary = Color(0xFF6EDBD0),
    onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF075F5B),
    onPrimaryContainer = Color(0xFFB5FFF6),
    secondary = Color(0xFFA5CDD1),
    onSecondary = Color(0xFF12373C),
    secondaryContainer = Color(0xFF294C51),
    onSecondaryContainer = Color(0xFFC1E9ED),
    tertiary = Color(0xFFAFC6FF),
    onTertiary = Color(0xFF0D2F65),
    tertiaryContainer = Color(0xFF25477E),
    onTertiaryContainer = Color(0xFFD9E3FF),
    background = PageNestPalette.DarkBackground,
    onBackground = Color(0xFFDCE7E9),
    surface = PageNestPalette.DarkSurface,
    onSurface = Color(0xFFDCE7E9),
    surfaceVariant = Color(0xFF2A383C),
    onSurfaceVariant = Color(0xFFB8C8CC),
    outline = Color(0xFF83969B),
)

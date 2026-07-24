package com.example.input_ds.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 应用配色 - 深色主题（适合 BCI 扫描显示）
val DarkBackground = Color(0xFF121212)
val SurfaceDark = Color(0xFF1E1E2E)
val PrimaryBlue = Color(0xFF5B8DEF)
val AccentGreen = Color(0xFF4CAF50)
val HighlightYellow = Color(0xFFFFD740)
val HighlightOrange = Color(0xFFFF9800)
val TextWhite = Color(0xFFE0E0E0)
val TextGray = Color(0xFF9E9E9E)
val BlockBorder = Color(0xFF3A3A5C)
val ErrorRed = Color(0xFFEF5350)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlue,
    secondary = AccentGreen,
    tertiary = HighlightYellow,
    background = DarkBackground,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.Black,
    onBackground = TextWhite,
    onSurface = TextWhite,
    error = ErrorRed,
    onError = Color.White
)

@Composable
fun InputDSTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}

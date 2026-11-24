package com.example.input_ds.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor

private val BciTypography = Typography(
    headlineLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BciType.chinese, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = BciType.chinese, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val BciShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Shared background for pages still using the original component API. */
@Composable
fun BciBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

fun Modifier.bciBackground(): Modifier = composed {
    background(MaterialTheme.colorScheme.background)
}

@Composable
fun InputDSTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = (if (darkTheme) Night else Day).animated()
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    MaterialTheme(
        colorScheme = base.copy(
            primary = colors.focus, onPrimary = colors.bg, primaryContainer = colors.focusTint,
            secondary = colors.muted, background = colors.bg, surface = colors.panel,
            surfaceVariant = colors.panel2, surfaceContainer = colors.panel,
            surfaceContainerHigh = colors.panel2, outline = colors.line, onSurface = colors.text,
            onBackground = colors.text, onSurfaceVariant = colors.muted, error = colors.alarm
        ),
        typography = BciTypography,
        shapes = BciShapes,
    ) {
        CompositionLocalProvider(
            LocalBciColors provides colors,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}

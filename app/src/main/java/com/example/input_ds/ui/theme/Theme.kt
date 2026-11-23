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

val AuroraDarkBackground = Night.bg
val AuroraDarkSubtle = Night.mag
val AuroraDarkSurface = Night.panel
val AuroraDarkSurfaceStrong = Night.panel2
val AuroraDarkAccentSoft = Night.focusTint
val AuroraDarkOkSoft = Night.okTint
val AuroraDarkWarnSoft = Night.focusTint
val AuroraDarkBadSoft = Night.alarmTint
val AuroraDarkInfoSoft = Night.focusTint
val AuroraDarkBorder = Night.line
val AuroraDarkBorderStrong = Night.dim
val AuroraDarkText = Night.text
val AuroraDarkTextSecondary = Night.muted
val AuroraDarkTextTertiary = Night.dim
val AuroraViolet = Night.focus
val AuroraVioletBright = Night.focus
val AuroraCyan = Night.focus
val AuroraPink = Night.focus
val AuroraOk = Night.ok
val AuroraWarn = Night.focus
val AuroraBad = Night.alarm
val AuroraInfo = Night.muted
val AuroraLightBackground = Day.bg
val AuroraLightSubtle = Day.mag
val AuroraLightSurface = Day.panel
val AuroraLightSurfaceStrong = Day.panel2
val AuroraLightBorder = Day.line
val AuroraLightText = Day.text
val AuroraLightTextSecondary = Day.muted
val AuroraLightAccent = Day.focus

// Compatibility aliases keep feature code token-driven while applying Aurora Glass everywhere.
val DarkBackground = AuroraDarkBackground
val SurfaceDark = AuroraDarkSurface
val SurfaceElevated = AuroraDarkSurfaceStrong
val PrimaryBlue = AuroraViolet
val AccentGreen = AuroraOk
val HighlightYellow = AuroraVioletBright
val HighlightOrange = AuroraPink
val TextWhite = AuroraDarkText
val TextGray = AuroraDarkTextSecondary
val BlockBorder = AuroraDarkBorder
val ErrorRed = AuroraBad

private val AuroraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = BciType.chinese, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = BciType.chinese, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = BciType.chinese, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val AuroraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Shared background for pages still using the original component API. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

fun Modifier.auroraBackground(): Modifier = composed {
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
        typography = AuroraTypography,
        shapes = AuroraShapes,
    ) {
        CompositionLocalProvider(
            LocalBciColors provides colors,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}

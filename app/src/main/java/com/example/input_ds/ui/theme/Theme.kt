package com.example.input_ds.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor

/** Aurora Glass design tokens, adapted from theme.html for Compose. */
val AuroraDarkBackground = Color(0xFF0A0D1E)
val AuroraDarkSubtle = Color(0xFF0D1126)
val AuroraDarkSurface = Color(0x14FFFFFF)
val AuroraDarkSurfaceStrong = Color(0x24FFFFFF)
val AuroraDarkAccentSoft = Color(0x2EA78BFA)
val AuroraDarkOkSoft = Color(0x2634D399)
val AuroraDarkWarnSoft = Color(0x24FCD34D)
val AuroraDarkBadSoft = Color(0x26FB7185)
val AuroraDarkInfoSoft = Color(0x247DD3FC)
val AuroraDarkBorder = Color(0x24FFFFFF)
val AuroraDarkBorderStrong = Color(0x52FFFFFF)
val AuroraDarkText = Color(0xFFEEF0FF)
val AuroraDarkTextSecondary = Color(0xFFB9BDD9)
val AuroraDarkTextTertiary = Color(0xFF7F84A8)
val AuroraViolet = Color(0xFFA78BFA)
val AuroraVioletBright = Color(0xFFC4B5FD)
val AuroraCyan = Color(0xFF22D3EE)
val AuroraPink = Color(0xFFF0ABFC)
val AuroraOk = Color(0xFF34D399)
val AuroraWarn = Color(0xFFFCD34D)
val AuroraBad = Color(0xFFFB7185)
val AuroraInfo = Color(0xFF7DD3FC)

val AuroraLightBackground = Color(0xFFEEF1FA)
val AuroraLightSubtle = Color(0xFFE4E8F6)
val AuroraLightSurface = Color(0xB8FFFFFF)
val AuroraLightSurfaceStrong = Color(0xE6FFFFFF)
val AuroraLightBorder = Color(0x477078B4)
val AuroraLightText = Color(0xFF232848)
val AuroraLightTextSecondary = Color(0xFF565D85)
val AuroraLightAccent = Color(0xFF7C3AED)

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

private val DarkColorScheme = darkColorScheme(
    primary = AuroraViolet,
    onPrimary = Color(0xFF17103A),
    primaryContainer = Color(0x2EA78BFA),
    onPrimaryContainer = AuroraVioletBright,
    secondary = AuroraCyan,
    onSecondary = Color(0xFF061C24),
    secondaryContainer = Color(0x2422D3EE),
    onSecondaryContainer = AuroraInfo,
    tertiary = AuroraPink,
    background = AuroraDarkBackground,
    surface = AuroraDarkSurface,
    surfaceVariant = AuroraDarkSurfaceStrong,
    surfaceContainer = AuroraDarkSurface,
    surfaceContainerHigh = AuroraDarkSurfaceStrong,
    outline = AuroraDarkBorder,
    outlineVariant = AuroraDarkBorderStrong,
    onBackground = AuroraDarkText,
    onSurface = AuroraDarkText,
    onSurfaceVariant = AuroraDarkTextSecondary,
    error = AuroraBad,
    onError = Color(0xFF2D0713)
)

private val LightColorScheme = lightColorScheme(
    primary = AuroraLightAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0x247C3AED),
    onPrimaryContainer = Color(0xFF4C1D95),
    secondary = Color(0xFF0891B2),
    background = AuroraLightBackground,
    surface = AuroraLightSurface,
    surfaceVariant = AuroraLightSurfaceStrong,
    surfaceContainer = AuroraLightSurface,
    surfaceContainerHigh = AuroraLightSurfaceStrong,
    outline = AuroraLightBorder,
    onBackground = AuroraLightText,
    onSurface = AuroraLightText,
    onSurfaceVariant = AuroraLightTextSecondary,
    error = Color(0xFFD13060),
    onError = Color.White
)

private val AuroraTypography = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
)

private val AuroraShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/** Layered mesh background used by every top-level screen. */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x66391A78), Color.Transparent),
                    center = Offset(size.width * .16f, size.height * .18f),
                    radius = size.maxDimension * .52f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x590E7490), Color.Transparent),
                    center = Offset(size.width * .84f, size.height * .10f),
                    radius = size.maxDimension * .46f
                )
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(Color(0x472D1B69), Color.Transparent),
                    center = Offset(size.width * .55f, size.height * .92f),
                    radius = size.maxDimension * .58f
                )
            )
        }
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
            content()
        }
    }
}

fun Modifier.auroraBackground(): Modifier = composed {
    val dark = MaterialTheme.colorScheme.background == AuroraDarkBackground
    val base = if (dark) AuroraDarkBackground else AuroraLightBackground
    val mesh = if (dark) {
        arrayOf(
            0.00f to Color(0x8A391A78),
            0.42f to Color(0x5A0E7490),
            0.72f to Color(0x462D1B69),
            1.00f to AuroraDarkBackground
        )
    } else {
        arrayOf(
            0.00f to Color(0xCFA78BFA),
            0.38f to Color(0xA867E8F9),
            0.72f to Color(0x96F0ABFC),
            1.00f to AuroraLightBackground
        )
    }
    background(base).background(
        Brush.linearGradient(colorStops = mesh, tileMode = TileMode.Clamp)
    )
}

@Composable
fun InputDSTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = AuroraTypography,
        shapes = AuroraShapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
            content = content
        )
    }
}

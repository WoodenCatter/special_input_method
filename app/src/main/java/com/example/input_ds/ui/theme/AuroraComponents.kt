package com.example.input_ds.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AuroraButtonStyle { PRIMARY, SECONDARY, GHOST, DANGER, SUCCESS }
enum class AuroraBadgeStyle { NEUTRAL, SUCCESS, WARNING, DANGER, INFO }

@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AuroraDarkAccentSoft else AuroraDarkSurface
        ),
        border = BorderStroke(
            1.dp,
            if (selected) AuroraViolet else AuroraDarkBorder
        )
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content
        )
    }
}

@Composable
fun SelectableGlassPanel(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    GlassPanel(
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        selected = selected,
        contentPadding = contentPadding,
        content = content
    )
}

@Composable
fun AuroraButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AuroraButtonStyle = AuroraButtonStyle.SECONDARY,
    enabled: Boolean = true,
    leading: String? = null,
    minHeight: Dp = 48.dp
) {
    val shape = MaterialTheme.shapes.medium
    val background = when (style) {
        AuroraButtonStyle.PRIMARY -> Brush.linearGradient(listOf(Color(0xFF7C3AED), Color(0xFF0891B2)))
        AuroraButtonStyle.SUCCESS -> Brush.linearGradient(listOf(Color(0xCC047857), Color(0xCC0F766E)))
        AuroraButtonStyle.DANGER -> Brush.linearGradient(listOf(AuroraDarkBadSoft, AuroraDarkBadSoft))
        AuroraButtonStyle.GHOST -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        AuroraButtonStyle.SECONDARY -> Brush.linearGradient(listOf(AuroraDarkSurfaceStrong, AuroraDarkSurfaceStrong))
    }
    val border = when (style) {
        AuroraButtonStyle.PRIMARY -> Color.Transparent
        AuroraButtonStyle.DANGER -> AuroraBad.copy(alpha = .55f)
        AuroraButtonStyle.SUCCESS -> AuroraOk.copy(alpha = .55f)
        AuroraButtonStyle.GHOST -> Color.Transparent
        AuroraButtonStyle.SECONDARY -> AuroraDarkBorderStrong
    }
    val foreground = when (style) {
        AuroraButtonStyle.DANGER -> AuroraBad
        AuroraButtonStyle.SUCCESS -> AuroraDarkText
        AuroraButtonStyle.GHOST -> AuroraVioletBright
        else -> AuroraDarkText
    }
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = minHeight)
            .alpha(if (enabled) 1f else .45f)
            .clip(shape)
            .background(background)
            .border(1.dp, border, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            leading?.let { Text(it, color = foreground) }
            Text(
                text,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun AuroraBadge(
    text: String,
    modifier: Modifier = Modifier,
    style: AuroraBadgeStyle = AuroraBadgeStyle.NEUTRAL
) {
    val (background, foreground) = when (style) {
        AuroraBadgeStyle.SUCCESS -> AuroraDarkOkSoft to AuroraOk
        AuroraBadgeStyle.WARNING -> AuroraDarkWarnSoft to AuroraWarn
        AuroraBadgeStyle.DANGER -> AuroraDarkBadSoft to AuroraBad
        AuroraBadgeStyle.INFO -> AuroraDarkInfoSoft to AuroraInfo
        AuroraBadgeStyle.NEUTRAL -> AuroraDarkSurfaceStrong to AuroraDarkTextSecondary
    }
    Surface(
        modifier = modifier,
        color = background,
        contentColor = foreground,
        shape = CircleShape,
        border = BorderStroke(1.dp, foreground.copy(alpha = .18f))
    ) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 22.dp)
                .clip(CircleShape)
                .background(AuroraViolet)
        )
        Spacer(Modifier.size(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            subtitle?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        trailing?.invoke()
    }
}

@Composable
fun AuroraEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    symbol: String = "◇"
) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(symbol, color = AuroraVioletBright, style = MaterialTheme.typography.headlineMedium)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun AuroraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    supportingText: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        isError = isError,
        singleLine = true,
        label = { Text(label) },
        supportingText = supportingText?.let { text ->
            { Text(text, color = if (isError) AuroraBad else AuroraDarkTextTertiary) }
        },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = AuroraDarkSurfaceStrong,
            unfocusedContainerColor = AuroraDarkSurface,
            disabledContainerColor = AuroraDarkSurface,
            focusedBorderColor = AuroraViolet,
            unfocusedBorderColor = AuroraDarkBorderStrong,
            cursorColor = AuroraVioletBright,
            focusedLabelColor = AuroraVioletBright,
            unfocusedLabelColor = AuroraDarkTextSecondary,
            errorBorderColor = AuroraBad,
            errorLabelColor = AuroraBad
        )
    )
}

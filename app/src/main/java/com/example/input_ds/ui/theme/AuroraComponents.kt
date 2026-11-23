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
            containerColor = if (selected) LocalBciColors.current.focusTint else LocalBciColors.current.panel
        ),
        border = BorderStroke(
            1.dp,
            if (selected) LocalBciColors.current.focus else LocalBciColors.current.line
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
        AuroraButtonStyle.PRIMARY -> Brush.linearGradient(listOf(LocalBciColors.current.text, LocalBciColors.current.text))
        AuroraButtonStyle.SUCCESS -> Brush.linearGradient(listOf(LocalBciColors.current.ok, LocalBciColors.current.ok))
        AuroraButtonStyle.DANGER -> Brush.linearGradient(listOf(LocalBciColors.current.alarmTint, LocalBciColors.current.alarmTint))
        AuroraButtonStyle.GHOST -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
        AuroraButtonStyle.SECONDARY -> Brush.linearGradient(listOf(LocalBciColors.current.panel2, LocalBciColors.current.panel2))
    }
    val border = when (style) {
        AuroraButtonStyle.PRIMARY -> Color.Transparent
        AuroraButtonStyle.DANGER -> LocalBciColors.current.alarm.copy(alpha = .55f)
        AuroraButtonStyle.SUCCESS -> LocalBciColors.current.ok.copy(alpha = .55f)
        AuroraButtonStyle.GHOST -> Color.Transparent
        AuroraButtonStyle.SECONDARY -> LocalBciColors.current.dim
    }
    val foreground = when (style) {
        AuroraButtonStyle.DANGER -> LocalBciColors.current.alarm
        AuroraButtonStyle.PRIMARY, AuroraButtonStyle.SUCCESS -> LocalBciColors.current.bg
        AuroraButtonStyle.GHOST -> LocalBciColors.current.focus
        else -> LocalBciColors.current.text
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
        AuroraBadgeStyle.SUCCESS -> LocalBciColors.current.okTint to LocalBciColors.current.ok
        AuroraBadgeStyle.WARNING -> LocalBciColors.current.focusTint to LocalBciColors.current.muted
        AuroraBadgeStyle.DANGER -> LocalBciColors.current.alarmTint to LocalBciColors.current.alarm
        AuroraBadgeStyle.INFO -> LocalBciColors.current.focusTint to LocalBciColors.current.muted
        AuroraBadgeStyle.NEUTRAL -> LocalBciColors.current.panel2 to LocalBciColors.current.muted
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
                .background(LocalBciColors.current.focus)
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
        Text(symbol, color = LocalBciColors.current.focus, style = MaterialTheme.typography.headlineMedium)
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
            { Text(text, color = if (isError) LocalBciColors.current.alarm else LocalBciColors.current.dim) }
        },
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        shape = MaterialTheme.shapes.small,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = LocalBciColors.current.panel2,
            unfocusedContainerColor = LocalBciColors.current.panel,
            disabledContainerColor = LocalBciColors.current.panel,
            focusedBorderColor = LocalBciColors.current.focus,
            unfocusedBorderColor = LocalBciColors.current.dim,
            cursorColor = LocalBciColors.current.focus,
            focusedLabelColor = LocalBciColors.current.focus,
            unfocusedLabelColor = LocalBciColors.current.muted,
            errorBorderColor = LocalBciColors.current.alarm,
            errorLabelColor = LocalBciColors.current.alarm
        )
    )
}

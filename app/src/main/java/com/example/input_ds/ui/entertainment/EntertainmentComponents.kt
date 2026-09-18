package com.example.input_ds.ui.entertainment

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.input_ds.ui.theme.AuroraDarkAccentSoft
import com.example.input_ds.ui.theme.AuroraDarkBorderStrong
import com.example.input_ds.ui.theme.AuroraDarkSurfaceStrong
import com.example.input_ds.ui.theme.AuroraViolet
import com.example.input_ds.ui.theme.AuroraVioletBright

@Composable
fun EntertainmentTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backSelected: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        EntertainmentFocusButton(
            text = "返回",
            leading = "←",
            selected = backSelected,
            onClick = onBack
        )
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun EntertainmentFocusButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: String? = null
) {
    Surface(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        color = if (selected) AuroraDarkAccentSoft else AuroraDarkSurfaceStrong,
        contentColor = if (selected) AuroraVioletBright else MaterialTheme.colorScheme.onSurface,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) AuroraViolet else AuroraDarkBorderStrong
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 13.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            leading?.let {
                Text(it, color = if (selected) AuroraVioletBright else Color.Unspecified)
                Text("  ")
            }
            Text(text, fontWeight = FontWeight.SemiBold)
        }
    }
}

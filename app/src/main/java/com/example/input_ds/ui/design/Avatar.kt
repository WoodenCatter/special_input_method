package com.example.input_ds.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors

fun contactAvatarHue(principalId: String, overrideHue: Float? = null): Float {
    if (overrideHue != null) return ((overrideHue % 360f) + 360f) % 360f
    val palette = floatArrayOf(12f, 200f, 160f, 300f, 36f, 240f)
    val idx = (principalId.hashCode() and 0x7fffffff) % palette.size
    return palette[idx]
}

@Composable
fun ContactAvatar(
    text: String,
    size: Dp,
    fontSize: Int,
    modifier: Modifier = Modifier,
    self: Boolean = false,
    online: Boolean = false,
    hue: Float = 24f,
    ringColor: Color? = null
) {
    val c = LocalBciColors.current
    val fill = if (self) c.ok else Color.hsl(((hue % 360f) + 360f) % 360f, 0.42f, 0.48f)
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .background(fill, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text.take(1),
                color = Color.White,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = fontSize.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        if (online && !self) {
            val dotRing = ringColor ?: c.panel
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 2.dp, y = 2.dp)
                    .size(22.dp)
                    .background(dotRing, CircleShape)
                    .border(3.dp, dotRing, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(c.ok, CircleShape)
                )
            }
        }
    }
}

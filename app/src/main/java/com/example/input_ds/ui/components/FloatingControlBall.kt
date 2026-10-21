package com.example.input_ds.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * In-app testing controller. Drag the round handle away from an edge to reveal
 * the three simulated headset commands; drag it back to either edge to collapse.
 */
@Composable
fun FloatingControlBall(
    visible: Boolean,
    onLeftLook: () -> Unit,
    onRightLook: () -> Unit,
    onBite: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(1_000f)
    ) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val centerX = remember { Animatable(0f) }
        val centerY = remember { Animatable(0f) }
        var initialized by remember { mutableStateOf(false) }
        var expanded by remember { mutableStateOf(false) }
        var controlSize by remember { mutableStateOf(IntSize.Zero) }
        val viewportWidth = constraints.maxWidth.toFloat()
        val viewportHeight = constraints.maxHeight.toFloat()
        val handleSizePx = with(density) { 60.dp.toPx() }
        val edgeThresholdPx = with(density) { 92.dp.toPx() }
        val expandedWidthPx = with(density) { 306.dp.toPx() }

        LaunchedEffect(viewportWidth, viewportHeight) {
            if (viewportWidth <= 0f || viewportHeight <= 0f) return@LaunchedEffect
            if (!initialized) {
                centerX.snapTo(viewportWidth - handleSizePx * .25f)
                centerY.snapTo(viewportHeight * .58f)
                initialized = true
            } else {
                centerX.snapTo(centerX.value.coerceIn(0f, viewportWidth))
                centerY.snapTo(centerY.value.coerceIn(handleSizePx / 2f, viewportHeight - handleSizePx / 2f))
            }
        }

        if (!visible || !initialized) return@BoxWithConstraints

        fun settleAfterDrag() {
            scope.launch {
                val dockLeft = centerX.value <= edgeThresholdPx
                val dockRight = centerX.value >= viewportWidth - edgeThresholdPx
                if (dockLeft || dockRight) {
                    expanded = false
                    val dockedX = if (dockLeft) handleSizePx * .25f else viewportWidth - handleSizePx * .25f
                    centerX.animateTo(
                        dockedX,
                        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing)
                    )
                } else {
                    expanded = true
                    val rightLimit = (viewportWidth - expandedWidthPx).coerceAtLeast(viewportWidth / 2f)
                    centerX.animateTo(
                        centerX.value.coerceIn(edgeThresholdPx, rightLimit),
                        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing)
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .offset {
                    IntOffset(
                        x = (centerX.value - handleSizePx / 2f).roundToInt(),
                        y = (centerY.value - controlSize.height / 2f).roundToInt()
                    )
                }
                .onSizeChanged { controlSize = it }
                .animateContentSize(
                    animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing)
                )
                .background(Color(0xEE17242C), RoundedCornerShape(34.dp))
                .border(2.dp, Color(0xFF79E495), RoundedCornerShape(34.dp))
                .padding(4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .background(Color(0xFF58C96F), CircleShape)
                    .border(3.dp, Color.White, CircleShape)
                    .pointerInput(viewportWidth, viewportHeight) {
                        detectDragGestures(
                            onDragEnd = ::settleAfterDrag,
                            onDragCancel = ::settleAfterDrag
                        ) { change, dragAmount ->
                            change.consume()
                            scope.launch {
                                centerX.snapTo((centerX.value + dragAmount.x).coerceIn(0f, viewportWidth))
                                centerY.snapTo(
                                    (centerY.value + dragAmount.y).coerceIn(
                                        handleSizePx / 2f,
                                        viewportHeight - handleSizePx / 2f
                                    )
                                )
                            }
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (expanded) "拖动" else "控制",
                    color = Color(0xFF102217),
                    fontSize = if (expanded) 14.sp else 16.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn(tween(180)) + expandHorizontally(
                    animationSpec = tween(260, easing = FastOutSlowInEasing),
                    expandFrom = Alignment.Start
                ),
                exit = fadeOut(tween(150)) + shrinkHorizontally(
                    animationSpec = tween(220, easing = FastOutSlowInEasing),
                    shrinkTowards = Alignment.Start
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FloatingCommandButton("◀\n左看", Color(0xFF345B82), onLeftLook)
                    Spacer(Modifier.width(6.dp))
                    FloatingCommandButton("●\n咬牙", Color(0xFF91443F), onBite)
                    Spacer(Modifier.width(6.dp))
                    FloatingCommandButton("▶\n右看", Color(0xFF345B82), onRightLook)
                }
            }
        }
    }
}

@Composable
private fun FloatingCommandButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.width(74.dp).height(54.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

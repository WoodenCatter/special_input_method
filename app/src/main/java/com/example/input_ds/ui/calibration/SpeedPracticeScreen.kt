package com.example.input_ds.ui.calibration

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.model.AppUiSettings
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.settings.SpeedPracticeEngine
import com.example.input_ds.settings.SpeedPracticeInputBus
import com.example.input_ds.settings.SpeedPracticeOutcome
import com.example.input_ds.settings.SpeedPracticePhase
import com.example.input_ds.settings.SpeedPracticeSignalSink
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors

/** V3 12-target speed calibration screen reached from Settings row 7. */
@Composable
fun SpeedPracticeScreen(
    initialIntervalMs: Long,
    scanMode: ScanMode,
    paused: Boolean,
    frozen: Boolean,
    onApplyInterval: (Long) -> Unit,
    onToast: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnApply by rememberUpdatedState(onApplyInterval)
    val currentOnToast by rememberUpdatedState(onToast)

    var state by remember { mutableStateOf(SpeedPracticeEngine.create(initialIntervalMs, SystemClock.elapsedRealtime())) }
    var dwellRevision by remember { mutableIntStateOf(0) }

    val c = LocalBciColors.current
    val cssNoPad = PlatformTextStyle(includeFontPadding = false)

    val focusedLabel = if (state.phase == SpeedPracticePhase.PRACTICING) {
        if (state.cursor.index == 0) "退出练习" else "第 ${state.cursor.index} 号"
    } else {
        when (state.cursor.index) {
            0 -> "采用 ${SpeedPracticeEngine.formatInterval(state.recommendedIntervalMs)}"
            1 -> "保持 ${SpeedPracticeEngine.formatInterval(state.initialIntervalMs)}"
            else -> "再练一次"
        }
    }
    val hintsScanMode = if (state.phase == SpeedPracticePhase.PRACTICING) ScanMode.AUTO else scanMode
    ReportActionHints(ActionHints.list(focusedLabel, hintsScanMode, state.cursor.direction))

    fun handleOutcome(outcome: SpeedPracticeOutcome) {
        when (outcome) {
            SpeedPracticeOutcome.Exit, SpeedPracticeOutcome.KeepCurrent -> currentOnBack()
            is SpeedPracticeOutcome.ApplyInterval -> {
                if (!frozen) {
                    currentOnToast("已采用推荐停留时间 ${SpeedPracticeEngine.formatInterval(outcome.intervalMs)}", true)
                }
                currentOnApply(outcome.intervalMs)
            }
            is SpeedPracticeOutcome.Continue -> {
                if (!frozen && outcome.toast != null) {
                    currentOnToast(outcome.toast, outcome.positive)
                }
                dwellRevision++
            }
        }
    }

    fun choose(index: Int) {
        val (next, outcome) = SpeedPracticeEngine.chooseIndex(state, index, SystemClock.elapsedRealtime())
        state = next
        handleOutcome(outcome)
    }

    val signalHandler by rememberUpdatedState<(ControlSignal) -> Unit> { signal ->
        val now = SystemClock.elapsedRealtime()
        val grace = if (frozen) 0L else AppUiSettings.graceMs(context)
        when (signal) {
            ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                state = SpeedPracticeEngine.onLook(state, step, scanMode)
                dwellRevision++
            }
            ControlSignal.BITE -> {
                val effectiveMode = if (state.phase == SpeedPracticePhase.PRACTICING) ScanMode.AUTO else scanMode
                val chosen = state.cursor.selected(now, grace, effectiveMode)
                val (next, outcome) = SpeedPracticeEngine.chooseIndex(state, chosen, now)
                state = next
                handleOutcome(outcome)
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }
    val tickHandler by rememberUpdatedState<() -> Unit> {
        state = SpeedPracticeEngine.onTick(state, SystemClock.elapsedRealtime(), scanMode)
    }
    val sink = remember { SpeedPracticeSignalSink { signalHandler(it) } }
    DisposableEffect(sink) {
        SpeedPracticeInputBus.attach(sink, onTick = { tickHandler() })
        onDispose { SpeedPracticeInputBus.detach(sink) }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentPaused by rememberUpdatedState(paused)
    LaunchedEffect(paused) {
        state = SpeedPracticeEngine.resetForPauseBoundary(
            state = state,
            resumed = !paused,
            nowMs = SystemClock.elapsedRealtime()
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    state = SpeedPracticeEngine.resetForPauseBoundary(state, resumed = false)
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (!currentPaused) {
                        state = SpeedPracticeEngine.resetForPauseBoundary(
                            state = state,
                            resumed = true,
                            nowMs = SystemClock.elapsedRealtime()
                        )
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val autoScanning = state.phase == SpeedPracticePhase.PRACTICING || scanMode == ScanMode.AUTO
    LaunchedEffect(paused, frozen, state.phase, state.trialIndex, state.currentIntervalMs, scanMode, dwellRevision, lifecycleOwner) {
        val interval = if (state.phase == SpeedPracticePhase.PRACTICING) state.currentIntervalMs else state.initialIntervalMs
        if (!paused && !frozen && autoScanning) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                state = state.copy(cursor = state.cursor.copy(previousIndex = -1, advancedAtMs = 0L))
                runScanTimer(
                    interval = { interval },
                    onFailure = { android.util.Log.e("ScanCursor", "SpeedPractice tick failed", it) }
                ) {
                    val activeInterval = if (state.phase == SpeedPracticePhase.PRACTICING) {
                        state.currentIntervalMs
                    } else {
                        state.initialIntervalMs
                    }
                    state = SpeedPracticeEngine.onTick(state, SystemClock.elapsedRealtime(), scanMode)
                    if (activeInterval != interval) dwellRevision++
                }
            }
        }
    }

    val dwellProgress = remember { Animatable(0f) }
    val activeDwellMs = if (state.phase == SpeedPracticePhase.PRACTICING) state.currentIntervalMs else state.initialIntervalMs
    LaunchedEffect(state.phase, state.trialIndex, state.cursor.index, state.cursor.direction, dwellRevision, activeDwellMs, autoScanning, paused, frozen, lifecycleOwner) {
        when {
            frozen -> dwellProgress.snapTo(if (autoScanning) 0.5f else 0f)
            paused || !autoScanning -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(activeDwellMs.toInt(), easing = LinearEasing))
            }
        }
    }

    val dir = if (autoScanning) state.cursor.direction else 0
    val progressProvider: () -> Float? = { if (autoScanning) dwellProgress.value else null }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        if (state.phase == SpeedPracticePhase.PRACTICING) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "速度练习",
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(900),
                        fontSize = 30.sp,
                        style = TextStyle(platformStyle = cssNoPad)
                    )
                    Text(
                        text = "第 ${state.trialIndex + 1} / ${SpeedPracticeEngine.TOTAL_TRIALS} 题",
                        color = c.focus,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        style = TextStyle(platformStyle = cssNoPad)
                    )
                }
                Text(
                    text = "当前节奏 ${SpeedPracticeEngine.formatInterval(state.currentIntervalMs)}",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    style = TextStyle(platformStyle = cssNoPad)
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.panel, RoundedCornerShape(16.dp))
                    .border(1.dp, c.line, RoundedCornerShape(16.dp))
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = buildAnnotatedString {
                        append("目标：")
                        withStyle(SpanStyle(color = c.ok, fontWeight = FontWeight(900))) {
                            append("第 ${state.targetIndex} 号")
                        }
                    },
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    style = TextStyle(platformStyle = cssNoPad)
                )
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val exitFocused = state.cursor.index == 0
                Column(
                    modifier = Modifier
                        .width(176.dp)
                        .fillMaxHeight()
                        .scanItem(
                            focused = exitFocused,
                            direction = dir,
                            picked = false,
                            progress = if (exitFocused) progressProvider else null,
                            navigation = true,
                            showDirectionTag = false
                        )
                        .clickable(enabled = !paused) { choose(0) }
                        .padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        contentDescription = null,
                        tint = c.muted,
                        modifier = Modifier.size(32.dp)
                    )
                    Text(
                        text = "退出练习",
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 24.sp,
                        style = TextStyle(platformStyle = cssNoPad)
                    )
                }

                for (slot in 1..4) {
                    val focused = state.cursor.index == slot
                    val isTarget = state.targetIndex == slot
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = dir,
                                picked = false,
                                progress = if (focused) progressProvider else null
                            )
                            .then(
                                if (isTarget && !focused) {
                                    Modifier.border(2.dp, c.ok, RoundedCornerShape(20.dp))
                                } else Modifier
                            )
                            .clickable(enabled = !paused) { choose(slot) }
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(if (isTarget) c.okTint else c.panel2)
                                .border(1.dp, if (isTarget) c.ok else c.line, CircleShape)
                                .padding(horizontal = 16.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isTarget) "目标" else "选项",
                                color = if (isTarget) c.ok else c.muted,
                                fontFamily = BciType.chinese,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                style = TextStyle(platformStyle = cssNoPad)
                            )
                        }
                        Text(
                            text = slot.toString(),
                            color = if (focused) c.focus else if (isTarget) c.ok else c.text,
                            fontFamily = BciType.latin,
                            fontWeight = FontWeight(800),
                            fontSize = 84.sp,
                            style = TextStyle(platformStyle = cssNoPad)
                        )
                    }
                }
            }
        } else {
            val hits = state.records.count { it.hit }
            val resultLabels = listOf(
                "采用 ${SpeedPracticeEngine.formatInterval(state.recommendedIntervalMs)}",
                "保持 ${SpeedPracticeEngine.formatInterval(state.initialIntervalMs)}",
                "再练一次"
            )
            val recommendedText = SpeedPracticeEngine.formatInterval(state.recommendedIntervalMs)
            Text(
                text = "练习完成 · ${state.records.size} / ${SpeedPracticeEngine.TOTAL_TRIALS} 题",
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(900),
                fontSize = 36.sp,
                lineHeight = 44.sp,
                style = TextStyle(platformStyle = cssNoPad)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(c.panel, RoundedCornerShape(22.dp))
                    .border(1.dp, c.line, RoundedCornerShape(22.dp))
                    .padding(horizontal = 36.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "推荐每项停留时间",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 22.sp,
                    lineHeight = 30.sp,
                    style = TextStyle(platformStyle = cssNoPad)
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = buildAnnotatedString {
                        for (ch in recommendedText) {
                            withStyle(SpanStyle(fontFamily = if (ch.code < 0x3000) BciType.latin else BciType.chinese)) {
                                append(ch)
                            }
                        }
                    },
                    color = c.focus,
                    fontWeight = FontWeight(800),
                    fontSize = 96.sp,
                    lineHeight = 96.sp,
                    style = TextStyle(platformStyle = cssNoPad)
                )
                Spacer(Modifier.height(14.dp))
                Text(
                    text = "命中 $hits / ${state.records.size} 题 · 当前停留 ${SpeedPracticeEngine.formatInterval(state.initialIntervalMs)}",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    lineHeight = 32.sp,
                    style = TextStyle(platformStyle = cssNoPad)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                resultLabels.forEachIndexed { index, label ->
                    val focused = index == state.cursor.index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .scanItem(
                                focused = focused,
                                direction = dir,
                                picked = false,
                                progress = if (focused) progressProvider else null
                            )
                            .clickable(enabled = !paused) { choose(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 30.sp,
                            style = TextStyle(platformStyle = cssNoPad)
                        )
                    }
                }
            }
        }
    }
}

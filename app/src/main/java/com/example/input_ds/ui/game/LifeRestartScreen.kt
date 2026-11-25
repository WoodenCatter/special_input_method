package com.example.input_ds.ui.game

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.input_ds.R
import com.example.input_ds.game.LifeAllocationPreset
import com.example.input_ds.game.LifeJudgeResult
import com.example.input_ds.game.LifeRestartPhase
import com.example.input_ds.game.LifeRestartRules
import com.example.input_ds.game.LifeRestartState
import com.example.input_ds.game.LifeTalent
import com.example.input_ds.game.LifeYearLog
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.ui.design.ActionHints
import com.example.input_ds.ui.design.ReportActionHints
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * BCI-friendly item descriptor for the current phase of LifeRestart.
 */
sealed interface LifeRestartScanItem {
    val label: String
    val isNavigation: Boolean get() = false

    data class TalentOption(val talent: LifeTalent) : LifeRestartScanItem {
        override val label: String get() = talent.name
    }
    data class PickAction(val kind: Kind, override val label: String) : LifeRestartScanItem {
        enum class Kind { CONFIRM, REROLL, EXIT }
        override val isNavigation: Boolean get() = kind == Kind.EXIT
    }
    data class AllocPresetOption(val preset: LifeAllocationPreset) : LifeRestartScanItem {
        override val label: String get() = preset.label
    }
    data class AllocAction(val kind: Kind, override val label: String) : LifeRestartScanItem {
        enum class Kind { START, BACK_TO_TALENTS, EXIT }
        override val isNavigation: Boolean get() = kind != Kind.START
    }
    data class PlayAction(val kind: Kind, override val label: String) : LifeRestartScanItem {
        enum class Kind { TOGGLE_AUTO, NEXT_YEAR, TOGGLE_TTS, SUMMARY, EXIT }
        override val isNavigation: Boolean get() = kind == Kind.EXIT
    }
    data class LockTalentOption(val talent: LifeTalent, val locked: Boolean) : LifeRestartScanItem {
        override val label: String get() = if (locked) "取消继承「${talent.name}」" else "继承「${talent.name}」"
    }
    data class SummaryAction(val kind: Kind, override val label: String) : LifeRestartScanItem {
        enum class Kind { RESTART, EXIT }
        override val isNavigation: Boolean get() = kind == Kind.EXIT
    }
}

fun buildLifeRestartScanItems(state: LifeRestartState): List<LifeRestartScanItem> = when (state.phase) {
    LifeRestartPhase.PICK_TALENTS -> buildList {
        state.pulledTalents.forEach { add(LifeRestartScanItem.TalentOption(it)) }
        val confirmLabel = if (state.selectedTalentIds.size >= LifeRestartRules.PICK_COUNT) {
            "去分配属性"
        } else {
            "随机选满并继续"
        }
        add(LifeRestartScanItem.PickAction(LifeRestartScanItem.PickAction.Kind.CONFIRM, confirmLabel))
        add(LifeRestartScanItem.PickAction(LifeRestartScanItem.PickAction.Kind.REROLL, "换一批天赋"))
        add(LifeRestartScanItem.PickAction(LifeRestartScanItem.PickAction.Kind.EXIT, "离开游戏"))
    }

    LifeRestartPhase.ALLOCATE -> buildList {
        add(LifeRestartScanItem.AllocAction(LifeRestartScanItem.AllocAction.Kind.START, "开始新人生"))
        LifeAllocationPreset.entries.forEach { add(LifeRestartScanItem.AllocPresetOption(it)) }
        add(LifeRestartScanItem.AllocAction(LifeRestartScanItem.AllocAction.Kind.BACK_TO_TALENTS, "重选天赋"))
        add(LifeRestartScanItem.AllocAction(LifeRestartScanItem.AllocAction.Kind.EXIT, "离开游戏"))
    }

    LifeRestartPhase.PLAYING -> if (state.ended) {
        listOf(
            LifeRestartScanItem.PlayAction(LifeRestartScanItem.PlayAction.Kind.SUMMARY, "查看人生总结"),
            LifeRestartScanItem.PlayAction(LifeRestartScanItem.PlayAction.Kind.EXIT, "离开游戏")
        )
    } else {
        listOf(
            LifeRestartScanItem.PlayAction(
                LifeRestartScanItem.PlayAction.Kind.TOGGLE_AUTO,
                if (state.autoPlay) "暂停播放" else "继续自动播放"
            ),
            LifeRestartScanItem.PlayAction(LifeRestartScanItem.PlayAction.Kind.NEXT_YEAR, "下一年"),
            LifeRestartScanItem.PlayAction(
                LifeRestartScanItem.PlayAction.Kind.TOGGLE_TTS,
                if (state.readAloud) "语音朗读：开" else "语音朗读：关"
            ),
            LifeRestartScanItem.PlayAction(LifeRestartScanItem.PlayAction.Kind.EXIT, "离开游戏")
        )
    }

    LifeRestartPhase.SUMMARY -> buildList {
        add(LifeRestartScanItem.SummaryAction(LifeRestartScanItem.SummaryAction.Kind.RESTART, "再次重开"))
        state.activeTalents.take(3).forEach { talent ->
            add(LifeRestartScanItem.LockTalentOption(talent, state.pendingLockTalentId == talent.id))
        }
        add(LifeRestartScanItem.SummaryAction(LifeRestartScanItem.SummaryAction.Kind.EXIT, "离开游戏"))
    }
}

@Composable
fun LifeRestartScreen(
    state: LifeRestartState,
    cursor: ScanCursor,
    scanMode: ScanMode,
    scanIntervalMs: Long,
    dwellRevision: Int,
    paused: Boolean,
    frozen: Boolean,
    loading: Boolean = false,
    totalAchievementsCount: Int,
    onChooseItem: (LifeRestartScanItem) -> Unit,
    onAutoStepYear: () -> Unit,
    onAdjustStatByTouch: (String, Int) -> Unit,
    onSpeakYearText: (String, () -> Unit) -> Unit,
    onMarkSpokenCompleted: (Int) -> Unit,
    onStopSpeech: () -> Unit
) {
    if (loading) {
        val c = LocalBciColors.current
        ReportActionHints(ActionHints("", "正在加载…", ""))
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .background(c.panel, RoundedCornerShape(22.dp))
                    .border(2.dp, c.line, RoundedCornerShape(22.dp))
                    .padding(horizontal = 42.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "正在加载人生重开模拟器…",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(800),
                    fontSize = 28.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "首次打开正在载入天赋与事件库",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 18.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
        return
    }

    val items = remember(state) { buildLifeRestartScanItems(state) }
    val focusedIndex = cursor.index.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val focusedLabel = items.getOrNull(focusedIndex)?.label.orEmpty()
    val scanDir = if (scanMode == ScanMode.AUTO) cursor.direction else 0

    ReportActionHints(ActionHints.list(focusedLabel, scanMode, cursor.direction))

    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnAutoStepYear by rememberUpdatedState(onAutoStepYear)
    val currentOnSpeakYearText by rememberUpdatedState(onSpeakYearText)
    val currentOnMarkSpokenCompleted by rememberUpdatedState(onMarkSpokenCompleted)
    val currentOnStopSpeech by rememberUpdatedState(onStopSpeech)
    val latestSpokenCompleted by rememberUpdatedState(state.spokenCompletedRevision)

    val dwellProgress = remember { Animatable(0f) }
    LaunchedEffect(focusedIndex, cursor.direction, dwellRevision, scanIntervalMs, scanMode, paused, frozen, lifecycleOwner) {
        when {
            frozen -> dwellProgress.snapTo(if (scanMode == ScanMode.AUTO) 0.5f else 0f)
            paused || scanMode != ScanMode.AUTO -> dwellProgress.snapTo(0f)
            else -> lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    // Stop game-owned speech when leaving the screen
    DisposableEffect(Unit) {
        onDispose { currentOnStopSpeech() }
    }

    // Optional Android TTS voice read-aloud: stops on pause/interruption/backgrounding and restarts cleanly on resume
    LaunchedEffect(state.phase, state.speakRevision, state.readAloud, state.narrationPaused, paused, frozen, lifecycleOwner) {
        if (state.phase != LifeRestartPhase.PLAYING || !state.readAloud || state.narrationPaused || paused || frozen || state.lastSpokenText.isBlank()) {
            currentOnStopSpeech()
            return@LaunchedEffect
        }
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val targetRev = state.speakRevision
            if (latestSpokenCompleted < targetRev) {
                try {
                    currentOnSpeakYearText(state.lastSpokenText) {
                        currentOnMarkSpokenCompleted(targetRev)
                    }
                    // Suspend until cancellation (pause / lifecycle stop / next year)
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    currentOnStopSpeech()
                }
            }
        }
    }

    // Automatic life chronicle playback: fixed 1.65s/year normally; waits for TTS utterance completion when read-aloud is active
    LaunchedEffect(state.phase, state.autoPlay, state.ended, state.readAloud, state.speakRevision, paused, frozen, lifecycleOwner) {
        if (state.phase == LifeRestartPhase.PLAYING && state.autoPlay && !state.ended && !paused && !frozen) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                if (state.readAloud) {
                    val targetRev = state.speakRevision
                    snapshotFlow { latestSpokenCompleted }.first { it >= targetRev }
                    delay(450L)
                    currentOnAutoStepYear()
                } else {
                    delay(1_650L)
                    currentOnAutoStepYear()
                }
            }
        }
    }

    val progressProvider: (Int) -> (() -> Float?)? = { idx ->
        if (idx == focusedIndex && scanMode == ScanMode.AUTO) ({ dwellProgress.value }) else null
    }

    when (state.phase) {
        LifeRestartPhase.PICK_TALENTS -> PickTalentsView(
            state = state,
            items = items,
            focusedIndex = focusedIndex,
            scanDir = scanDir,
            totalAchievementsCount = totalAchievementsCount,
            progressFor = progressProvider,
            onChoose = onChooseItem
        )

        LifeRestartPhase.ALLOCATE -> AllocateView(
            state = state,
            items = items,
            focusedIndex = focusedIndex,
            scanDir = scanDir,
            progressFor = progressProvider,
            onChoose = onChooseItem,
            onAdjustStatByTouch = onAdjustStatByTouch
        )

        LifeRestartPhase.PLAYING -> PlayingView(
            state = state,
            items = items,
            focusedIndex = focusedIndex,
            scanDir = scanDir,
            progressFor = progressProvider,
            onChoose = onChooseItem
        )

        LifeRestartPhase.SUMMARY -> SummaryView(
            state = state,
            items = items,
            focusedIndex = focusedIndex,
            scanDir = scanDir,
            totalAchievementsCount = totalAchievementsCount,
            progressFor = progressProvider,
            onChoose = onChooseItem
        )
    }
}

@Composable
private fun PickTalentsView(
    state: LifeRestartState,
    items: List<LifeRestartScanItem>,
    focusedIndex: Int,
    scanDir: Int,
    totalAchievementsCount: Int,
    progressFor: (Int) -> (() -> Float?)?,
    onChoose: (LifeRestartScanItem) -> Unit
) {
    val c = LocalBciColors.current
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "人生重开模拟器",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 32.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "挑选 3 个天赋（已选 ${state.selectedTalentIds.size} / ${LifeRestartRules.PICK_COUNT}）",
                    color = c.focus,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 21.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("第 ${state.profile.times + 1} 世")
                StatChip("成就 ${state.profile.achievements.size} / $totalAchievementsCount")
            }
        }

        // 2 rows × 5 columns of pulled talents
        val talentCount = state.pulledTalents.size
        val firstRowCount = (talentCount + 1) / 2
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (i in 0 until firstRowCount) {
                    val item = items[i] as LifeRestartScanItem.TalentOption
                    TalentPickCard(
                        talent = item.talent,
                        selected = item.talent.id in state.selectedTalentIds,
                        lockedFromPrev = item.talent.id == state.profile.lockedTalentId,
                        focused = i == focusedIndex,
                        scanDir = scanDir,
                        progress = progressFor(i),
                        onClick = { onChoose(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (i in firstRowCount until talentCount) {
                    val item = items[i] as LifeRestartScanItem.TalentOption
                    TalentPickCard(
                        talent = item.talent,
                        selected = item.talent.id in state.selectedTalentIds,
                        lockedFromPrev = item.talent.id == state.profile.lockedTalentId,
                        focused = i == focusedIndex,
                        scanDir = scanDir,
                        progress = progressFor(i),
                        onClick = { onChoose(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        // Bottom 3 action buttons
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(74.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            for (idx in talentCount until items.size) {
                val item = items[idx]
                val isPrimary = item is LifeRestartScanItem.PickAction &&
                    item.kind == LifeRestartScanItem.PickAction.Kind.CONFIRM
                ActionButtonCard(
                    label = item.label,
                    focused = idx == focusedIndex,
                    navigation = item.isNavigation,
                    emphasize = isPrimary && state.selectedTalentIds.size >= LifeRestartRules.PICK_COUNT,
                    scanDir = scanDir,
                    progress = progressFor(idx),
                    onClick = { onChoose(item) },
                    modifier = Modifier
                        .weight(if (isPrimary) 1.4f else 1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun TalentPickCard(
    talent: LifeTalent,
    selected: Boolean,
    lockedFromPrev: Boolean,
    focused: Boolean,
    scanDir: Int,
    progress: (() -> Float?)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    val rarityColor = rarityAccent(talent.grade)
    Column(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = scanDir,
                picked = selected,
                progress = progress,
                customBorderColor = if (selected) c.ok else rarityColor.copy(alpha = if (talent.grade == 0) 0.35f else 0.72f)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .background(rarityColor.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (lockedFromPrev) "继承·${LifeRestartRules.rarityLabel(talent.grade)}" else LifeRestartRules.rarityLabel(talent.grade),
                    color = rarityColor,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            if (selected) {
                Box(
                    modifier = Modifier
                        .background(c.ok, CircleShape)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "已选 ✓",
                        color = Color.White,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                text = talent.name,
                color = c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(800),
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = talent.description,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 16.sp,
                lineHeight = 22.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun AllocateView(
    state: LifeRestartState,
    items: List<LifeRestartScanItem>,
    focusedIndex: Int,
    scanDir: Int,
    progressFor: (Int) -> (() -> Float?)?,
    onChoose: (LifeRestartScanItem) -> Unit,
    onAdjustStatByTouch: (String, Int) -> Unit
) {
    val c = LocalBciColors.current
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Left: Chosen talents + 4 Attributes overview
        Column(
            modifier = Modifier
                .weight(0.95f)
                .fillMaxHeight()
                .background(c.panel, RoundedCornerShape(20.dp))
                .border(2.dp, c.line, RoundedCornerShape(20.dp))
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "调整初始属性",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 28.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Text(
                    text = "总点数 ${state.totalPoints}",
                    color = c.focus,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            // Selected talents chips
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "已携带天赋",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 16.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    state.activeTalents.take(4).forEach { t ->
                        val col = rarityAccent(t.grade)
                        Box(
                            modifier = Modifier
                                .background(col.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                                .border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = t.name,
                                color = c.text,
                                fontFamily = BciType.chinese,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                maxLines = 1,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
                if (state.replacementNotices.isNotEmpty()) {
                    Text(
                        text = state.replacementNotices.joinToString(" · "),
                        color = c.ok,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        maxLines = 2,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }

            // 4 stat bars (with touch +/- adjustment)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
            ) {
                val stats = listOf(
                    Triple("charm", "颜值", state.allocCharm),
                    Triple("intelligence", "智力", state.allocIntelligence),
                    Triple("strength", "体质", state.allocStrength),
                    Triple("money", "家境", state.allocMoney)
                )
                val maxBar = maxOf(LifeRestartRules.ALLOC_LIMIT, (state.totalPoints + 3) / 4).coerceAtLeast(10)
                stats.forEach { (key, label, value) ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = label,
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            modifier = Modifier.width(52.dp),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(22.dp)
                                .clip(RoundedCornerShape(11.dp))
                                .background(c.panel2)
                                .border(1.dp, c.line, RoundedCornerShape(11.dp))
                        ) {
                            val frac = (value.toFloat() / maxBar.toFloat()).coerceIn(0f, 1f)
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(frac)
                                    .background(c.focus, RoundedCornerShape(11.dp))
                            )
                        }
                        Text(
                            text = value.toString(),
                            color = c.text,
                            fontFamily = BciType.latin,
                            fontWeight = FontWeight(800),
                            fontSize = 26.sp,
                            modifier = Modifier.width(38.dp),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        // Subtle touch-only +/- controls
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MiniTouchStepButton("−") { onAdjustStatByTouch(key, -1) }
                            MiniTouchStepButton("+") { onAdjustStatByTouch(key, 1) }
                        }
                    }
                }
            }
        }

        // Right: Fast BCI presets + Start New Life
        Column(
            modifier = Modifier
                .weight(1.05f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Item 0: Start New Life hero button
            val startItem = items[0]
            ActionButtonCard(
                label = startItem.label,
                subtitle = "使用当前四维属性立即开启这一世",
                focused = focusedIndex == 0,
                navigation = false,
                emphasize = true,
                scanDir = scanDir,
                progress = progressFor(0),
                onClick = { onChoose(startItem) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(94.dp)
            )

            // 2×3 Preset grid (items 1..6)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (row in 0 until 2) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (col in 0 until 3) {
                            val idx = 1 + row * 3 + col
                            val item = items[idx] as LifeRestartScanItem.AllocPresetOption
                            ActionButtonCard(
                                label = item.preset.label,
                                subtitle = item.preset.subtitle,
                                focused = idx == focusedIndex,
                                navigation = false,
                                emphasize = false,
                                scanDir = scanDir,
                                progress = progressFor(idx),
                                onClick = { onChoose(item) },
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }

            // Bottom back & exit row (items 7..8)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (idx in 7 until items.size) {
                    val item = items[idx]
                    ActionButtonCard(
                        label = item.label,
                        focused = idx == focusedIndex,
                        navigation = item.isNavigation,
                        emphasize = false,
                        scanDir = scanDir,
                        progress = progressFor(idx),
                        onClick = { onChoose(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun PlayingView(
    state: LifeRestartState,
    items: List<LifeRestartScanItem>,
    focusedIndex: Int,
    scanDir: Int,
    progressFor: (Int) -> (() -> Float?)?,
    onChoose: (LifeRestartScanItem) -> Unit
) {
    val c = LocalBciColors.current
    val listState = rememberLazyListState()

    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.lastIndex)
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Left dashboard: Age + 5 live attributes + talents + BCI controls
        Column(
            modifier = Modifier
                .width(344.dp)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Age + live stats card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.panel, RoundedCornerShape(18.dp))
                    .border(2.dp, c.line, RoundedCornerShape(18.dp))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = state.currentProps.age.coerceAtLeast(0).toString(),
                            color = c.focus,
                            fontFamily = BciType.latin,
                            fontWeight = FontWeight(800),
                            fontSize = 42.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                        Text(
                            text = "岁",
                            color = c.text,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                            modifier = Modifier.padding(bottom = 5.dp),
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(if (state.ended) c.panel2 else c.okTint, CircleShape)
                            .border(1.dp, if (state.ended) c.line else c.ok, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (state.ended) "此生已尽" else if (state.autoPlay) "自动演绎中" else "已暂停",
                            color = if (state.ended) c.muted else c.ok,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }

                // 5 live stats row
                val p = state.currentProps
                val liveMetrics = listOf(
                    LifeRestartRules.judge("charm", p.charm),
                    LifeRestartRules.judge("intelligence", p.intelligence),
                    LifeRestartRules.judge("strength", p.strength),
                    LifeRestartRules.judge("money", p.money),
                    LifeRestartRules.judge("spirit", p.spirit)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    liveMetrics.forEach { metric ->
                        val col = rarityAccent(metric.grade)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .background(c.panel2, RoundedCornerShape(10.dp))
                                .border(1.dp, col.copy(alpha = if (metric.grade == 0) 0.25f else 0.7f), RoundedCornerShape(10.dp))
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text(
                                text = metric.label,
                                color = c.muted,
                                fontFamily = BciType.chinese,
                                fontSize = 13.sp,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                            Text(
                                text = metric.value.toString(),
                                color = c.text,
                                fontFamily = BciType.latin,
                                fontWeight = FontWeight(800),
                                fontSize = 21.sp,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }

                // Active talents strip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    state.activeTalents.take(3).forEach { t ->
                        val col = rarityAccent(t.grade)
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(col.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .padding(horizontal = 6.dp, vertical = 5.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = t.name,
                                color = c.text,
                                fontFamily = BciType.chinese,
                                fontWeight = FontWeight.Medium,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    }
                }
            }

            // BCI Control buttons
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items.forEachIndexed { idx, item ->
                    val emphasize = item is LifeRestartScanItem.PlayAction &&
                        item.kind == LifeRestartScanItem.PlayAction.Kind.SUMMARY
                    ActionButtonCard(
                        label = item.label,
                        focused = idx == focusedIndex,
                        navigation = item.isNavigation,
                        emphasize = emphasize,
                        scanDir = scanDir,
                        progress = progressFor(idx),
                        onClick = { onChoose(item) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    )
                }
            }
        }

        // Right column: Large readable life chronicle
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(c.panel, RoundedCornerShape(20.dp))
                .border(2.dp, c.line, RoundedCornerShape(20.dp))
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "人生历程",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 24.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                state.latestAchievementBanner?.let { ach ->
                    Box(
                        modifier = Modifier
                            .background(c.okTint, CircleShape)
                            .border(1.dp, c.ok, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "成就解锁 · ${ach.name}",
                            color = c.ok,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(state.logs, key = { idx, log -> "${idx}_${log.age}" }) { idx, log ->
                    YearChronicleCard(
                        log = log,
                        isLatest = idx == state.logs.lastIndex
                    )
                }
            }
        }
    }
}

@Composable
private fun YearChronicleCard(
    log: LifeYearLog,
    isLatest: Boolean
) {
    val c = LocalBciColors.current
    val maxGrade = maxOf(
        log.eventLines.maxOfOrNull { it.grade } ?: 0,
        log.talents.maxOfOrNull { it.grade } ?: 0,
        log.achievements.maxOfOrNull { it.grade } ?: 0
    )
    val accent = rarityAccent(maxGrade)
    val bg = when {
        isLatest -> c.focusTint
        maxGrade >= 2 -> accent.copy(alpha = 0.09f)
        else -> c.panel2
    }
    val borderCol = when {
        isLatest -> c.focus
        maxGrade >= 1 -> accent.copy(alpha = 0.55f)
        else -> c.line
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .border(if (isLatest) 2.dp else 1.dp, borderCol, RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .width(74.dp)
                .background(if (isLatest) c.focus else c.panel, RoundedCornerShape(10.dp))
                .border(1.dp, if (isLatest) c.focus else c.line, RoundedCornerShape(10.dp))
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "${log.age}岁",
                color = if (isLatest) c.bg else c.text,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Bold,
                fontSize = 19.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            log.talents.forEach { t ->
                val tColor = rarityAccent(t.grade)
                Text(
                    text = "【天赋】${t.name}：${t.description}",
                    color = tColor,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 25.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            log.eventLines.forEach { line ->
                Text(
                    text = line.text,
                    color = if (line.grade >= 2) rarityAccent(line.grade) else c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = if (isLatest || line.grade >= 1) FontWeight.SemiBold else FontWeight.Normal,
                    fontSize = 21.sp,
                    lineHeight = 30.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            log.achievements.forEach { ach ->
                Text(
                    text = "★ 解锁成就「${ach.name}」— ${ach.description}",
                    color = c.ok,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    lineHeight = 24.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun SummaryView(
    state: LifeRestartState,
    items: List<LifeRestartScanItem>,
    focusedIndex: Int,
    scanDir: Int,
    totalAchievementsCount: Int,
    progressFor: (Int) -> (() -> Float?)?,
    onChoose: (LifeRestartScanItem) -> Unit
) {
    val c = LocalBciColors.current
    val totalJudge = state.summaryJudges.firstOrNull { it.key == "summary" }
    val detailJudges = state.summaryJudges.filter { it.key != "summary" }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Header with big overall grade
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "人生总结",
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = 34.sp,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                totalJudge?.let { tj ->
                    val col = rarityAccent(tj.grade)
                    Box(
                        modifier = Modifier
                            .background(col.copy(alpha = 0.16f), CircleShape)
                            .border(2.dp, col, CircleShape)
                            .padding(horizontal = 18.dp, vertical = 7.dp)
                    ) {
                        Text(
                            text = "总评 ${tj.value} · ${tj.ratingText}",
                            color = col,
                            fontFamily = BciType.chinese,
                            fontWeight = FontWeight(800),
                            fontSize = 22.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatChip("第 ${state.profile.times + 1} 世")
                StatChip("成就 ${(state.profile.achievements + state.runAchievements.map { it.id }).size} / $totalAchievementsCount")
            }
        }

        // 6 rating tiles (2 rows × 3 cols)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            detailJudges.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    row.forEach { judge ->
                        SummaryJudgeCard(judge, Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }

        // Talent inheritance prompt + action bar
        val lockItems = items.withIndex().filter { it.value is LifeRestartScanItem.LockTalentOption }
        if (lockItems.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(74.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "带入下世",
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.width(82.dp),
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
                lockItems.forEach { (idx, item) ->
                    val lockOpt = item as LifeRestartScanItem.LockTalentOption
                    ActionButtonCard(
                        label = lockOpt.talent.name,
                        subtitle = if (lockOpt.locked) "已锁定 · 下世必出 ✓" else "点击锁定到下世",
                        focused = idx == focusedIndex,
                        navigation = false,
                        emphasize = lockOpt.locked,
                        scanDir = scanDir,
                        progress = progressFor(idx),
                        onClick = { onChoose(item) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }

        // Bottom Restart & Exit buttons
        val restartItem = items.first()
        val exitIndex = items.lastIndex
        val exitItem = items[exitIndex]
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(82.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ActionButtonCard(
                label = restartItem.label,
                focused = focusedIndex == 0,
                navigation = false,
                emphasize = true,
                scanDir = scanDir,
                progress = progressFor(0),
                onClick = { onChoose(restartItem) },
                modifier = Modifier
                    .weight(1.6f)
                    .fillMaxHeight()
            )
            ActionButtonCard(
                label = exitItem.label,
                focused = focusedIndex == exitIndex,
                navigation = true,
                emphasize = false,
                scanDir = scanDir,
                progress = progressFor(exitIndex),
                onClick = { onChoose(exitItem) },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun SummaryJudgeCard(
    judge: LifeJudgeResult,
    modifier: Modifier = Modifier
) {
    val c = LocalBciColors.current
    val col = rarityAccent(judge.grade)
    Row(
        modifier = modifier
            .background(c.panel, RoundedCornerShape(16.dp))
            .border(2.dp, col.copy(alpha = if (judge.grade == 0) 0.3f else 0.75f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = judge.label,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
            Text(
                text = judge.value.toString(),
                color = c.text,
                fontFamily = BciType.latin,
                fontWeight = FontWeight(800),
                fontSize = 36.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
        Box(
            modifier = Modifier
                .background(col.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            Text(
                text = judge.ratingText,
                color = col,
                fontFamily = BciType.chinese,
                fontWeight = FontWeight(800),
                fontSize = 24.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun ActionButtonCard(
    label: String,
    focused: Boolean,
    navigation: Boolean,
    emphasize: Boolean,
    scanDir: Int,
    progress: (() -> Float?)?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    val c = LocalBciColors.current
    Box(
        modifier = modifier
            .scanItem(
                focused = focused,
                direction = scanDir,
                picked = false,
                progress = progress,
                navigation = navigation,
                customBorderColor = if (emphasize && !focused) c.ok else null,
                customFillColor = if (emphasize && !focused) c.okTint else null
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (navigation) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_arrow_left),
                        contentDescription = null,
                        tint = if (focused) c.text else c.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = label,
                    color = when {
                        navigation && !focused -> c.muted
                        emphasize && !focused -> c.ok
                        else -> c.text
                    },
                    fontFamily = BciType.chinese,
                    fontWeight = if (focused || emphasize) FontWeight(800) else FontWeight.Bold,
                    fontSize = 22.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    val c = LocalBciColors.current
    Box(
        modifier = Modifier
            .background(c.panel, CircleShape)
            .border(1.dp, c.line, CircleShape)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            text = text,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun MiniTouchStepButton(symbol: String, onClick: () -> Unit) {
    val c = LocalBciColors.current
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(c.panel2)
            .border(1.dp, c.line, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = c.text,
            fontFamily = BciType.latin,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@Composable
private fun rarityAccent(grade: Int): Color {
    val c = LocalBciColors.current
    return when (grade) {
        1 -> Color(0xFF2B82D9) // Blue rare
        2 -> Color(0xFF9B51E0) // Purple epic
        3 -> Color(0xFFE68A1E) // Gold legendary
        else -> c.muted
    }
}

package com.example.input_ds.ui.communication

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSide
import com.example.input_ds.model.SelectionItem
import com.example.input_ds.model.SelectionItemAction
import com.example.input_ds.model.SelectionPaging
import com.example.input_ds.ui.design.scanItem
import com.example.input_ds.ui.theme.BciType
import com.example.input_ds.ui.theme.LocalBciColors
import kotlinx.coroutines.delay

/**
 * 统一输入界面（当面说话 / 文字消息 / 生活助手 三种模式共用）
 */
@Composable
fun ComposerScreen(
    state: InputState,
    phrases: List<String>,
    paused: Boolean,
    frozen: Boolean,
    compactWings: Boolean = state.mode is InputMode.Chat,
    onBlockClick: (Int) -> Unit,
    onPinyinClick: (String) -> Unit,
    onPinyinNavigationClick: (Int) -> Unit,
    onCharacterClick: (String) -> Unit,
    onCharacterItemClick: (Int) -> Unit,
    onCommonPhraseClick: (String) -> Unit,
    onPredictionClick: (Int) -> Unit,
    onInitialPredictionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val narrow = state.phase != InputPhase.LEVEL_1_SCANNING
    val targetWingWidth = when {
        compactWings && narrow -> 110.dp
        compactWings -> 200.dp
        narrow -> 132.dp
        else -> 270.dp
    }
    val wingWidth by animateDpAsState(
        targetValue = targetWingWidth,
        animationSpec = tween(if (frozen) 0 else 250, easing = FastOutSlowInEasing),
        label = "composerWingWidth"
    )

    val level1Active = state.phase == InputPhase.LEVEL_1_SCANNING
    val dwellAuto = level1Active || state.scanMode == ScanMode.AUTO
    val dwellProgress = remember { Animatable(0f) }
    val dwellKey = when {
        level1Active -> "L1:${state.scanSide}:${state.highlightedBlockIndex}"
        state.phase == InputPhase.LEVEL_2_LETTER_SELECT && !state.isCharFocused -> "PY:${state.highlightedPinyinIndex}"
        state.phase == InputPhase.LEVEL_2_LETTER_SELECT || state.phase == InputPhase.LEVEL_3_CHAR_SELECT -> "CH:${state.charPage}:${state.highlightedCharIndex}"
        state.phase == InputPhase.PREDICTION -> "PR:${state.isCommonPhraseSelection}:${state.predictionPage}:${state.highlightedPredictionIndex}"
        else -> "IN:${state.initialPage}:${state.highlightedInitialIndex}"
    }
    LaunchedEffect(dwellKey, state.dwellRevision, state.scanIntervalMs, dwellAuto, paused, frozen) {
        when {
            frozen -> dwellProgress.snapTo(if (dwellAuto) 0.5f else 0f)
            paused || !dwellAuto -> dwellProgress.snapTo(0f)
            else -> {
                dwellProgress.snapTo(0f)
                dwellProgress.animateTo(1f, tween(state.scanIntervalMs.toInt(), easing = LinearEasing))
            }
        }
    }

    val progressProvider: () -> Float? = { if (dwellAuto) dwellProgress.value else null }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ComposerOutputBar(state = state, frozen = frozen)

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            WingColumn(
                blocks = LetterBlockMapping.LEFT_BLOCKS,
                side = ScanSide.LEFT,
                state = state,
                narrow = narrow,
                compactWings = compactWings,
                progress = progressProvider,
                onBlockClick = onBlockClick,
                modifier = Modifier
                    .width(if (frozen) targetWingWidth else wingWidth)
                    .fillMaxHeight()
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                MagnifierBox(
                    state = state,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(178.dp)
                )
                CandidatesBox(
                    state = state,
                    phrases = phrases,
                    progress = progressProvider,
                    onPinyinClick = onPinyinClick,
                    onPinyinNavigationClick = onPinyinNavigationClick,
                    onCharacterClick = onCharacterClick,
                    onCharacterItemClick = onCharacterItemClick,
                    onCommonPhraseClick = onCommonPhraseClick,
                    onPredictionClick = onPredictionClick,
                    onInitialPredictionClick = onInitialPredictionClick,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            }

            WingColumn(
                blocks = LetterBlockMapping.RIGHT_BLOCKS,
                side = ScanSide.RIGHT,
                state = state,
                narrow = narrow,
                compactWings = compactWings,
                progress = progressProvider,
                onBlockClick = onBlockClick,
                modifier = Modifier
                    .width(if (frozen) targetWingWidth else wingWidth)
                    .fillMaxHeight()
            )
        }
    }
}

@Composable
private fun ComposerOutputBar(state: InputState, frozen: Boolean) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(18.dp)
    var caretVisible by remember { mutableStateOf(true) }
    LaunchedEffect(frozen) {
        if (frozen) {
            caretVisible = true
            return@LaunchedEffect
        }
        while (true) {
            delay(550L)
            caretVisible = !caretVisible
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(104.dp)
            .shadow(if (c.bg.luminance() > 0.5f) 2.dp else 0.dp, shape, clip = false)
            .background(c.panel, shape)
            .border(2.dp, c.line, shape)
            .padding(horizontal = 26.dp),
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        state.mode.pillLabel?.let { pill ->
            Box(
                modifier = Modifier
                    .border(1.dp, c.line, CircleShape)
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    text = pill,
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 17.sp,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (state.outputText.isEmpty()) Arrangement.Start else Arrangement.End
        ) {
            if (state.outputText.isEmpty()) {
                if (state.selectedBlocks.isEmpty()) {
                    Text(
                        text = state.mode.placeholder,
                        modifier = Modifier.weight(1f),
                        color = c.dim,
                        fontFamily = BciType.chinese,
                        fontSize = 24.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            } else {
                Text(
                    text = state.outputText,
                    modifier = Modifier.weight(1f, fill = false),
                    color = c.text,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(500),
                    fontSize = 44.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
            Spacer(Modifier.width(4.dp))
            Spacer(
                Modifier
                    .width(3.dp)
                    .height(48.dp)
                    .alpha(if (caretVisible) 1f else 0f)
                    .background(c.focus)
            )
        }

        if (state.selectedBlocks.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                state.selectedBlocks.forEach { block ->
                    Box(
                        modifier = Modifier
                            .border(1.dp, c.line, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = LetterBlockMapping.labelFor(block),
                            color = c.muted,
                            fontFamily = BciType.latin,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
                Text(
                    text = "→",
                    color = c.dim,
                    fontFamily = BciType.latin,
                    fontSize = 22.sp
                )
                val previewPy = when {
                    state.pinyinCandidates.isNotEmpty() -> state.pinyinCandidates.take(4).joinToString(" · ")
                    state.selectedBlocks.size < 6 -> "继续…"
                    else -> "无拼音"
                }
                Text(
                    text = previewPy,
                    color = c.focus,
                    fontFamily = if (state.pinyinCandidates.isNotEmpty()) BciType.latin else BciType.chinese,
                    fontWeight = FontWeight(800),
                    fontSize = 38.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun WingColumn(
    blocks: List<Int>,
    side: ScanSide,
    state: InputState,
    narrow: Boolean,
    compactWings: Boolean,
    progress: () -> Float?,
    onBlockClick: (Int) -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    val wingAlpha = when {
        narrow -> 0.3f
        state.scanSide == side -> 1f
        else -> 0.5f
    }
    val talkMode = state.mode is InputMode.Talk

    Column(
        modifier = modifier.alpha(wingAlpha),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        blocks.forEachIndexed { index, block ->
            val focused = !narrow && state.scanSide == side && state.highlightedBlockIndex == index
            val isFn = LetterBlockMapping.isFunctionBlock(block)
            val isSend = block == LetterBlockMapping.SEND_BLOCK
            val isBack = block == LetterBlockMapping.BACK_BLOCK
            val picked = state.flashedBlock == block
            val label = LetterBlockMapping.labelFor(block, talkMode)
            val orders = state.selectedBlocks.mapIndexedNotNull { k, d -> if (d == block) k + 1 else null }

            val customBorder = when {
                isSend && talkMode -> c.ok
                isSend -> c.focus
                else -> null
            }
            val textColor = when {
                isSend && talkMode -> c.ok
                isSend -> c.focus
                isBack -> c.muted
                else -> c.text
            }
            val fontSize = when {
                isFn && narrow -> 18.sp
                isFn -> 28.sp
                compactWings && narrow -> 18.sp
                compactWings -> 30.sp
                narrow -> 20.sp
                else -> 36.sp
            }
            val letterSpacing = when {
                isFn -> 0.1.em
                narrow -> 0.06.em
                compactWings -> 0.1.em
                else -> 0.14.em
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .scanItem(
                        focused = focused,
                        direction = 0,
                        picked = picked,
                        progress = if (focused) progress else null,
                        navigation = isFn,
                        customBorderColor = customBorder,
                        customFillColor = if (isFn) c.panel else null,
                        showShadow = !isFn
                    )
                    .clickable { onBlockClick(block) }
            ) {
                if (orders.isNotEmpty()) {
                    Text(
                        text = orders.joinToString("·"),
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(start = 10.dp, top = 6.dp),
                        color = c.focus,
                        fontFamily = BciType.latin,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
                Text(
                    text = label,
                    modifier = Modifier.align(Alignment.Center),
                    color = textColor,
                    fontFamily = if (isFn) BciType.chinese else BciType.latin,
                    fontWeight = FontWeight.Bold,
                    fontSize = fontSize,
                    letterSpacing = letterSpacing,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }
        }
    }
}

@Composable
private fun MagnifierBox(state: InputState, modifier: Modifier) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .background(c.mag, shape)
            .border(2.dp, c.line, shape)
            .clip(shape)
    ) {
        when (state.phase) {
            InputPhase.LEVEL_1_SCANNING -> {
                val blocks = if (state.scanSide == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
                val block = blocks.getOrNull(state.highlightedBlockIndex) ?: 2
                val isFn = LetterBlockMapping.isFunctionBlock(block)
                val talkMode = state.mode is InputMode.Talk
                val big = if (isFn) LetterBlockMapping.labelFor(block, talkMode)
                else LetterBlockMapping.labelFor(block).toCharArray().joinToString(" ")

                Text(
                    text = big,
                    modifier = Modifier.align(Alignment.Center),
                    color = c.focus,
                    fontFamily = if (isFn) BciType.chinese else BciType.latin,
                    fontWeight = FontWeight(if (isFn) 900 else 800),
                    fontSize = if (isFn) 76.sp else 92.sp,
                    letterSpacing = if (isFn) 0.06.em else 0.18.em,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            InputPhase.LEVEL_2_LETTER_SELECT -> if (!state.isCharFocused) {
                val pyIdx = state.highlightedPinyinIndex
                val isBack = pyIdx == 0
                val isInitial = state.hasInitialPredictionOption && pyIdx == state.pinyinCombinations.size + 1
                val pinyin = state.pinyinCombinations.getOrNull(pyIdx - 1)
                val label = when {
                    isBack -> "返回"
                    isInitial -> "首字母整句"
                    else -> pinyin.orEmpty()
                }
                val isLatin = !isBack && !isInitial
                val previewChars = if (isLatin) state.charCandidates.take(10).joinToString("  ") else ""
                Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = label,
                        color = c.focus,
                        fontFamily = if (isLatin) BciType.latin else BciType.chinese,
                        fontWeight = FontWeight(if (isLatin) 800 else 900),
                        fontSize = if (isLatin) 92.sp else 76.sp,
                        letterSpacing = if (isLatin) 0.18.em else 0.06.em,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                    if (previewChars.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            text = previewChars,
                            color = c.muted,
                            fontFamily = BciType.chinese,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                        )
                    }
                }
            } else {
                val items = SelectionPaging.characterItems(state.charCandidates, state.charPage)
                val item = items.getOrNull(state.highlightedCharIndex)
                val isChar = item?.action == SelectionItemAction.SELECT
                if (state.currentPinyin.isNotEmpty()) MagTag(state.currentPinyin)
                Text(
                    text = item?.label.orEmpty(),
                    modifier = Modifier.align(Alignment.Center),
                    color = c.focus,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = if (isChar) 118.sp else 76.sp,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            InputPhase.LEVEL_3_CHAR_SELECT -> {
                val items = SelectionPaging.characterItems(state.charCandidates, state.charPage)
                val item = items.getOrNull(state.highlightedCharIndex)
                val isChar = item?.action == SelectionItemAction.SELECT
                if (state.currentPinyin.isNotEmpty()) MagTag(state.currentPinyin)
                Text(
                    text = item?.label.orEmpty(),
                    modifier = Modifier.align(Alignment.Center),
                    color = c.focus,
                    fontFamily = BciType.chinese,
                    fontWeight = FontWeight(900),
                    fontSize = if (isChar) 118.sp else 76.sp,
                    maxLines = 1,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            }

            InputPhase.PREDICTION, InputPhase.INITIAL_PREDICTION -> {
                val item = when {
                    state.phase == InputPhase.INITIAL_PREDICTION ->
                        SelectionPaging.initialSentenceItems(state.initialCandidates, state.initialPage)
                            .getOrNull(state.highlightedInitialIndex)
                    state.isCommonPhraseSelection ->
                        SelectionPaging.commonPhraseItems(state.predictionCandidates, state.predictionPage)
                            .getOrNull(state.highlightedPredictionIndex)
                    else ->
                        SelectionPaging.predictionItems(state.predictionCandidates, state.mode.sendLabel)
                            .getOrNull(state.highlightedPredictionIndex)
                }
                if (state.phase == InputPhase.INITIAL_PREDICTION && state.initialBlocksKey.isNotEmpty()) {
                    MagTag(state.initialBlocksKey)
                }
                if (item?.action == SelectionItemAction.SELECT) {
                    val tailPrefix = if (state.outputText.length > 10) "…" + state.outputText.takeLast(10) else state.outputText
                    Text(
                        text = buildAnnotatedString {
                            append(tailPrefix)
                            withStyle(SpanStyle(color = c.focus, fontWeight = FontWeight(900))) {
                                append(item.label)
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 30.dp),
                        color = c.text,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(500),
                        fontSize = 50.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                } else {
                    Text(
                        text = item?.label.orEmpty(),
                        modifier = Modifier.align(Alignment.Center),
                        color = c.focus,
                        fontFamily = BciType.chinese,
                        fontWeight = FontWeight(900),
                        fontSize = 76.sp,
                        maxLines = 1,
                        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                    )
                }
            }
        }
    }
}

@Composable
private fun BoxScope.MagTag(text: String) {
    val c = LocalBciColors.current
    Text(
        text = text,
        modifier = Modifier
            .align(Alignment.TopStart)
            .padding(start = 18.dp, top = 12.dp),
        color = c.muted,
        fontFamily = BciType.chinese,
        fontSize = 18.sp,
        style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
    )
}

@Composable
private fun CandidatesBox(
    state: InputState,
    phrases: List<String>,
    progress: () -> Float?,
    onPinyinClick: (String) -> Unit,
    onPinyinNavigationClick: (Int) -> Unit,
    onCharacterClick: (String) -> Unit,
    onCharacterItemClick: (Int) -> Unit,
    onCommonPhraseClick: (String) -> Unit,
    onPredictionClick: (Int) -> Unit,
    onInitialPredictionClick: (Int) -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    val shape = RoundedCornerShape(18.dp)
    val dir = if (state.scanMode == ScanMode.AUTO) state.charScanDirection else 0

    Column(
        modifier = modifier
            .shadow(if (c.bg.luminance() > 0.5f) 2.dp else 0.dp, shape, clip = false)
            .background(c.panel, shape)
            .border(2.dp, c.line, shape)
            .padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (state.phase) {
            InputPhase.LEVEL_1_SCANNING -> {
                if (state.selectedBlocks.isNotEmpty()) {
                    CapRow(
                        left = "候选",
                        right = state.selectedBlocks.joinToString(" + ") { LetterBlockMapping.labelFor(it) }
                    )
                    if (state.pinyinCandidates.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
                            state.pinyinCandidates.take(5).forEach { py ->
                                Text(
                                    text = py,
                                    modifier = Modifier.clickable { onPinyinClick(py) },
                                    color = c.focus,
                                    fontFamily = BciType.latin,
                                    fontWeight = FontWeight(800),
                                    fontSize = 58.sp,
                                    lineHeight = 58.sp,
                                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                                )
                            }
                        }
                    }
                    if (state.charCandidates.isNotEmpty()) {
                        PreviewCharsWrap(
                            chars = state.charCandidates.take(16),
                            onCharacterClick = onCharacterClick,
                            modifier = Modifier.weight(1f).fillMaxWidth()
                        )
                    } else if (state.pinyinCandidates.isEmpty()) {
                        Text(
                            text = "无匹配汉字",
                            color = c.dim,
                            fontFamily = BciType.chinese,
                            fontSize = 24.sp
                        )
                    }
                } else {
                    CapRow(left = "常用语", right = "")
                    val topSix = phrases.take(6)
                    Column(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (row in 0..1) {
                            Row(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                for (col in 0..2) {
                                    val phrase = topSix.getOrNull(row * 3 + col)
                                    if (phrase != null) {
                                        DashedPhrasePreviewTile(
                                            text = phrase,
                                            onClick = { onCommonPhraseClick(phrase) },
                                            modifier = Modifier.weight(1f).fillMaxHeight()
                                        )
                                    } else {
                                        Spacer(Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            InputPhase.LEVEL_2_LETTER_SELECT -> if (!state.isCharFocused) {
                val items = buildList {
                    add(SelectionItem("返回", SelectionItemAction.BACK))
                    state.pinyinCombinations.forEachIndexed { i, py ->
                        add(SelectionItem(py, SelectionItemAction.SELECT, i))
                    }
                    if (state.hasInitialPredictionOption) {
                        add(SelectionItem("首字母整句", SelectionItemAction.CONTINUE_INPUT))
                    }
                }
                val rows = ((items.size + 4) / 5).coerceIn(1, 3)
                CandidateGrid(
                    items = items,
                    columns = 5,
                    rows = rows,
                    focusedIndex = state.highlightedPinyinIndex,
                    direction = dir,
                    progress = progress,
                    cellFontSize = 44,
                    latinCell = true,
                    onClick = { idx, item ->
                        when (item.action) {
                            SelectionItemAction.BACK -> onPinyinNavigationClick(0)
                            SelectionItemAction.SELECT -> onPinyinClick(item.label)
                            else -> onPinyinNavigationClick(state.pinyinCombinations.size + 1)
                        }
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                val preview = if (state.highlightedPinyinIndex in 1..state.pinyinCombinations.size) {
                    state.charCandidates.take(12).joinToString("  ")
                } else ""
                Text(
                    text = preview,
                    color = c.muted,
                    fontFamily = BciType.chinese,
                    fontSize = 28.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                )
            } else {
                val items = SelectionPaging.characterItems(state.charCandidates, state.charPage)
                CapRow(left = "选字", right = "第 ${state.charPage + 1} 页")
                CandidateGrid(
                    items = items,
                    columns = 6,
                    rows = 2,
                    focusedIndex = state.highlightedCharIndex,
                    direction = dir,
                    progress = progress,
                    cellFontSize = 40,
                    latinCell = false,
                    onClick = { idx, _ -> onCharacterItemClick(idx) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            InputPhase.LEVEL_3_CHAR_SELECT -> {
                val items = SelectionPaging.characterItems(state.charCandidates, state.charPage)
                CapRow(left = "选字", right = "第 ${state.charPage + 1} 页")
                CandidateGrid(
                    items = items,
                    columns = 6,
                    rows = 2,
                    focusedIndex = state.highlightedCharIndex,
                    direction = dir,
                    progress = progress,
                    cellFontSize = 40,
                    latinCell = false,
                    onClick = { idx, _ -> onCharacterItemClick(idx) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }

            InputPhase.PREDICTION -> {
                if (state.isCommonPhraseSelection) {
                    val urgentSet = if (state.mode.usesAssistantPhrases) emptySet() else CharacterDictionary.URGENT_PHRASE_SET
                    val items = SelectionPaging.commonPhraseItems(state.predictionCandidates, state.predictionPage, urgentSet)
                    CapRow(left = "常用语", right = "第 ${state.predictionPage + 1} 页")
                    CandidateGrid(
                        items = items,
                        columns = 4,
                        rows = 3,
                        focusedIndex = state.highlightedPredictionIndex,
                        direction = dir,
                        progress = progress,
                        cellFontSize = 27,
                        latinCell = false,
                        onClick = { idx, _ -> onPredictionClick(idx) },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                } else {
                    val items = SelectionPaging.predictionItems(state.predictionCandidates, state.mode.sendLabel)
                    val rows = if (items.size > 8) 3 else 2
                    CapRow(left = "联想词", right = "")
                    CandidateGrid(
                        items = items,
                        columns = 4,
                        rows = rows,
                        focusedIndex = state.highlightedPredictionIndex,
                        direction = dir,
                        progress = progress,
                        cellFontSize = 34,
                        latinCell = false,
                        onClick = { idx, _ -> onPredictionClick(idx) },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }

            InputPhase.INITIAL_PREDICTION -> {
                val items = SelectionPaging.initialSentenceItems(state.initialCandidates, state.initialPage)
                CapRow(left = "整句候选", right = "第 ${state.initialPage + 1} 页")
                CandidateGrid(
                    items = items,
                    columns = 4,
                    rows = 3,
                    focusedIndex = state.highlightedInitialIndex,
                    direction = dir,
                    progress = progress,
                    cellFontSize = 24,
                    latinCell = false,
                    onClick = { idx, _ -> onInitialPredictionClick(idx) },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun CapRow(left: String, right: String) {
    val c = LocalBciColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = left,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 17.sp,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
        if (right.isNotEmpty()) {
            Text(
                text = right,
                color = c.muted,
                fontFamily = BciType.chinese,
                fontSize = 17.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun DashedPhrasePreviewTile(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                val stroke = 1.dp.toPx()
                drawRoundRect(
                    color = c.line,
                    topLeft = Offset(stroke / 2f, stroke / 2f),
                    size = Size(size.width - stroke, size.height - stroke),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                    style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())))
                )
            }
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = c.muted,
            fontFamily = BciType.chinese,
            fontSize = 24.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCharsWrap(
    chars: List<String>,
    onCharacterClick: (String) -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chars.forEach { ch ->
            Text(
                text = ch,
                modifier = Modifier.clickable { onCharacterClick(ch) },
                color = c.text,
                fontFamily = BciType.chinese,
                fontSize = 34.sp,
                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
            )
        }
    }
}

@Composable
private fun CandidateGrid(
    items: List<SelectionItem>,
    columns: Int,
    rows: Int,
    focusedIndex: Int,
    direction: Int,
    progress: () -> Float?,
    cellFontSize: Int,
    latinCell: Boolean,
    onClick: (Int, SelectionItem) -> Unit,
    modifier: Modifier
) {
    val c = LocalBciColors.current
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (row in 0 until rows) {
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (col in 0 until columns) {
                    val index = row * columns + col
                    val item = items.getOrNull(index)
                    if (item != null) {
                        val focused = index == focusedIndex
                        val isNav = item.action != SelectionItemAction.SELECT
                        val urgent = item.urgent
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .scanItem(
                                    focused = focused,
                                    direction = direction,
                                    picked = false,
                                    progress = if (focused) progress else null,
                                    navigation = isNav,
                                    customFillColor = if (!isNav) c.panel2 else null,
                                    urgentBar = urgent,
                                    showShadow = false
                                )
                                .clickable { onClick(index, item) }
                                .padding(horizontal = if (urgent) 12.dp else 4.dp, vertical = 4.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = item.label,
                                color = when {
                                    urgent -> c.alarm
                                    isNav && !focused -> c.muted
                                    else -> c.text
                                },
                                fontFamily = if (!isNav && latinCell) BciType.latin else BciType.chinese,
                                fontWeight = when {
                                    urgent -> FontWeight.Bold
                                    !isNav && latinCell -> FontWeight.Bold
                                    isNav -> FontWeight(500)
                                    else -> FontWeight.Normal
                                },
                                fontSize = (if (isNav) 21 else cellFontSize).sp,
                                lineHeight = ((if (isNav) 21 else cellFontSize) * 1.15f).sp,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = TextStyle(platformStyle = PlatformTextStyle(includeFontPadding = false))
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

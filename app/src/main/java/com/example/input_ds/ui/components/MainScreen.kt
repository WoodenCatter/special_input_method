package com.example.input_ds.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanSide
import com.example.input_ds.model.SelectionItemAction
import com.example.input_ds.model.SelectionPaging
import com.example.input_ds.ui.theme.*

/**
 * 主输入界面
 *
 * 布局对应 PRD 中的扫描界面设计：
 * - 左侧 4 个字母块
 * - 中间区域 1（放大字母 + 左/右指示）
 * - 中间区域 2（拼音/汉字候选）
 * - 右侧 4 个字母块
 */
@Composable
fun MainScreen(
    state: InputState,
    onBlockClick: (Int) -> Unit,
    onPinyinClick: (String) -> Unit,
    onPinyinNavigationClick: (Int) -> Unit,
    onCharacterClick: (String) -> Unit,
    onCommonPhraseClick: (String) -> Unit,
    onPredictionClick: (Int) -> Unit,
    onInitialPredictionClick: (Int) -> Unit
) {
    GlassPanel(
        modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 4.dp),
        contentPadding = PaddingValues(12.dp)
    ) {
        // === 顶部输出区域 ===
        OutputTextArea(state.outputText)

        Spacer(modifier = Modifier.height(10.dp))

        // === 中间主交互区域 ===
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 左侧字母块
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                LetterBlockMapping.LEFT_BLOCKS.forEachIndexed { index, block ->
                    LetterBlockItem(
                        label = LetterBlockMapping.DIGIT_LABELS[block] ?: "",
                        isHighlighted = state.phase == InputPhase.LEVEL_1_SCANNING
                                && state.scanSide == ScanSide.LEFT
                                && state.highlightedBlockIndex == index,
                        onClick = { onBlockClick(block) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 中间区域
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 区域1：放大显示 + 左右指示
                Region1(
                    state = state,
                    onPinyinClick = onPinyinClick,
                    onPinyinNavigationClick = onPinyinNavigationClick,
                    modifier = Modifier.weight(1f)
                )

                // 区域2：拼音/汉字候选
                Region2(
                    state = state,
                    onPinyinClick = onPinyinClick,
                    onPinyinNavigationClick = onPinyinNavigationClick,
                    onCharacterClick = onCharacterClick,
                    onCommonPhraseClick = onCommonPhraseClick,
                    onPredictionClick = onPredictionClick,
                    onInitialPredictionClick = onInitialPredictionClick,
                    modifier = Modifier.weight(1.5f)
                )
            }

            // 右侧字母块
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                LetterBlockMapping.RIGHT_BLOCKS.forEachIndexed { index, block ->
                    LetterBlockItem(
                        label = LetterBlockMapping.DIGIT_LABELS[block] ?: "",
                        isHighlighted = state.phase == InputPhase.LEVEL_1_SCANNING
                                && state.scanSide == ScanSide.RIGHT
                                && state.highlightedBlockIndex == index,
                        onClick = { onBlockClick(block) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

    }
}

/**
 * 字母块组件
 */
@Composable
fun LetterBlockItem(
    label: String,
    isHighlighted: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> AuroraDarkAccentSoft
            else -> SurfaceDark
        },
        animationSpec = tween(180),
        label = "blockColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> HighlightYellow
            else -> BlockBorder
        },
        animationSpec = tween(180),
        label = "borderColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(1.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(bgColor)
            .border(
                1.dp,
                borderColor,
                RoundedCornerShape(12.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = if (isHighlighted) 19.sp else 16.sp,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlighted) AuroraVioletBright else TextWhite
        )
    }
}

/**
 * 汉字网格中的单个格子（含下一页导航）
 */
@Composable
fun GridCharBox(
    isHighlighted: Boolean,
    text: String,
    highlightedSize: androidx.compose.ui.unit.TextUnit,
    normalSize: androidx.compose.ui.unit.TextUnit,
    isNav: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isHighlighted && isNav -> HighlightYellow.copy(alpha = 0.6f)
        isHighlighted -> HighlightYellow.copy(alpha = 0.5f)
        isNav -> HighlightOrange.copy(alpha = 0.15f)
        else -> Color.Transparent
    }
    val borderColor = when {
        isHighlighted -> HighlightYellow
        isNav -> HighlightOrange.copy(alpha = 0.6f)
        else -> Color.Transparent
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = if (isHighlighted) highlightedSize else normalSize,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = when {
                isHighlighted -> Color.Black
                isNav -> HighlightOrange
                else -> TextWhite
            }
        )
    }
}

/**
 * 区域1：放大显示当前字母 + 左/右提示
 */
@Composable
fun Region1(
    state: InputState,
    onPinyinClick: (String) -> Unit,
    onPinyinNavigationClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BlockBorder, RoundedCornerShape(16.dp))
            .padding(6.dp),
        contentAlignment = Alignment.Center
    ) {
        when (state.phase) {
            InputPhase.LEVEL_1_SCANNING -> {
                // 显示当前高亮块的字母放大
                val sideBlocks = if (state.scanSide == ScanSide.LEFT) {
                    LetterBlockMapping.LEFT_BLOCKS
                } else {
                    LetterBlockMapping.RIGHT_BLOCKS
                }
                val currentBlock = sideBlocks.getOrNull(state.highlightedBlockIndex)

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    // 左/右指示
                    Text(
                        text = if (state.scanSide == ScanSide.LEFT) "← 左侧扫描" else "右侧扫描 →",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    // 放大字母
                    if (currentBlock != null) {
                        val letters = LetterBlockMapping.DIGIT_LABELS[currentBlock]
                            ?.let { label ->
                                if (currentBlock in LetterBlockMapping.ALL_BLOCKS) {
                                    label.toCharArray().joinToString(" ")
                                } else label
                            }
                            .orEmpty()
                        Text(
                            text = letters,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighlightYellow
                        )
                    }
                }
            }
            InputPhase.LEVEL_2_LETTER_SELECT -> {
                // 始终显示拼音组合（咬牙后不跳转，Region2 切换为汉字网格）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "选择拼音",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        val returnHighlighted = state.highlightedPinyinIndex == 0
                        Text(
                            text = "返回",
                            modifier = Modifier.clickable { onPinyinNavigationClick(0) },
                            fontSize = if (returnHighlighted) 30.sp else 22.sp,
                            fontWeight = if (returnHighlighted) FontWeight.Bold else FontWeight.Normal,
                            color = if (returnHighlighted) HighlightYellow else HighlightOrange
                        )
                        state.pinyinCombinations.forEachIndexed { index, pinyin ->
                            val isHighlighted = index + 1 == state.highlightedPinyinIndex
                            Text(
                                text = pinyin,
                                modifier = Modifier.clickable { onPinyinClick(pinyin) },
                                fontSize = if (isHighlighted) 30.sp else 22.sp,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted) HighlightYellow else TextWhite
                            )
                        }
                        if (state.hasInitialPredictionOption) {
                            val isHighlighted = state.highlightedPinyinIndex ==
                                state.pinyinCombinations.size + 1
                            Text(
                                text = "首字母",
                                modifier = Modifier.clickable {
                                    onPinyinNavigationClick(state.pinyinCombinations.size + 1)
                                },
                                fontSize = if (isHighlighted) 30.sp else 22.sp,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted) HighlightYellow else HighlightOrange
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (!state.isCharFocused) "← 左看/右看 → 切换"
                               else "咬定拼音 · 下方选字",
                        fontSize = 10.sp,
                        color = TextGray
                    )
                }
            }
            InputPhase.LEVEL_3_CHAR_SELECT -> {
                // 显示当前拼音和正在选择的字
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = state.currentPinyin,
                        fontSize = 18.sp,
                        color = TextGray
                    )
                    Text(
                        text = "选择汉字",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    if (state.charCandidates.isNotEmpty()) {
                        val idx = state.highlightedCharIndex
                        if (idx < state.charCandidates.size) {
                            Text(
                                text = state.charCandidates[idx],
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighlightYellow
                            )
                            val dirText = if (state.charScanDirection >= 0) "→ 自动循环中" else "← 自动循环中"
                            Text(
                                text = dirText,
                                fontSize = 10.sp,
                                color = TextGray
                            )
                        }
                    }
                }
            }
            InputPhase.PREDICTION -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = if (state.isCommonPhraseSelection) "常用语" else "预测词",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    val items = if (state.isCommonPhraseSelection) {
                        SelectionPaging.commonPhraseItems(state.predictionCandidates, state.predictionPage)
                    } else {
                        SelectionPaging.predictionItems(state.predictionCandidates)
                    }
                    if (items.isNotEmpty()) {
                        val idx = state.highlightedPredictionIndex
                        if (idx < items.size) {
                            Text(
                                text = items[idx].label,
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                                color = HighlightYellow
                            )
                        }
                    }
                }
            }
            InputPhase.INITIAL_PREDICTION -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "首字母整句 · ${state.initialBlocksKey}",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    val items = SelectionPaging.initialSentenceItems(
                        state.initialCandidates,
                        state.initialPage
                    )
                    items.getOrNull(state.highlightedInitialIndex)?.let { item ->
                        Text(
                            text = item.label,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = HighlightYellow
                        )
                    }
                }
            }
        }
    }
}

/**
 * 区域2：拼音候选 / 汉字候选 / 预测词 显示
 */
@Composable
fun Region2(
    state: InputState,
    onPinyinClick: (String) -> Unit,
    onPinyinNavigationClick: (Int) -> Unit,
    onCharacterClick: (String) -> Unit,
    onCommonPhraseClick: (String) -> Unit,
    onPredictionClick: (Int) -> Unit,
    onInitialPredictionClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(1.dp, BlockBorder, RoundedCornerShape(16.dp))
            .padding(6.dp)
    ) {
        when (state.phase) {
            InputPhase.LEVEL_1_SCANNING -> {
                Column {
                    // 上半部分：有完整拼音时维持原显示；否则回显已选字母块，避免空白。
                    if (state.pinyinCandidates.isNotEmpty()) {
                        Text(
                            text = "拼音:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            state.pinyinCandidates.chunked(6).forEach { rowItems ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    rowItems.forEach { pinyin ->
                                        Text(
                                            text = pinyin,
                                            modifier = Modifier.clickable { onPinyinClick(pinyin) },
                                            fontSize = 15.sp,
                                            color = PrimaryBlue,
                                            lineHeight = 20.sp
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                    } else if (state.selectedBlocks.isNotEmpty()) {
                        Text(
                            text = "已选字母块:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        Text(
                            text = state.selectedBlocks.mapNotNull { block ->
                                LetterBlockMapping.DIGIT_LABELS[block]
                            }.joinToString(" · "),
                            fontSize = 15.sp,
                            color = PrimaryBlue,
                            lineHeight = 20.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // 下半部分：汉字候选网格（无高亮循环，纯展示）
                    val chars = state.charCandidates
                    if (chars.isNotEmpty()) {
                        Text(
                            text = "候选词:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val displayChars = chars.take(15)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    for (col in 0..4) {
                                        val idx = row * 5 + col
                                        if (idx < displayChars.size) {
                                            GridCharBox(
                                                false,
                                                displayChars[idx],
                                                26.sp,
                                                22.sp,
                                                onClick = { onCharacterClick(displayChars[idx]) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    } else if (state.selectedBlocks.isEmpty()) {
                        // 初始状态：显示常用语
                        Text(
                            text = "常用语:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        val commonPhrases = CommonPhraseUsage.sorted(
                            com.example.input_ds.data.CharacterDictionary.COMMON_PHRASES
                        ).take(15)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    for (col in 0..4) {
                                        val idx = row * 5 + col
                                        if (idx < commonPhrases.size) {
                                            GridCharBox(
                                                false,
                                                commonPhrases[idx],
                                                18.sp,
                                                16.sp,
                                                onClick = { onCommonPhraseClick(commonPhrases[idx]) },
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            InputPhase.LEVEL_2_LETTER_SELECT -> {
                if (!state.isCharFocused) {
                    // 子状态A：选拼音 — 显示拼音组合 + 候选字预览
                    Column {
                        if (state.pinyinCombinations.isNotEmpty() || state.hasInitialPredictionOption) {
                            Text(
                                text = "拼音组合:",
                                fontSize = 11.sp,
                                color = TextGray
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GridCharBox(
                                    state.highlightedPinyinIndex == 0,
                                    "返回",
                                    18.sp,
                                    15.sp,
                                    isNav = true,
                                    onClick = { onPinyinNavigationClick(0) },
                                    modifier = Modifier
                                )
                                state.pinyinCombinations.forEachIndexed { index, pinyin ->
                                    val isHighlighted = index + 1 == state.highlightedPinyinIndex
                                    Text(
                                        text = pinyin,
                                        modifier = Modifier.clickable { onPinyinClick(pinyin) },
                                        fontSize = if (isHighlighted) 20.sp else 16.sp,
                                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isHighlighted) HighlightYellow else PrimaryBlue
                                    )
                                }
                                if (state.hasInitialPredictionOption) {
                                    val highlighted = state.highlightedPinyinIndex ==
                                        state.pinyinCombinations.size + 1
                                    GridCharBox(
                                        highlighted,
                                        "首字母",
                                        18.sp,
                                        15.sp,
                                        isNav = true,
                                        onClick = {
                                            onPinyinNavigationClick(state.pinyinCombinations.size + 1)
                                        },
                                        modifier = Modifier
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        if (state.charCandidates.isNotEmpty()) {
                            Text(
                                text = "候选词 (${state.currentPinyin}) · 预览:",
                                fontSize = 11.sp,
                                color = TextGray
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val previewItems = SelectionPaging.characterItems(state.charCandidates, 0)

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (row in 0..2) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (col in 0..4) {
                                            val idx = row * 5 + col
                                            val item = previewItems.getOrNull(idx)
                                            if (item != null) {
                                                GridCharBox(
                                                    false,
                                                    item.label,
                                                    26.sp,
                                                    18.sp,
                                                    isNav = item.action != SelectionItemAction.SELECT,
                                                    onClick = if (item.action == SelectionItemAction.SELECT) {
                                                        { onCharacterClick(item.label) }
                                                    } else null,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "第1/${SelectionPaging.totalPages(state.charCandidates.size)}页",
                                fontSize = 11.sp,
                                color = TextGray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                } else {
                    // 子状态B：选汉字 — 上方拼音组合不动，下方汉字网格循环高亮
                    Column {
                        // 上方：拼音组合（与子状态A完全一致，被选中的固定高亮）
                        if (state.pinyinCombinations.isNotEmpty() || state.hasInitialPredictionOption) {
                            Text(
                                text = "拼音组合:",
                                fontSize = 11.sp,
                                color = TextGray
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GridCharBox(
                                    false,
                                    "返回",
                                    18.sp,
                                    15.sp,
                                    isNav = true,
                                    onClick = { onPinyinNavigationClick(0) },
                                    modifier = Modifier
                                )
                                state.pinyinCombinations.forEachIndexed { index, pinyin ->
                                    val isHighlighted = index + 1 == state.highlightedPinyinIndex
                                    Text(
                                        text = pinyin,
                                        modifier = Modifier.clickable { onPinyinClick(pinyin) },
                                        fontSize = if (isHighlighted) 20.sp else 16.sp,
                                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isHighlighted) HighlightYellow else PrimaryBlue
                                    )
                                }
                                if (state.hasInitialPredictionOption) {
                                    GridCharBox(
                                        false,
                                        "首字母",
                                        18.sp,
                                        15.sp,
                                        isNav = true,
                                        onClick = {
                                            onPinyinNavigationClick(state.pinyinCombinations.size + 1)
                                        },
                                        modifier = Modifier
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                        }

                        // 下方：汉字候选网格 + 自动循环高亮
                        Text(
                            text = "候选词 (${state.currentPinyin}) · 选字中:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        val chars = state.charCandidates
                        if (chars.isNotEmpty()) {
                            val items = SelectionPaging.characterItems(chars, state.charPage)

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (row in 0..2) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (col in 0..4) {
                                            val idx = row * 5 + col
                                            val item = items.getOrNull(idx)
                                            if (item != null) {
                                                GridCharBox(
                                                    idx == state.highlightedCharIndex,
                                                    item.label,
                                                    26.sp,
                                                    18.sp,
                                                    isNav = item.action != SelectionItemAction.SELECT,
                                                    onClick = if (item.action == SelectionItemAction.SELECT) {
                                                        { onCharacterClick(item.label) }
                                                    } else null,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            } else {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "第${state.charPage + 1}/${SelectionPaging.totalPages(chars.size)}页",
                                fontSize = 11.sp,
                                color = TextGray,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
            InputPhase.LEVEL_3_CHAR_SELECT -> {
                Column {
                    Text(
                        text = "${state.currentPinyin} →",
                        fontSize = 11.sp,
                        color = TextGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val chars = state.charCandidates
                    if (chars.isNotEmpty()) {
                        val items = SelectionPaging.characterItems(chars, state.charPage)

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    for (col in 0..4) {
                                        val idx = row * 5 + col
                                        val item = items.getOrNull(idx)
                                        if (item != null) {
                                            GridCharBox(
                                                idx == state.highlightedCharIndex,
                                                item.label,
                                                26.sp,
                                                18.sp,
                                                isNav = item.action != SelectionItemAction.SELECT,
                                                onClick = if (item.action == SelectionItemAction.SELECT) {
                                                    { onCharacterClick(item.label) }
                                                } else null,
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "第${state.charPage + 1}/${SelectionPaging.totalPages(chars.size)}页",
                            fontSize = 11.sp,
                            color = TextGray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            InputPhase.PREDICTION -> {
                Column {
                    Text(
                        text = if (state.isCommonPhraseSelection) {
                            "常用语 · 自动循环中:"
                        } else {
                            "预测词 · 自动循环中:"
                        },
                        fontSize = 11.sp,
                        color = TextGray
                    )
                    val dirText = if (state.charScanDirection >= 0) "→" else "←"
                    Text(
                        text = "左看/右看控制方向 ${dirText}",
                        fontSize = 10.sp,
                        color = TextGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    val items = if (state.isCommonPhraseSelection) {
                        SelectionPaging.commonPhraseItems(state.predictionCandidates, state.predictionPage)
                    } else {
                        SelectionPaging.predictionItems(state.predictionCandidates)
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (row in 0..2) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (col in 0..4) {
                                    val idx = row * 5 + col
                                    val item = items.getOrNull(idx)
                                    if (item != null) {
                                        GridCharBox(
                                            idx == state.highlightedPredictionIndex,
                                            item.label,
                                            20.sp,
                                            17.sp,
                                            isNav = item.action != SelectionItemAction.SELECT,
                                            onClick = { onPredictionClick(idx) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    if (state.isCommonPhraseSelection) {
                        Text(
                            text = "第${state.predictionPage + 1}/${SelectionPaging.totalPages(state.predictionCandidates.size)}页",
                            fontSize = 11.sp,
                            color = TextGray,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            InputPhase.INITIAL_PREDICTION -> {
                Column {
                    Text(
                        text = "首字母整句 ${state.initialBlocksKey} · ${when (state.initialPredictionStatus) {
                            com.example.input_ds.model.PredictionStatus.LOADING -> "生成中"
                            com.example.input_ds.model.PredictionStatus.READY -> "已就绪"
                            com.example.input_ds.model.PredictionStatus.UNAVAILABLE -> "远程不可用"
                            com.example.input_ds.model.PredictionStatus.IDLE -> "等待中"
                        }}",
                        fontSize = 11.sp,
                        color = TextGray
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val items = SelectionPaging.initialSentenceItems(
                        state.initialCandidates,
                        state.initialPage
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (row in 0..2) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (col in 0..4) {
                                    val index = row * 5 + col
                                    val item = items.getOrNull(index)
                                    if (item != null) {
                                        GridCharBox(
                                            index == state.highlightedInitialIndex,
                                            item.label,
                                            20.sp,
                                            17.sp,
                                            isNav = item.action != SelectionItemAction.SELECT,
                                            onClick = { onInitialPredictionClick(index) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = "第${state.initialPage + 1}/${SelectionPaging.totalPages(state.initialCandidates.size)}页",
                        fontSize = 11.sp,
                        color = TextGray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 输出文本区域（顶部）
 */
@Composable
fun OutputTextArea(
    text: String
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(AuroraDarkSurface)
            .border(1.dp, BlockBorder, RoundedCornerShape(18.dp))
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (text.isEmpty()) "输入文本将显示在此处..." else text,
            fontSize = if (text.isEmpty()) 18.sp else 26.sp,
            fontWeight = if (text.isEmpty()) FontWeight.Normal else FontWeight.Medium,
            color = if (text.isEmpty()) TextGray else TextWhite,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

package com.example.input_ds.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanSide
import com.example.input_ds.ui.theme.*

/**
 * 主输入界面
 *
 * 布局对应 PRD 中的扫描界面设计：
 * - 左侧 4 个字母块
 * - 中间区域 1（放大字母 + 左/右指示）
 * - 中间区域 2（拼音/汉字候选）
 * - 右侧 4 个字母块
 * - 底部控制按钮
 */
@Composable
fun MainScreen(
    state: InputState,
    onLeftLook: () -> Unit,
    onRightLook: () -> Unit,
    onBite: () -> Unit,
    onSpeedUp: () -> Unit,
    onSpeedDown: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(8.dp)
    ) {
        // === 顶部输出区域 ===
        OutputTextArea(state.outputText)

        Spacer(modifier = Modifier.height(8.dp))

        // === 中间主交互区域 ===
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                        isSelected = block in state.selectedBlocks,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 中间区域
            Column(
                modifier = Modifier
                    .weight(2f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 区域1：放大显示 + 左右指示
                Region1(
                    state = state,
                    modifier = Modifier.weight(1f)
                )

                // 区域2：拼音/汉字候选
                Region2(
                    state = state,
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
                        isSelected = block in state.selectedBlocks,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // === 底部控制区域 ===
        ControlPanel(
            onLeftLook = onLeftLook,
            onRightLook = onRightLook,
            onBite = onBite,
            onSpeedUp = onSpeedUp,
            onSpeedDown = onSpeedDown,
            currentPhase = state.phase,
            scanIntervalMs = state.scanIntervalMs,
            isCharFocused = state.isCharFocused
        )
    }
}

/**
 * 字母块组件
 */
@Composable
fun LetterBlockItem(
    label: String,
    isHighlighted: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> HighlightYellow.copy(alpha = 0.7f)
            else -> SurfaceDark
        },
        label = "blockColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isHighlighted -> HighlightYellow
            else -> BlockBorder
        },
        label = "borderColor"
    )

    // 高亮时添加脉冲动画
    val pulseAlpha = if (isHighlighted) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.6f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor.copy(alpha = pulseAlpha))
            .border(2.dp, borderColor.copy(alpha = pulseAlpha), RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = if (isHighlighted) 22.sp else 18.sp,
            fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlighted) Color.Black else TextWhite
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
            .clip(RoundedCornerShape(4.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(4.dp))
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
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, BlockBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
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
                        val letters = LetterBlockMapping.DIGIT_TO_LETTERS[currentBlock]
                            ?.joinToString(" ") { it.uppercase() } ?: ""
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
                        state.pinyinCombinations.forEachIndexed { index, pinyin ->
                            val isHighlighted = index == state.highlightedPinyinIndex
                            Text(
                                text = pinyin,
                                fontSize = if (isHighlighted) 30.sp else 22.sp,
                                fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                color = if (isHighlighted) HighlightYellow else TextWhite
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
                        text = "预测词",
                        fontSize = 12.sp,
                        color = TextGray
                    )
                    if (state.predictionCandidates.isNotEmpty()) {
                        val idx = state.highlightedPredictionIndex
                        if (idx < state.predictionCandidates.size) {
                            Text(
                                text = state.predictionCandidates[idx],
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
}

/**
 * 区域2：拼音候选 / 汉字候选 / 预测词 显示
 */
@Composable
fun Region2(
    state: InputState,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, BlockBorder, RoundedCornerShape(8.dp))
            .padding(8.dp)
    ) {
        when (state.phase) {
            InputPhase.LEVEL_1_SCANNING -> {
                Column {
                    // 上半部分：拼音候选（全量显示，与咬定后一致）
                    if (state.pinyinCandidates.isNotEmpty()) {
                        Text(
                            text = "拼音:",
                            fontSize = 11.sp,
                            color = TextGray
                        )
                        Text(
                            text = state.pinyinCandidates.joinToString("  "),
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
                                            GridCharBox(false, displayChars[idx], 26.sp, 22.sp, modifier = Modifier.weight(1f))
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
                        val commonPhrases = com.example.input_ds.data.CharacterDictionary.COMMON_PHRASES.take(15)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    for (col in 0..4) {
                                        val idx = row * 5 + col
                                        if (idx < commonPhrases.size) {
                                            GridCharBox(false, commonPhrases[idx], 18.sp, 16.sp, modifier = Modifier.weight(1f))
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
                        if (state.pinyinCombinations.isNotEmpty()) {
                            Text(
                                text = "拼音组合:",
                                fontSize = 11.sp,
                                color = TextGray
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.pinyinCombinations.forEachIndexed { index, pinyin ->
                                    val isHighlighted = index == state.highlightedPinyinIndex
                                    Text(
                                        text = pinyin,
                                        fontSize = if (isHighlighted) 20.sp else 16.sp,
                                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isHighlighted) HighlightYellow else PrimaryBlue
                                    )
                                }
                                // 返回按钮（与候选词网格样式一致）
                                val isReturnHl = state.highlightedPinyinIndex >= state.pinyinCombinations.size
                                GridCharBox(isReturnHl, "返回", 18.sp, 15.sp, isNav = true, modifier = Modifier)
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
                            // 与选字状态完全相同的分页网格（无高亮循环）
                            val chars = state.charCandidates
                            val charsPerPage = 15
                            val totalPages = (chars.size + charsPerPage - 1) / charsPerPage
                            // 始终 page=0，与选字态第一页逻辑一致
                            val hasPrev = false
                            val hasNext = totalPages > 1
                            val displayCount = charsPerPage - (if (hasPrev) 1 else 0) - (if (hasNext) 1 else 0) - 1
                            val actualCount = minOf(displayCount, chars.size)
                            val pageChars = chars.take(actualCount)
                            val prevSlot = -1  // 预览无上一页
                            val nextSlot = if (hasNext) actualCount else -1
                            val backSlot = actualCount + (if (hasNext) 1 else 0)

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (row in 0..2) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (col in 0..4) {
                                            val idx = row * 5 + col
                                            if (idx < actualCount) {
                                                GridCharBox(false, pageChars[idx], 26.sp, 22.sp, modifier = Modifier.weight(1f))
                                            } else if (idx == nextSlot) {
                                                GridCharBox(false, "下一页", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                            } else if (idx == backSlot) {
                                                GridCharBox(false, "返回", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                            } else {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "第1/${totalPages}页",
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
                        if (state.pinyinCombinations.isNotEmpty()) {
                            Text(
                                text = "拼音组合:",
                                fontSize = 11.sp,
                                color = TextGray
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.pinyinCombinations.forEachIndexed { index, pinyin ->
                                    val isHighlighted = index == state.highlightedPinyinIndex
                                    Text(
                                        text = pinyin,
                                        fontSize = if (isHighlighted) 20.sp else 16.sp,
                                        fontWeight = if (isHighlighted) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isHighlighted) HighlightYellow else PrimaryBlue
                                    )
                                }
                                GridCharBox(false, "返回", 18.sp, 15.sp, isNav = true, modifier = Modifier)
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
                            val charsPerPage = 15
                            val totalPages = (chars.size + charsPerPage - 1) / charsPerPage
                            val currentPage = state.highlightedCharIndex / charsPerPage
                            val startIdx = currentPage * charsPerPage
                            val hasPrev = currentPage > 0
                            val hasNext = currentPage < totalPages - 1
                            val displayCount = charsPerPage - (if (hasPrev) 1 else 0) - (if (hasNext) 1 else 0) - 1
                            val actualCount = minOf(displayCount, chars.size - startIdx)
                            val pageChars = chars.subList(startIdx, startIdx + actualCount)
                            // 导航槽位：上一页 / 下一页 / 返回
                            val prevSlot = if (hasPrev) actualCount else -1
                            val nextSlot = if (hasNext) (if (hasPrev) actualCount + 1 else actualCount) else -1
                            val backSlot = actualCount + (if (hasPrev) 1 else 0) + (if (hasNext) 1 else 0)

                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                for (row in 0..2) {
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        for (col in 0..4) {
                                            val idx = row * 5 + col
                                            if (idx < actualCount) {
                                                val charIdx = startIdx + idx
                                                val isHl = charIdx == state.highlightedCharIndex
                                                GridCharBox(isHl, pageChars[idx], 26.sp, 22.sp, modifier = Modifier.weight(1f))
                                            } else if (idx == prevSlot) {
                                                val isHl = state.highlightedCharIndex == startIdx + prevSlot
                                                GridCharBox(isHl, "上一页", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                            } else if (idx == nextSlot) {
                                                val isHl = state.highlightedCharIndex == startIdx + nextSlot
                                                GridCharBox(isHl, "下一页", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                            } else if (idx == backSlot) {
                                                val isHl = state.highlightedCharIndex == startIdx + backSlot
                                                GridCharBox(isHl, "返回", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                            } else {
                                                Spacer(modifier = Modifier.weight(1f))
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "第${currentPage + 1}/${totalPages}页",
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
                        val charsPerPage = 15
                        val totalPages = (chars.size + charsPerPage - 1) / charsPerPage
                        val currentPage = state.highlightedCharIndex / charsPerPage
                        val startIdx = currentPage * charsPerPage
                        val hasPrev = currentPage > 0
                        val hasNext = currentPage < totalPages - 1
                        val displayCount = charsPerPage - (if (hasPrev) 1 else 0) - (if (hasNext) 1 else 0) - 1
                        val actualCount = minOf(displayCount, chars.size - startIdx)
                        val pageChars = chars.subList(startIdx, startIdx + actualCount)
                        val prevSlot = if (hasPrev) actualCount else -1
                        val nextSlot = if (hasNext) (if (hasPrev) actualCount + 1 else actualCount) else -1
                        val backSlot = actualCount + (if (hasPrev) 1 else 0) + (if (hasNext) 1 else 0)

                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (row in 0..2) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    for (col in 0..4) {
                                        val idx = row * 5 + col
                                        if (idx < actualCount) {
                                            val charIdx = startIdx + idx
                                            val isHl = charIdx == state.highlightedCharIndex
                                            GridCharBox(isHl, pageChars[idx], 26.sp, 22.sp, modifier = Modifier.weight(1f))
                                        } else if (idx == prevSlot) {
                                            val isHl = state.highlightedCharIndex == startIdx + prevSlot
                                            GridCharBox(isHl, "上一页", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                        } else if (idx == nextSlot) {
                                            val isHl = state.highlightedCharIndex == startIdx + nextSlot
                                            GridCharBox(isHl, "下一页", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                        } else if (idx == backSlot) {
                                            val isHl = state.highlightedCharIndex == startIdx + backSlot
                                            GridCharBox(isHl, "返回", 18.sp, 15.sp, isNav = true, modifier = Modifier.weight(1f))
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }

                        Text(
                            text = "第${currentPage + 1}/${totalPages}页",
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
                        text = "预测词 · 自动循环中:",
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

                    val predictions = state.predictionCandidates
                    val pageSize = 15
                    val currentPage = state.highlightedPredictionIndex / pageSize
                    val startIdx = currentPage * pageSize
                    val pagePredictions = predictions.subList(
                        startIdx,
                        minOf(startIdx + pageSize, predictions.size)
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (row in 0..2) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                for (col in 0..4) {
                                    val idx = row * 5 + col
                                    if (idx < pagePredictions.size) {
                                        val predIdx = startIdx + idx
                                        val isHighlighted = predIdx == state.highlightedPredictionIndex
                                        GridCharBox(isHighlighted, pagePredictions[idx], 20.sp, 17.sp, modifier = Modifier.weight(1f))
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
    }
}

/**
 * 底部控制面板
 */
@Composable
fun ControlPanel(
    onLeftLook: () -> Unit,
    onRightLook: () -> Unit,
    onBite: () -> Unit,
    onSpeedUp: () -> Unit,
    onSpeedDown: () -> Unit,
    currentPhase: InputPhase,
    scanIntervalMs: Long,
    isCharFocused: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左看按钮
        Button(
            onClick = onLeftLook,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryBlue
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "← 左看",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 咬牙/确认按钮
        Button(
            onClick = onBite,
            modifier = Modifier.weight(1.3f),
            colors = ButtonDefaults.buttonColors(
                containerColor = when (currentPhase) {
                    InputPhase.LEVEL_1_SCANNING -> AccentGreen
                    InputPhase.LEVEL_2_LETTER_SELECT -> HighlightOrange
                    InputPhase.LEVEL_3_CHAR_SELECT -> HighlightOrange
                    InputPhase.PREDICTION -> HighlightOrange
                }
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = when {
                    isCharFocused -> "咬牙·选字"
                    currentPhase == InputPhase.LEVEL_1_SCANNING -> "咬牙·进二级"
                    currentPhase == InputPhase.LEVEL_2_LETTER_SELECT -> "咬牙·选拼音"
                    currentPhase == InputPhase.LEVEL_3_CHAR_SELECT -> "咬牙·选字"
                    currentPhase == InputPhase.PREDICTION -> "咬牙·选词"
                    else -> "咬牙·确认"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // 右看按钮
        Button(
            onClick = onRightLook,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = PrimaryBlue
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = "右看 →",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // 第二行：速度调节
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onSpeedDown,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceDark
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("🐢 减速", fontSize = 11.sp, color = TextGray)
        }
        Text(
            text = "  ${scanIntervalMs}ms  ",
            fontSize = 12.sp,
            color = TextGray
        )
        Button(
            onClick = onSpeedUp,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceDark
            ),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("加速 🐇", fontSize = 11.sp, color = TextGray)
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
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceDark)
            .border(1.dp, BlockBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = if (text.isEmpty()) "输入文本将显示在此处..." else text,
            fontSize = 18.sp,
            color = if (text.isEmpty()) TextGray else TextWhite
        )
    }
}

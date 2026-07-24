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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.input_ds.data.SideKey
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanSide
import com.example.input_ds.ui.theme.AccentGreen
import com.example.input_ds.ui.theme.BlockBorder
import com.example.input_ds.ui.theme.DarkBackground
import com.example.input_ds.ui.theme.ErrorRed
import com.example.input_ds.ui.theme.HighlightOrange
import com.example.input_ds.ui.theme.HighlightYellow
import com.example.input_ds.ui.theme.PrimaryBlue
import com.example.input_ds.ui.theme.SurfaceDark
import com.example.input_ds.ui.theme.TextGray
import com.example.input_ds.ui.theme.TextWhite

/** 主输入界面：两侧扫描区 + 中间五阶段区 + 底部控制区。 */
@OptIn(ExperimentalLayoutApi::class)
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
        OutputTextArea(state.outputText)
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            SideKeyColumn(
                keys = LetterBlockMapping.LEFT_KEYS,
                isActiveSide = state.phase == InputPhase.PINYIN_KEY_INPUT &&
                    state.scanSide == ScanSide.LEFT,
                highlightedIndex = state.highlightedSideKeyIndex,
                selectedBlocks = state.selectedBlocks,
                modifier = Modifier.weight(0.9f)
            )

            FiveStageArea(
                state = state,
                modifier = Modifier.weight(2.8f)
            )

            SideKeyColumn(
                keys = LetterBlockMapping.RIGHT_KEYS,
                isActiveSide = state.phase == InputPhase.PINYIN_KEY_INPUT &&
                    state.scanSide == ScanSide.RIGHT,
                highlightedIndex = state.highlightedSideKeyIndex,
                selectedBlocks = state.selectedBlocks,
                modifier = Modifier.weight(0.9f)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
        ControlPanel(
            onLeftLook = onLeftLook,
            onRightLook = onRightLook,
            onBite = onBite,
            onSpeedUp = onSpeedUp,
            onSpeedDown = onSpeedDown,
            currentPhase = state.phase,
            scanIntervalMs = state.scanIntervalMs
        )
    }
}

@Composable
private fun SideKeyColumn(
    keys: List<SideKey>,
    isActiveSide: Boolean,
    highlightedIndex: Int,
    selectedBlocks: List<Int>,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        keys.forEachIndexed { index, key ->
            SideKeyItem(
                label = key.label,
                isHighlighted = isActiveSide && highlightedIndex == index,
                isSelected = key.digit != null && key.digit in selectedBlocks,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun SideKeyItem(
    label: String,
    isHighlighted: Boolean,
    isSelected: Boolean,
    modifier: Modifier = Modifier
) {
    val background by animateColorAsState(
        targetValue = when {
            isHighlighted -> HighlightYellow.copy(alpha = 0.76f)
            isSelected -> AccentGreen.copy(alpha = 0.2f)
            else -> SurfaceDark
        },
        label = "sideKeyBackground"
    )
    val border by animateColorAsState(
        targetValue = when {
            isHighlighted -> HighlightYellow
            isSelected -> AccentGreen
            else -> BlockBorder
        },
        label = "sideKeyBorder"
    )
    val pulse = if (isHighlighted) {
        val transition = rememberInfiniteTransition(label = "sideKeyPulse")
        val alpha by transition.animateFloat(
            initialValue = 0.68f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "sideKeyPulseAlpha"
        )
        alpha
    } else {
        1f
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(background.copy(alpha = pulse))
            .border(2.dp, border.copy(alpha = pulse), RoundedCornerShape(7.dp))
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontSize = when {
                isHighlighted -> 19.sp
                label.length >= 4 -> 12.sp
                else -> 16.sp
            },
            fontWeight = if (isHighlighted || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isHighlighted) Color.Black else TextWhite,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiveStageArea(
    state: InputState,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        StagePanel(
            title = "1  已选择的拼音按键",
            active = state.phase == InputPhase.PINYIN_KEY_INPUT,
            modifier = Modifier.weight(stageWeight(state.phase, InputPhase.PINYIN_KEY_INPUT))
        ) {
            if (state.selectedBlocks.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    state.selectedBlocks.forEach { digit ->
                        Text(
                            text = LetterBlockMapping.DIGIT_LABELS[digit].orEmpty(),
                            color = if (state.phase == InputPhase.PINYIN_KEY_INPUT) HighlightYellow else TextWhite,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }

        StagePanel(
            title = "2  选择具体拼音",
            active = state.phase == InputPhase.PINYIN_SELECTION,
            modifier = Modifier.weight(stageWeight(state.phase, InputPhase.PINYIN_SELECTION))
        ) {
            when {
                state.selectedBlocks.isEmpty() -> Unit

                state.pinyinCombinations.isEmpty() -> {
                    Text(
                        text = "当前按键组合没有可用拼音，请继续选择或删除后重试",
                        color = ErrorRed,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp)
                    )
                }

                state.phase == InputPhase.PINYIN_KEY_INPUT -> {
                    // 第一阶段实时预览：只显示完整使用当前全部按键的合法拼音。
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        state.pinyinCombinations.forEach { pinyin ->
                            Text(
                                text = pinyin,
                                color = TextGray.copy(alpha = 0.72f),
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                else -> {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val options = state.pinyinCombinations + "返回选择拼音"
                        options.forEachIndexed { index, option ->
                            SelectionChip(
                                text = option,
                                selected = index == state.highlightedPinyinOptionIndex,
                                compact = false
                            )
                        }
                    }
                }
            }
        }

        StagePanel(
            title = "3  选择汉字",
            active = state.phase == InputPhase.CHARACTER_SELECTION,
            modifier = Modifier.weight(stageWeight(state.phase, InputPhase.CHARACTER_SELECTION))
        ) {
            if (state.phase.ordinal >= InputPhase.CHARACTER_SELECTION.ordinal &&
                state.charCandidates.isNotEmpty()
            ) {
                val listState = rememberLazyListState()
                LaunchedEffect(state.charRowIndex) {
                    listState.scrollToItem((state.charRowIndex - 1).coerceAtLeast(0))
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val rows = state.charCandidates.chunked(4)
                    items(rows.size) { rowIndex ->
                        val characters = rows[rowIndex]
                        Box(modifier = Modifier.padding(bottom = 2.dp)) {
                            CandidateCharacterRow(
                                characters = characters,
                                rowIndex = rowIndex,
                                rowCount = rows.size,
                                isCurrentRow = rowIndex == state.charRowIndex,
                                highlightedOptionIndex = state.highlightedCharOptionIndex
                            )
                        }
                    }
                }
            }
        }

        StagePanel(
            title = "4  选择词语",
            active = state.phase == InputPhase.WORD_SELECTION,
            modifier = Modifier.weight(stageWeight(state.phase, InputPhase.WORD_SELECTION))
        ) {
            if (state.phase.ordinal >= InputPhase.WORD_SELECTION.ordinal) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    state.wordCandidates.forEachIndexed { index, word ->
                        SelectionChip(
                            text = word,
                            selected = index == state.highlightedWordIndex,
                            compact = false
                        )
                    }
                }
            }
        }

        StagePanel(
            title = "5  预测句子",
            active = state.phase == InputPhase.SENTENCE_SELECTION,
            modifier = Modifier.weight(stageWeight(state.phase, InputPhase.SENTENCE_SELECTION))
        ) {
            if (state.phase == InputPhase.SENTENCE_SELECTION) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    state.sentenceCandidates.forEachIndexed { index, sentence ->
                        SelectionChip(
                            text = sentence,
                            selected = index == state.highlightedSentenceIndex,
                            compact = false
                        )
                    }
                }
            }
        }
    }
}

private fun stageWeight(current: InputPhase, panel: InputPhase): Float = when {
    current == panel && panel == InputPhase.CHARACTER_SELECTION -> 3.2f
    current == panel -> 1.55f
    current == InputPhase.PINYIN_KEY_INPUT && panel == InputPhase.PINYIN_SELECTION -> 1.2f
    current.ordinal > panel.ordinal && panel == InputPhase.CHARACTER_SELECTION -> 1.5f
    else -> 0.72f
}

@Composable
private fun StagePanel(
    title: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (active) SurfaceDark else SurfaceDark.copy(alpha = 0.72f))
            .border(
                width = if (active) 2.dp else 1.dp,
                color = if (active) HighlightYellow else BlockBorder,
                shape = RoundedCornerShape(7.dp)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    ) {
        Text(
            text = title,
            color = if (active) HighlightYellow else TextGray,
            fontSize = if (active) 11.sp else 10.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal
        )
        Spacer(modifier = Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
            content = content
        )
    }
}

@Composable
private fun SelectionChip(
    text: String,
    selected: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(horizontal = 3.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(if (selected) HighlightYellow.copy(alpha = 0.78f) else Color.Transparent)
            .border(
                1.dp,
                if (selected) HighlightYellow else BlockBorder.copy(alpha = 0.7f),
                RoundedCornerShape(5.dp)
            )
            .padding(horizontal = if (compact) 4.dp else 7.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color.Black else TextWhite,
            fontSize = when {
                selected && compact -> 14.sp
                selected -> 20.sp
                compact -> 10.sp
                else -> 15.sp
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CandidateCharacterRow(
    characters: List<String>,
    rowIndex: Int,
    rowCount: Int,
    isCurrentRow: Boolean,
    highlightedOptionIndex: Int
) {
    val labels = listOf(
        "上一行",
        "返回",
        characters.getOrNull(0).orEmpty(),
        characters.getOrNull(1).orEmpty(),
        characters.getOrNull(2).orEmpty(),
        characters.getOrNull(3).orEmpty(),
        "下一行"
    )
    val enabled = listOf(
        rowIndex > 0,
        true,
        characters.size >= 1,
        characters.size >= 2,
        characters.size >= 3,
        characters.size >= 4,
        rowIndex < rowCount - 1
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCurrentRow) 38.dp else 25.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        labels.forEachIndexed { optionIndex, label ->
            CandidateCell(
                text = label,
                selected = isCurrentRow && highlightedOptionIndex == optionIndex,
                enabled = enabled[optionIndex],
                isCurrentRow = isCurrentRow,
                modifier = Modifier.weight(if (optionIndex in listOf(0, 1, 6)) 1.25f else 0.8f)
            )
        }
    }
}

@Composable
private fun CandidateCell(
    text: String,
    selected: Boolean,
    enabled: Boolean,
    isCurrentRow: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(4.dp))
            .background(if (selected) HighlightYellow.copy(alpha = 0.8f) else Color.Transparent)
            .border(
                1.dp,
                when {
                    selected -> HighlightYellow
                    isCurrentRow && enabled -> BlockBorder
                    else -> Color.Transparent
                },
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 1.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = when {
                selected -> Color.Black
                !enabled -> TextGray.copy(alpha = 0.28f)
                isCurrentRow -> TextWhite
                else -> TextGray.copy(alpha = 0.55f)
            },
            fontSize = when {
                selected -> 16.sp
                isCurrentRow -> 10.sp
                else -> 8.sp
            },
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ControlPanel(
    onLeftLook: () -> Unit,
    onRightLook: () -> Unit,
    onBite: () -> Unit,
    onSpeedUp: () -> Unit,
    onSpeedDown: () -> Unit,
    currentPhase: InputPhase,
    scanIntervalMs: Long
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(54.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Button(
            onClick = onLeftLook,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("← 左看", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }

        Button(
            onClick = onBite,
            modifier = Modifier.weight(1.25f),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (currentPhase == InputPhase.PINYIN_KEY_INPUT) AccentGreen else HighlightOrange
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = when (currentPhase) {
                    InputPhase.PINYIN_KEY_INPUT -> "咬牙·选拼音"
                    InputPhase.PINYIN_SELECTION -> "咬牙·确认拼音"
                    InputPhase.CHARACTER_SELECTION -> "咬牙·选字"
                    InputPhase.WORD_SELECTION -> "咬牙·选词"
                    InputPhase.SENTENCE_SELECTION -> "咬牙·输入句子"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick = onRightLook,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("右看 →", fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }

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
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("减速", fontSize = 11.sp, color = TextGray)
        }
        Text("  ${scanIntervalMs}ms  ", fontSize = 12.sp, color = TextGray)
        Button(
            onClick = onSpeedUp,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceDark),
            shape = RoundedCornerShape(6.dp)
        ) {
            Text("加速", fontSize = 11.sp, color = TextGray)
        }
    }
}

@Composable
private fun OutputTextArea(text: String) {
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

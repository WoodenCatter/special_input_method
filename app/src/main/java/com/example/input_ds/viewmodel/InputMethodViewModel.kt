package com.example.input_ds.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.SideKeyType
import com.example.input_ds.engine.CharacterLookupEngine
import com.example.input_ds.engine.PinyinRecoveryEngine
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.ScanSide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 扫描式输入法状态机。
 *
 * 只有第一阶段会自动轮转两侧按键；后续四个阶段都由左看、右看移动
 * 选中项，由咬牙确认。
 */
class InputMethodViewModel : ViewModel() {

    private val _state = MutableStateFlow(InputState())
    val state: StateFlow<InputState> = _state

    private val pinyinRecoveryEngine = PinyinRecoveryEngine()
    private val characterLookupEngine = CharacterLookupEngine()
    private var scanJob: Job? = null

    init {
        startScanning()
    }

    fun handleSignal(signal: ControlSignal) {
        when (_state.value.phase) {
            InputPhase.PINYIN_KEY_INPUT -> handleKeyInputSignal(signal)
            InputPhase.PINYIN_SELECTION -> handlePinyinSignal(signal)
            InputPhase.CHARACTER_SELECTION -> handleCharacterSignal(signal)
            InputPhase.WORD_SELECTION -> handleWordSignal(signal)
            InputPhase.SENTENCE_SELECTION -> handleSentenceSignal(signal)
        }
    }

    // 第一块：两侧按键自动扫描

    private fun handleKeyInputSignal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                if (_state.value.scanSide == ScanSide.LEFT) selectCurrentSideKey()
                else switchScanSide(ScanSide.LEFT)
            }

            ControlSignal.RIGHT_LOOK -> {
                if (_state.value.scanSide == ScanSide.RIGHT) selectCurrentSideKey()
                else switchScanSide(ScanSide.RIGHT)
            }

            ControlSignal.BITE -> {
                if (_state.value.selectedBlocks.isNotEmpty()) enterPinyinSelection()
            }
        }
    }

    private fun selectCurrentSideKey() {
        val current = _state.value
        val keys = LetterBlockMapping.keysFor(current.scanSide == ScanSide.LEFT)
        val key = keys.getOrNull(current.highlightedSideKeyIndex) ?: return

        when (key.type) {
            SideKeyType.PINYIN -> key.digit?.let { digit ->
                _state.value = current.copy(selectedBlocks = current.selectedBlocks + digit)
            }

            SideKeyType.DELETE -> deleteOneInput()
            SideKeyType.SEND -> sendText()
            SideKeyType.COMMON_PHRASES -> openCommonPhraseLibrary()
            SideKeyType.ENGLISH -> enterEnglishMode()
        }
    }

    private fun switchScanSide(side: ScanSide) {
        _state.value = _state.value.copy(
            scanSide = side,
            highlightedSideKeyIndex = 0
        )
    }

    /**
     * 删除优先级与普通输入法一致：先删尚未确认的拼音按键，删完后再删输出文字。
     */
    private fun deleteOneInput() {
        val current = _state.value
        _state.value = if (current.selectedBlocks.isNotEmpty()) {
            current.copy(selectedBlocks = current.selectedBlocks.dropLast(1))
        } else {
            current.copy(outputText = current.outputText.dropLast(1))
        }
    }

    /** 常用词库入口预留：后续可在此加载用户词库并切换新状态。 */
    private fun openCommonPhraseLibrary() = Unit

    /** 英文输入入口预留：后续可在此接入英文字符/单词选择状态。 */
    private fun enterEnglishMode() = Unit

    // 第二块：拼音选择

    private fun enterPinyinSelection() {
        val current = _state.value
        val combinations = pinyinRecoveryEngine.recover(current.selectedBlocks)
            .map { it.letters }
            .filter { it.isNotBlank() }
            // 当前一轮输入一个汉字，只保留能够直接查到候选字的完整音节。
            .filter { CharacterDictionary.PINYIN_TO_CHARS.containsKey(it) }
            .distinct()

        if (combinations.isEmpty()) return

        stopScanning()
        _state.value = current.copy(
            phase = InputPhase.PINYIN_SELECTION,
            pinyinCombinations = combinations,
            highlightedPinyinOptionIndex = 0,
            currentPinyin = "",
            charCandidates = emptyList()
        )
    }

    private fun handlePinyinSignal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> movePinyinSelection(-1)
            ControlSignal.RIGHT_LOOK -> movePinyinSelection(1)
            ControlSignal.BITE -> confirmPinyinSelection()
        }
    }

    private fun movePinyinSelection(direction: Int) {
        val current = _state.value
        // 拼音候选后还有一个“返回选择拼音”选项。
        val lastOptionIndex = current.pinyinCombinations.size
        val next = (current.highlightedPinyinOptionIndex + direction)
            .coerceIn(0, lastOptionIndex)
        _state.value = current.copy(highlightedPinyinOptionIndex = next)
    }

    private fun confirmPinyinSelection() {
        val current = _state.value
        val optionIndex = current.highlightedPinyinOptionIndex
        if (optionIndex == current.pinyinCombinations.size) {
            returnToKeyInput(retainSelectedBlocks = true)
            return
        }

        val pinyin = current.pinyinCombinations.getOrNull(optionIndex) ?: return
        val previousChar = current.outputText.lastOrNull()?.toString()
        val characters = characterLookupEngine.lookupSingleChar(pinyin, previousChar)
            .map { it.char }
            .distinct()
        if (characters.isEmpty()) return

        _state.value = current.copy(
            phase = InputPhase.CHARACTER_SELECTION,
            currentPinyin = pinyin,
            charCandidates = characters,
            charRowIndex = 0,
            highlightedCharOptionIndex = defaultCharacterOption(characters, 0),
            selectedCharacter = "",
            wordCandidates = emptyList(),
            sentenceCandidates = emptyList()
        )
    }

    // 第三块：每行四个汉字，左右看移动，不自动轮转

    private fun handleCharacterSignal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> moveCharacterSelection(-1)
            ControlSignal.RIGHT_LOOK -> moveCharacterSelection(1)
            ControlSignal.BITE -> confirmCharacterOption()
        }
    }

    private fun moveCharacterSelection(direction: Int) {
        val current = _state.value
        val available = availableCharacterOptions(current)
        if (available.isEmpty()) return

        val position = available.indexOf(current.highlightedCharOptionIndex)
            .takeIf { it >= 0 } ?: 0
        val nextPosition = (position + direction).coerceIn(0, available.lastIndex)
        _state.value = current.copy(highlightedCharOptionIndex = available[nextPosition])
    }

    private fun confirmCharacterOption() {
        val current = _state.value
        when (val option = current.highlightedCharOptionIndex) {
            0 -> changeCharacterRow(-1)
            1 -> returnToPinyinSelection()
            in 2..5 -> {
                val characterIndex = current.charRowIndex * CHARS_PER_ROW + (option - 2)
                val character = current.charCandidates.getOrNull(characterIndex) ?: return
                _state.value = current.copy(
                    phase = InputPhase.WORD_SELECTION,
                    selectedCharacter = character,
                    wordCandidates = listOf(character),
                    highlightedWordIndex = 0
                )
            }

            6 -> changeCharacterRow(1)
        }
    }

    private fun changeCharacterRow(delta: Int) {
        val current = _state.value
        val lastRow = lastCharacterRow(current.charCandidates)
        val newRow = (current.charRowIndex + delta).coerceIn(0, lastRow)
        _state.value = current.copy(
            charRowIndex = newRow,
            highlightedCharOptionIndex = defaultCharacterOption(current.charCandidates, newRow)
        )
    }

    private fun returnToPinyinSelection() {
        _state.value = _state.value.copy(
            phase = InputPhase.PINYIN_SELECTION,
            currentPinyin = "",
            charCandidates = emptyList(),
            charRowIndex = 0,
            highlightedCharOptionIndex = 0,
            selectedCharacter = "",
            wordCandidates = emptyList(),
            selectedWord = "",
            sentenceCandidates = emptyList()
        )
    }

    private fun availableCharacterOptions(state: InputState): List<Int> = buildList {
        if (state.charRowIndex > 0) add(0) // 上一行
        add(1) // 返回

        val start = state.charRowIndex * CHARS_PER_ROW
        val count = (state.charCandidates.size - start).coerceIn(0, CHARS_PER_ROW)
        repeat(count) { add(2 + it) }

        if (state.charRowIndex < lastCharacterRow(state.charCandidates)) add(6) // 下一行
    }

    private fun defaultCharacterOption(characters: List<String>, row: Int): Int {
        val count = (characters.size - row * CHARS_PER_ROW).coerceIn(0, CHARS_PER_ROW)
        return when {
            count >= 2 -> 3 // 默认字2
            count == 1 -> 2
            else -> 1
        }
    }

    private fun lastCharacterRow(characters: List<String>): Int =
        if (characters.isEmpty()) 0 else (characters.size - 1) / CHARS_PER_ROW

    // 第四块：词语预测（当前按需求只显示所选汉字）

    private fun handleWordSignal(signal: ControlSignal) {
        val current = _state.value
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                val index = (current.highlightedWordIndex - 1).coerceAtLeast(0)
                _state.value = current.copy(highlightedWordIndex = index)
            }

            ControlSignal.RIGHT_LOOK -> {
                val lastIndex = current.wordCandidates.lastIndex.coerceAtLeast(0)
                val index = (current.highlightedWordIndex + 1).coerceAtMost(lastIndex)
                _state.value = current.copy(highlightedWordIndex = index)
            }

            ControlSignal.BITE -> {
                val word = current.wordCandidates.getOrNull(current.highlightedWordIndex) ?: return
                _state.value = current.copy(
                    phase = InputPhase.SENTENCE_SELECTION,
                    selectedWord = word,
                    sentenceCandidates = listOf(word),
                    highlightedSentenceIndex = 0
                )
            }
        }
    }

    // 第五块：大模型句子预测（当前按需求只显示所选词语）

    private fun handleSentenceSignal(signal: ControlSignal) {
        val current = _state.value
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                val index = (current.highlightedSentenceIndex - 1).coerceAtLeast(0)
                _state.value = current.copy(highlightedSentenceIndex = index)
            }

            ControlSignal.RIGHT_LOOK -> {
                val lastIndex = current.sentenceCandidates.lastIndex.coerceAtLeast(0)
                val index = (current.highlightedSentenceIndex + 1).coerceAtMost(lastIndex)
                _state.value = current.copy(highlightedSentenceIndex = index)
            }

            ControlSignal.BITE -> {
                val sentence = current.sentenceCandidates
                    .getOrNull(current.highlightedSentenceIndex) ?: return
                _state.value = current.copy(outputText = current.outputText + sentence)
                returnToKeyInput(retainSelectedBlocks = false)
            }
        }
    }

    private fun returnToKeyInput(retainSelectedBlocks: Boolean) {
        val current = _state.value
        _state.value = InputState(
            scanSide = current.scanSide,
            highlightedSideKeyIndex = current.highlightedSideKeyIndex,
            selectedBlocks = if (retainSelectedBlocks) current.selectedBlocks else emptyList(),
            outputText = current.outputText,
            scanIntervalMs = current.scanIntervalMs
        )
        startScanning()
    }

    /** 发送动作沿用旧版效果：发送后清空输入框并开始下一轮输入。 */
    fun sendText() {
        val current = _state.value
        stopScanning()
        _state.value = InputState(scanIntervalMs = current.scanIntervalMs)
        startScanning()
    }

    fun adjustSpeed(faster: Boolean) {
        val current = _state.value.scanIntervalMs
        val interval = if (faster) {
            maxOf(300L, current - 200L)
        } else {
            minOf(3000L, current + 200L)
        }
        _state.value = _state.value.copy(scanIntervalMs = interval)
    }

    private fun startScanning() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            while (isActive) {
                delay(_state.value.scanIntervalMs)
                advanceSideKeyHighlight()
            }
        }
    }

    private fun stopScanning() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun advanceSideKeyHighlight() {
        val current = _state.value
        if (current.phase != InputPhase.PINYIN_KEY_INPUT) return

        val keys = LetterBlockMapping.keysFor(current.scanSide == ScanSide.LEFT)
        val nextIndex = (current.highlightedSideKeyIndex + 1) % keys.size
        _state.value = current.copy(highlightedSideKeyIndex = nextIndex)
    }

    override fun onCleared() {
        super.onCleared()
        stopScanning()
    }

    private companion object {
        const val CHARS_PER_ROW = 4
    }
}

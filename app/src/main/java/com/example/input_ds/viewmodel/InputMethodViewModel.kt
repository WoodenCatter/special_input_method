package com.example.input_ds.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.engine.CharacterLookupEngine
import com.example.input_ds.engine.HybridPredictionProvider
import com.example.input_ds.engine.LocalPredictionProvider
import com.example.input_ds.engine.PinyinRecoveryEngine
import com.example.input_ds.engine.PredictionDebugInfo
import com.example.input_ds.engine.RimePredictionProvider
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

class InputMethodViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(InputState())
    val state: StateFlow<InputState> = _state

    private val _predictionDebugInfo = MutableStateFlow<PredictionDebugInfo?>(null)
    val predictionDebugInfo: StateFlow<PredictionDebugInfo?> = _predictionDebugInfo

    private val pinyinRecoveryEngine = PinyinRecoveryEngine()
    private val characterLookupEngine = CharacterLookupEngine()
    private val predictionProvider = HybridPredictionProvider(
        local = LocalPredictionProvider(),
        rime = RimePredictionProvider(application)
    )
    private val predictionDebugEnabled =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var scanJob: Job? = null
    private var pinyinScanJob: Job? = null
    private var charScanJob: Job? = null
    private var predScanJob: Job? = null
    private var predictionJob: Job? = null
    private var predictionDebugClearJob: Job? = null
    private var predictionRequestId = 0L
    private var predictionDebugEventId = 0L

    init { startScanning() }

    fun handleSignal(signal: ControlSignal) {
        when (_state.value.phase) {
            InputPhase.LEVEL_1_SCANNING -> handleLevel1Signal(signal)
            InputPhase.LEVEL_2_LETTER_SELECT -> handleLevel2Signal(signal)
            InputPhase.LEVEL_3_CHAR_SELECT -> handleLevel3Signal(signal)
            InputPhase.PREDICTION -> handlePredictionSignal(signal)
        }
    }

    // ==================== Level 1 ====================

    private fun handleLevel1Signal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                if (_state.value.scanSide == ScanSide.LEFT) selectBlock()
                else switchToLeftSide()
            }
            ControlSignal.RIGHT_LOOK -> {
                if (_state.value.scanSide == ScanSide.RIGHT) selectBlock()
                else switchToRightSide()
            }
            ControlSignal.BITE -> {
                if (hasActivePinyinInput(_state.value)) enterLevel2()
                else enterCommonPhraseSelection()
            }
        }
    }

    private fun selectBlock() {
        val s = _state.value
        val sideBlocks = if (s.scanSide == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS
                         else LetterBlockMapping.RIGHT_BLOCKS
        val idx = s.highlightedBlockIndex
        if (idx >= sideBlocks.size) return
        val block = sideBlocks[idx]

        if (block == LetterBlockMapping.SEND_BLOCK) { sendText(); return }
        if (block == LetterBlockMapping.DELETE_BLOCK) {
            if (hasActivePinyinInput(s) && s.selectedBlocks.isNotEmpty()) {
                _state.value = s.copy(selectedBlocks = s.selectedBlocks.dropLast(1), pinyinCombinations = emptyList())
                updateBackendCandidates()
            } else if (!hasActivePinyinInput(s) && s.outputText.isNotEmpty()) {
                _state.value = s.copy(outputText = removeLastCodePoint(s.outputText))
            }
            return
        }
        _state.value = s.copy(selectedBlocks = s.selectedBlocks + block, pinyinCombinations = emptyList())
        updateBackendCandidates()
    }

    private fun hasActivePinyinInput(state: InputState): Boolean {
        return state.selectedBlocks.isNotEmpty() ||
            state.currentPinyin.isNotEmpty() ||
            state.selectedLetters.isNotEmpty()
    }

    private fun enterCommonPhraseSelection() {
        stopScanning()
        val s = _state.value
        _state.value = s.copy(
            phase = InputPhase.PREDICTION,
            currentChar = "",
            predictionCandidates = CharacterDictionary.COMMON_PHRASES + listOf(CONTINUE_INPUT),
            highlightedPredictionIndex = 0,
            charScanDirection = 1
        )
        startPredScanning()
    }

    private fun removeLastCodePoint(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, text.offsetByCodePoints(text.length, -1))
    }

    private fun switchToLeftSide() {
        val idx = _state.value.highlightedBlockIndex
        _state.value = _state.value.copy(scanSide = ScanSide.LEFT, highlightedBlockIndex = idx)
        restartScanning()
    }

    private fun switchToRightSide() {
        val idx = _state.value.highlightedBlockIndex
        _state.value = _state.value.copy(scanSide = ScanSide.RIGHT, highlightedBlockIndex = idx)
        restartScanning()
    }

    private fun restartScanning() { stopScanning(); startScanning() }

    private fun enterLevel2() {
        stopScanning()
        val s = _state.value
        val combos = s.pinyinCandidates.filter { it.isNotEmpty() }.take(12).toList()
        if (combos.isEmpty()) { returnToLevel1(); return }
        val first = combos.first()
        val chars = loadCharsForPinyin(first, s)
        _state.value = s.copy(phase = InputPhase.LEVEL_2_LETTER_SELECT, pinyinCombinations = combos,
            highlightedPinyinIndex = 0, currentPinyin = first,
            charCandidates = chars.map { it.char }, charScanDirection = 1)
        startPinyinScanning()
    }

    // ==================== Level 2 ====================

    private fun handleLevel2Signal(signal: ControlSignal) {
        if (!_state.value.isCharFocused) handlePinyinSelection(signal)
        else handleCharSelection(signal)
    }

    private fun handlePinyinSelection(signal: ControlSignal) {
        val combos = _state.value.pinyinCombinations
        if (combos.isEmpty()) return
        when (signal) {
            ControlSignal.LEFT_LOOK ->
                _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK ->
                _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> {
                if (_state.value.highlightedPinyinIndex >= combos.size) returnToLevel1()
                else focusOnChars()
            }
        }
    }

    private fun handleCharSelection(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmCharacter()
        }
    }

    private fun focusOnChars() {
        stopPinyinScanning()
        stopCharScanning()
        _state.value = _state.value.copy(isCharFocused = true, highlightedCharIndex = 0, charScanDirection = 1)
        startCharScanning()
    }

    private fun loadCharsForPinyin(pinyin: String, state: InputState): List<com.example.input_ds.model.CharCandidate> {
        val prev = state.outputText.takeIf { it.isNotEmpty() }?.last()?.toString()
        // 精确匹配
        val exact = characterLookupEngine.lookupSingleChar(pinyin, prev)
        if (exact.isNotEmpty()) return exact
        // 单字母（如 w/x/y/z）：前缀匹配所有首字母相同的汉字
        if (pinyin.length == 1) {
            val ch = pinyin[0]
            return CharacterDictionary.PINYIN_TO_CHARS
                .filter { (k, _) -> k.startsWith(ch) }
                .flatMap { (_, entries) -> entries }
                .sortedByDescending { it.weight }
                .map { entry -> com.example.input_ds.model.CharCandidate(entry.char, pinyin, entry.weight.toDouble() / 100.0) }
                .distinctBy { it.char }
        }
        return exact
    }

    private fun switchPinyinHighlight(index: Int) {
        val s = _state.value
        val pinyin = s.pinyinCombinations.getOrNull(index) ?: return
        val chars = loadCharsForPinyin(pinyin, s)
        _state.value = s.copy(
            highlightedPinyinIndex = index, currentPinyin = pinyin,
            charCandidates = chars.map { it.char }, highlightedCharIndex = 0
        )
    }

    private fun advancePinyinHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_2_LETTER_SELECT || s.isCharFocused) return
        val combos = s.pinyinCombinations
        if (combos.isEmpty()) return
        val total = combos.size + 1
        val next =
            (s.highlightedPinyinIndex + s.charScanDirection + total) % total
        if (next < combos.size) {
            switchPinyinHighlight(next)
        } else {
            _state.value = s.copy(highlightedPinyinIndex = next)
        }
    }

    // ==================== Level 3 ====================

    private fun handleLevel3Signal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmCharacter()
        }
    }

    private fun advanceCharHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_2_LETTER_SELECT || !s.isCharFocused) return
        val chars = s.charCandidates; if (chars.isEmpty()) return
        val charsPerPage = 15
        val cp = s.highlightedCharIndex / charsPerPage
        val start = cp * charsPerPage
        val tp = (chars.size + charsPerPage - 1) / charsPerPage
        val hasPrev = cp > 0; val hasNext = cp < tp - 1
        val dc = charsPerPage - (if (hasPrev) 1 else 0) - (if (hasNext) 1 else 0) - 1
        val ac = minOf(dc, chars.size - start)
        val itemsOnPage = ac + (if (hasPrev) 1 else 0) + (if (hasNext) 1 else 0) + 1
        val li = s.highlightedCharIndex - start
        val ni = start + ((li + s.charScanDirection + itemsOnPage) % itemsOnPage)
        _state.value = s.copy(highlightedCharIndex = ni)
    }

    private fun confirmCharacter() {
        val s = _state.value; val idx = s.highlightedCharIndex
        val charsPerPage = 15
        val cp = idx / charsPerPage; val start = cp * charsPerPage
        val tp = (s.charCandidates.size + charsPerPage - 1) / charsPerPage
        val hasPrev = cp > 0; val hasNext = cp < tp - 1
        val dc = charsPerPage - (if (hasPrev) 1 else 0) - (if (hasNext) 1 else 0) - 1
        val ac = minOf(dc, s.charCandidates.size - start)
        val li = idx - start
        val prevSlot = if (hasPrev) ac else -1
        val nextSlot = if (hasNext) (if (hasPrev) ac + 1 else ac) else -1
        val backSlot = ac + (if (hasPrev) 1 else 0) + (if (hasNext) 1 else 0)
        if (hasPrev && li == prevSlot) { _state.value = s.copy(highlightedCharIndex = (cp - 1) * charsPerPage); return }
        if (hasNext && li == nextSlot) { _state.value = s.copy(highlightedCharIndex = (cp + 1) * charsPerPage); return }
        if (li == backSlot) {
            _state.value = s.copy(isCharFocused = false)
            stopCharScanning()
            startPinyinScanning()
            return
        }
        stopCharScanning()
        if (idx < s.charCandidates.size) {
            val ch = s.charCandidates[idx]
            val newOut = s.outputText + ch
            UserDictionary.record(takeLastCodePoints(newOut, 4))
            requestPredictions(s, newOut, ch)
        }
    }

    // ==================== Prediction ====================

    private fun handlePredictionSignal(signal: ControlSignal) {
        if (_state.value.predictionCandidates.isEmpty()) { returnToLevel1(); return }
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmPrediction()
        }
    }

    private fun startPredScanning() {
        predScanJob?.cancel()
        predScanJob = viewModelScope.launch {
            while (isActive) { delay(_state.value.scanIntervalMs); advancePredictionHighlight() }
        }
    }

    private fun stopPredScanning() { predScanJob?.cancel(); predScanJob = null }

    private fun advancePredictionHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.PREDICTION) return
        val preds = s.predictionCandidates; if (preds.isEmpty()) return
        val ni = (s.highlightedPredictionIndex + s.charScanDirection + preds.size) % preds.size
        _state.value = s.copy(highlightedPredictionIndex = ni)
    }

    private fun confirmPrediction() {
        val s = _state.value; val idx = s.highlightedPredictionIndex
        if (idx >= s.predictionCandidates.size) return
        val sel = s.predictionCandidates[idx]
        if (sel == CONTINUE_INPUT) { stopPredScanning(); returnToLevel1(); return }
        val suffix = sel
        val newOut = s.outputText + suffix
        if (suffix.isNotEmpty()) {
            val lastCodePoint = takeLastCodePoints(suffix, 1)
            UserDictionary.record(takeLastCodePoints(newOut, 4))
            requestPredictions(s, newOut, lastCodePoint)
        } else { stopPredScanning(); returnToLevel1() }
    }

    private fun requestPredictions(baseState: InputState, context: String, currentChar: String) {
        stopPredScanning()
        predictionJob?.cancel()
        val requestId = ++predictionRequestId
        _state.value = baseState.copy(
            phase = InputPhase.PREDICTION,
            isCharFocused = false,
            outputText = context,
            currentChar = currentChar,
            predictionCandidates = listOf(CONTINUE_INPUT),
            highlightedPredictionIndex = 0,
            charScanDirection = 1
        )
        predictionJob = viewModelScope.launch {
            val predictionBatch = predictionProvider.predictWithDebug(context, MAX_PREDICTIONS)
            val predictions = predictionBatch.candidates.map { it.text }
            val current = _state.value
            if (requestId != predictionRequestId ||
                current.phase != InputPhase.PREDICTION ||
                current.outputText != context
            ) return@launch

            _state.value = current.copy(
                predictionCandidates = predictions + CONTINUE_INPUT,
                highlightedPredictionIndex = 0,
                charScanDirection = 1
            )
            startPredScanning()
            if (predictionDebugEnabled) {
                val eventId = ++predictionDebugEventId
                _predictionDebugInfo.value = predictionBatch.debugInfo.copy(eventId = eventId)
                predictionDebugClearJob?.cancel()
                predictionDebugClearJob = viewModelScope.launch {
                    delay(PREDICTION_DEBUG_FALLBACK_CLEAR_MS)
                    clearPredictionDebugInfo(eventId)
                }
            }
        }
    }

    fun clearPredictionDebugInfo(eventId: Long) {
        if (_predictionDebugInfo.value?.eventId == eventId) {
            _predictionDebugInfo.value = null
        }
    }

    private fun takeLastCodePoints(text: String, count: Int): String {
        if (text.isEmpty() || count <= 0) return ""
        val codePointCount = text.codePointCount(0, text.length)
        if (codePointCount <= count) return text
        return text.substring(text.offsetByCodePoints(0, codePointCount - count))
    }

    private fun cancelPredictionRequest() {
        predictionRequestId++
        predictionJob?.cancel()
        predictionJob = null
    }

    // ==================== 退出 ====================

    private fun returnToLevel1() {
        cancelPredictionRequest()
        stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        _state.value = InputState(outputText = _state.value.outputText, scanIntervalMs = _state.value.scanIntervalMs)
        startScanning()
    }

    fun sendText() {
        cancelPredictionRequest()
        stopScanning(); stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        _state.value = InputState()
        startScanning()
    }

    fun adjustSpeed(faster: Boolean) {
        val cur = _state.value.scanIntervalMs
        _state.value = _state.value.copy(scanIntervalMs = if (faster) maxOf(300L, cur - 200L) else minOf(3000L, cur + 200L))
    }

    // ==================== 后台 ====================

    private fun isValidPinyinCombo(pinyin: String): Boolean {
        return CharacterDictionary.PINYIN_TO_CHARS.containsKey(pinyin)
    }

    private fun updateBackendCandidates() {
        val s = _state.value
        if (s.selectedBlocks.isEmpty()) { _state.value = s.copy(pinyinCandidates = emptyList(), charCandidates = emptyList()); return }
        val prev = s.outputText.takeIf { it.isNotEmpty() }?.last()?.toString()

        // 单块：显示所有字母+首字母匹配的全部汉字（模拟九键）
        if (s.selectedBlocks.size == 1) {
            val block = s.selectedBlocks.first()
            val letters = LetterBlockMapping.DIGIT_TO_LETTERS[block] ?: emptyList()
            val pinyins = letters.map { it.toString() }
            val chars = CharacterDictionary.PINYIN_TO_CHARS
                .filter { (pinyin, _) -> letters.any { l -> pinyin.startsWith(l) } }
                .flatMap { (_, entries) -> entries }
                .sortedByDescending { it.weight }
                .map { it.char }
                .distinct()
                .take(45)
            _state.value = s.copy(pinyinCandidates = pinyins, charCandidates = chars)
            return
        }

        // 多块：minLen = 块数（2块→≥2字母，3块→≥3字母）
        val cands = pinyinRecoveryEngine.recover(s.selectedBlocks)
        val minLen = s.selectedBlocks.size
        val pinyins = cands.map { it.letters }
            .filter { it.length >= minLen }
            .filter { isValidPinyinCombo(it) }
            .distinct()
        val chars = pinyins.take(3).flatMap { pinyin ->
            characterLookupEngine.lookupSingleChar(pinyin, prev).map { it.char }
        }.distinct()
        _state.value = s.copy(pinyinCandidates = pinyins.take(12), charCandidates = chars)
    }

    // ==================== 扫描定时器 ====================

    private fun startScanning() { scanJob?.cancel(); scanJob = viewModelScope.launch { while (isActive) { delay(_state.value.scanIntervalMs); advanceHighlight() } } }
    private fun stopScanning() { scanJob?.cancel(); scanJob = null }
    private fun startPinyinScanning() {
        pinyinScanJob?.cancel()
        pinyinScanJob = viewModelScope.launch {
            while (isActive) {
                delay(_state.value.scanIntervalMs)
                advancePinyinHighlight()
            }
        }
    }
    private fun stopPinyinScanning() {
        pinyinScanJob?.cancel()
        pinyinScanJob = null
    }
    private fun startCharScanning() { charScanJob?.cancel(); charScanJob = viewModelScope.launch { while (isActive) { delay(_state.value.scanIntervalMs); advanceCharHighlight() } } }
    private fun stopCharScanning() { charScanJob?.cancel(); charScanJob = null }

    private fun advanceHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_1_SCANNING) return
        val blocks = if (s.scanSide == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
        _state.value = s.copy(highlightedBlockIndex = (s.highlightedBlockIndex + 1) % blocks.size)
    }

    override fun onCleared() {
        cancelPredictionRequest()
        predictionDebugClearJob?.cancel()
        predictionProvider.close()
        stopScanning(); stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        super.onCleared()
    }

    private companion object {
        const val CONTINUE_INPUT = "继续输入"
        const val MAX_PREDICTIONS = 12
        const val PREDICTION_DEBUG_FALLBACK_CLEAR_MS = 4_500L
    }
}

package com.example.input_ds.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.input_ds.data.CharacterDictionary
import com.example.input_ds.data.CommonPhraseUsage
import com.example.input_ds.data.LetterBlockMapping
import com.example.input_ds.data.UserDictionary
import com.example.input_ds.engine.CharacterLookupEngine
import com.example.input_ds.engine.ConversationContext
import com.example.input_ds.engine.InitialPhraseIndex
import com.example.input_ds.engine.LlmPredictionProvider
import com.example.input_ds.engine.LocalPredictionProvider
import com.example.input_ds.engine.MutableConversationContextProvider
import com.example.input_ds.engine.PinyinRecoveryEngine
import com.example.input_ds.engine.PredictionCandidate
import com.example.input_ds.engine.PredictionDebugInfo
import com.example.input_ds.engine.PredictionDebugStatus
import com.example.input_ds.engine.PredictionItemKind
import com.example.input_ds.engine.PredictionSource
import com.example.input_ds.engine.RimePredictionProvider
import com.example.input_ds.engine.StablePredictionMerge
import com.example.input_ds.engine.contextLlmDebugStatus
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.model.InputMode
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.PredictionStatus
import com.example.input_ds.model.ScanCursor
import com.example.input_ds.model.ScanMode
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.model.ScanSide
import com.example.input_ds.model.SelectionItemAction
import com.example.input_ds.model.SelectionPaging
import com.example.input_ds.model.runScanTimer
import com.example.input_ds.personalization.ContextCompletionRequest
import com.example.input_ds.personalization.ConversationTurn
import com.example.input_ds.personalization.InitialSentenceRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class InputMethodViewModel(
    application: Application,
    private val remotePredictionsEnabled: Boolean,
    private val priorityPhrases: List<String> = emptyList()
) : AndroidViewModel(application) {

    constructor(application: Application) : this(application, true)

    private val _state = MutableStateFlow(
        InputState(
            scanIntervalMs = ScanSettings.read(application),
            scanMode = ScanSettings.readMode(application)
        )
    )
    val state: StateFlow<InputState> = _state
    private val _predictionDebugInfo = MutableStateFlow<PredictionDebugInfo?>(null)
    val predictionDebugInfo: StateFlow<PredictionDebugInfo?> = _predictionDebugInfo

    private val pinyinRecoveryEngine = PinyinRecoveryEngine()
    private val characterLookupEngine = CharacterLookupEngine()
    private val localPredictionProvider = LocalPredictionProvider()
    private val rimePredictionProvider = RimePredictionProvider.shared(application)
    private val llmPredictionProvider = LlmPredictionProvider(application)
    private val conversationContextProvider = MutableConversationContextProvider()
    private val initialPhraseIndex = InitialPhraseIndex(application)
    private val predictionDebugEnabled =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private val drafts = mutableMapOf<String, String>()
    private var activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size)
    private var scanJob: Job? = null
    private var flashJob: Job? = null
    private var predictionJob: Job? = null
    private var initialPredictionJob: Job? = null
    private var predictionDebugClearJob: Job? = null
    private var predictionRequestId = 0L
    private var predictionDebugEventId = 0L

    private var onSendCallback: ((String) -> Unit)? = null
    private var onBackCallback: (() -> Unit)? = null
    private var onToastCallback: ((String, Boolean) -> Unit)? = null
    private var inputVisible = false
    private var paused = false
    private var frozen = false
    private var graceMs = 400L

    fun setOnToast(callback: ((String, Boolean) -> Unit)?) {
        onToastCallback = callback
    }

    fun setScanMode(mode: ScanMode) {
        if (_state.value.scanMode == mode) return
        _state.value = _state.value.copy(scanMode = mode, dwellRevision = _state.value.dwellRevision + 1)
        activeCursor = activeCursor.copy(previousIndex = -1)
        restartActiveTimer()
    }

    fun setPaused(value: Boolean) {
        if (paused == value) return
        paused = value
        activeCursor = activeCursor.copy(previousIndex = -1)
        if (paused) {
            stopActiveTimer()
        } else {
            _state.value = _state.value.copy(dwellRevision = _state.value.dwellRevision + 1)
            restartActiveTimer()
        }
    }

    fun setFrozen(value: Boolean) {
        if (frozen == value) return
        frozen = value
        activeCursor = activeCursor.copy(previousIndex = -1)
        _state.value = _state.value.copy(dwellRevision = _state.value.dwellRevision + 1)
        restartActiveTimer()
    }

    fun setGraceMs(ms: Long) {
        graceMs = ms.coerceAtLeast(0L)
    }

    /** Activates unified composer in Talk / Chat / Assistant mode while preserving per-object drafts. */
    fun openMode(
        mode: InputMode,
        initialDraft: String? = null,
        onSend: ((String) -> Unit)? = null,
        onBack: (() -> Unit)? = null
    ) {
        saveCurrentDraft()
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopActiveTimer()
        onSendCallback = onSend
        onBackCallback = onBack
        inputVisible = true
        val draft = initialDraft ?: drafts[mode.draftKey].orEmpty()
        drafts[mode.draftKey] = draft
        activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size, index = 0, direction = 1)
        val current = _state.value
        _state.value = InputState(
            mode = mode,
            scanMode = current.scanMode,
            phase = InputPhase.LEVEL_1_SCANNING,
            scanSide = ScanSide.LEFT,
            highlightedBlockIndex = 0,
            outputText = draft,
            scanIntervalMs = current.scanIntervalMs,
            dwellRevision = current.dwellRevision + 1
        )
        restartActiveTimer()
    }

    fun deactivate() {
        saveCurrentDraft()
        inputVisible = false
        stopActiveTimer()
    }

    fun saveCurrentDraft() {
        val s = _state.value
        drafts[s.mode.draftKey] = s.outputText
    }

    fun draftFor(key: String): String = drafts[key].orEmpty()

    fun setDraftFor(key: String, text: String) {
        drafts[key] = text
        if (_state.value.mode.draftKey == key && _state.value.outputText != text) {
            _state.value = _state.value.copy(outputText = text)
        }
    }

    fun clearOutputAndReset() {
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopActiveTimer()
        val s = _state.value
        drafts[s.mode.draftKey] = ""
        activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size, index = 0, direction = 1)
        _state.value = InputState(
            mode = s.mode,
            scanMode = s.scanMode,
            phase = InputPhase.LEVEL_1_SCANNING,
            scanSide = ScanSide.LEFT,
            highlightedBlockIndex = 0,
            outputText = "",
            scanIntervalMs = s.scanIntervalMs,
            dwellRevision = s.dwellRevision + 1
        )
        restartActiveTimer()
    }

    fun loadDraft(text: String) {
        val s = _state.value
        drafts[s.mode.draftKey] = text
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopActiveTimer()
        activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size, index = 0, direction = 1)
        _state.value = InputState(
            mode = s.mode,
            scanMode = s.scanMode,
            phase = InputPhase.LEVEL_1_SCANNING,
            scanSide = ScanSide.LEFT,
            highlightedBlockIndex = 0,
            outputText = text,
            scanIntervalMs = s.scanIntervalMs,
            dwellRevision = s.dwellRevision + 1
        )
        restartActiveTimer()
    }

    /** Handles system Back inside composer: collapses L2+ back to L1 first; returns false when already at L1. */
    fun handleBackPress(): Boolean {
        val s = _state.value
        if (s.phase == InputPhase.LEVEL_1_SCANNING) {
            triggerBack()
            return true
        }
        if (s.phase == InputPhase.LEVEL_2_LETTER_SELECT && s.isCharFocused) {
            val backIdx = (s.pinyinCombinations.indexOf(s.currentPinyin) + 1).coerceAtLeast(1)
            focusPinyinLayer(backIdx)
        } else {
            returnToLevel1()
        }
        return true
    }

    fun debugTick() {
        advanceActiveCursor(nowMs = 0L)
    }

    fun handleSignal(signal: ControlSignal) = handleSignal(signal, ::triggerSend)

    fun handleSignal(signal: ControlSignal, onSubmit: () -> Unit) {
        val current = _state.value
        if (current.phase == InputPhase.LEVEL_1_SCANNING) {
            handleLevel1Signal(signal, onSubmit)
            return
        }
        handleCandidateSignal(current, signal, onSubmit)
    }

    private fun handleLevel1Signal(signal: ControlSignal, onSubmit: () -> Unit) {
        val s = _state.value
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                if (s.scanSide == ScanSide.LEFT) selectHighlightedBlock(onSubmit)
                else switchSide(ScanSide.LEFT)
            }
            ControlSignal.RIGHT_LOOK -> {
                if (s.scanSide == ScanSide.RIGHT) selectHighlightedBlock(onSubmit)
                else switchSide(ScanSide.RIGHT)
            }
            ControlSignal.BITE -> {
                if (hasActivePinyinInput(s)) enterLevel2()
                else enterCommonPhraseSelection()
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun handleCandidateSignal(current: InputState, signal: ControlSignal, onSubmit: () -> Unit) {
        val mode = current.scanMode
        when (signal) {
            ControlSignal.LEFT_LOOK, ControlSignal.RIGHT_LOOK -> {
                val step = if (signal == ControlSignal.LEFT_LOOK) -1 else 1
                if (mode == ScanMode.MANUAL) {
                    moveCandidateManually(current, step)
                } else {
                    val redirected = redirectCandidateScan(current, signal) ?: return
                    activeCursor = activeCursor.look(step, ScanMode.AUTO)
                    _state.value = redirected.copy(dwellRevision = current.dwellRevision + 1)
                    restartActiveTimer()
                }
            }
            ControlSignal.BITE -> {
                val now = SystemClock.elapsedRealtime()
                val effectiveGrace = if (frozen) 0L else graceMs
                val chosen = activeCursor.selected(now, effectiveGrace, mode)
                if (chosen != activeCursor.index) {
                    applyCandidateIndex(current, chosen)
                    notifyToast("已按上一项执行（延迟补偿）", false)
                }
                activeCursor = activeCursor.copy(previousIndex = -1)
                val latest = _state.value
                when (latest.phase) {
                    InputPhase.LEVEL_2_LETTER_SELECT ->
                        if (latest.isCharFocused) confirmCharacter() else confirmPinyin(latest)
                    InputPhase.LEVEL_3_CHAR_SELECT -> confirmCharacter()
                    InputPhase.PREDICTION -> confirmPrediction(onSubmit)
                    InputPhase.INITIAL_PREDICTION -> confirmInitialPrediction()
                    InputPhase.LEVEL_1_SCANNING -> Unit
                }
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun moveCandidateManually(s: InputState, step: Int) {
        val count = candidateItemCount(s)
        if (count <= 0) return
        activeCursor = activeCursor.copy(size = count, index = currentCandidateIndex(s)).look(step, ScanMode.MANUAL)
        applyCandidateIndex(s, activeCursor.index)
    }

    private fun applyCandidateIndex(s: InputState, index: Int) {
        val next = index.coerceIn(0, (candidateItemCount(s) - 1).coerceAtLeast(0))
        activeCursor = activeCursor.copy(index = next)
        when {
            s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused -> {
                if (next in 1..s.pinyinCombinations.size) switchPinyinHighlight(next)
                else _state.value = s.copy(highlightedPinyinIndex = next, dwellRevision = s.dwellRevision + 1)
            }
            s.phase == InputPhase.LEVEL_2_LETTER_SELECT || s.phase == InputPhase.LEVEL_3_CHAR_SELECT ->
                _state.value = s.copy(highlightedCharIndex = next, dwellRevision = s.dwellRevision + 1)
            s.phase == InputPhase.PREDICTION ->
                _state.value = s.copy(highlightedPredictionIndex = next, dwellRevision = s.dwellRevision + 1)
            s.phase == InputPhase.INITIAL_PREDICTION ->
                _state.value = s.copy(highlightedInitialIndex = next, dwellRevision = s.dwellRevision + 1)
            else -> Unit
        }
    }

    private fun selectHighlightedBlock(onSubmit: () -> Unit) {
        val s = _state.value
        val sideBlocks = if (s.scanSide == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS
        else LetterBlockMapping.RIGHT_BLOCKS
        val now = SystemClock.elapsedRealtime()
        val effectiveGrace = if (frozen) 0L else graceMs
        val chosen = activeCursor.selected(now, effectiveGrace, ScanMode.AUTO)
        if (chosen != activeCursor.index && chosen in sideBlocks.indices) {
            activeCursor = activeCursor.copy(index = chosen, previousIndex = -1)
            _state.value = s.copy(highlightedBlockIndex = chosen)
            notifyToast("已按上一项执行（延迟补偿）", false)
        }
        val idx = _state.value.highlightedBlockIndex
        if (idx !in sideBlocks.indices) return
        applyBlock(sideBlocks[idx], onSubmit)
    }

    private fun applyBlock(block: Int, onSubmit: () -> Unit) {
        val s = _state.value
        flashBlock(block)
        when (block) {
            LetterBlockMapping.SEND_BLOCK -> {
                onSubmit()
                return
            }
            LetterBlockMapping.BACK_BLOCK -> {
                triggerBack()
                return
            }
            LetterBlockMapping.PHRASES_BLOCK -> {
                _state.value = s.copy(selectedBlocks = emptyList(), pinyinCandidates = emptyList(), charCandidates = emptyList())
                enterCommonPhraseSelection()
                return
            }
            LetterBlockMapping.DELETE_BLOCK -> {
                if (s.selectedBlocks.isNotEmpty()) {
                    _state.value = s.copy(
                        selectedBlocks = s.selectedBlocks.dropLast(1),
                        pinyinCombinations = emptyList(),
                        dwellRevision = s.dwellRevision + 1
                    )
                    updateBackendCandidates()
                } else if (s.outputText.isNotEmpty()) {
                    val trimmed = removeLastCodePoint(s.outputText)
                    drafts[s.mode.draftKey] = trimmed
                    _state.value = s.copy(outputText = trimmed, dwellRevision = s.dwellRevision + 1)
                } else {
                    notifyToast("没有可以删除的内容", false)
                }
                activeCursor = activeCursor.copy(previousIndex = -1)
                restartActiveTimer()
                return
            }
            else -> {
                if (s.selectedBlocks.size >= MAX_INITIAL_BLOCKS) {
                    notifyToast("已达字母块上限", false)
                } else {
                    _state.value = s.copy(
                        selectedBlocks = s.selectedBlocks + block,
                        pinyinCombinations = emptyList(),
                        dwellRevision = s.dwellRevision + 1
                    )
                    updateBackendCandidates()
                }
                activeCursor = activeCursor.copy(previousIndex = -1)
                restartActiveTimer()
            }
        }
    }

    private fun flashBlock(block: Int) {
        flashJob?.cancel()
        _state.value = _state.value.copy(flashedBlock = block)
        if (frozen) return
        flashJob = viewModelScope.launch {
            delay(450L)
            if (_state.value.flashedBlock == block) {
                _state.value = _state.value.copy(flashedBlock = null)
            }
        }
    }

    private fun triggerSend() {
        val s = _state.value
        val callback = onSendCallback
        if (callback != null) {
            callback(s.outputText)
        } else {
            sendText()
        }
    }

    private fun triggerBack() {
        saveCurrentDraft()
        val s = _state.value
        if (s.outputText.isNotEmpty()) {
            notifyToast(
                if (s.mode is InputMode.Assistant) "文字已放进草稿" else "没写完的话会保留",
                true
            )
        }
        onBackCallback?.invoke()
    }

    /** Touch counterpart of the BCI block selection path. */
    fun selectBlockByTouch(block: Int) = selectBlockByTouch(block, ::triggerSend)

    fun selectBlockByTouch(block: Int, onSubmit: () -> Unit) {
        if (block !in LetterBlockMapping.LEFT_BLOCKS && block !in LetterBlockMapping.RIGHT_BLOCKS) return
        if (_state.value.phase != InputPhase.LEVEL_1_SCANNING) returnToLevel1()
        val side = if (block in LetterBlockMapping.LEFT_BLOCKS) ScanSide.LEFT else ScanSide.RIGHT
        val sideBlocks = if (side == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
        val idx = sideBlocks.indexOf(block).coerceAtLeast(0)
        activeCursor = ScanCursor(sideBlocks.size, index = idx, direction = 1)
        _state.value = _state.value.copy(
            scanSide = side,
            highlightedBlockIndex = idx
        )
        applyBlock(block, onSubmit)
    }

    /** Selects a visible pinyin combination directly by touch. */
    fun selectPinyinByTouch(pinyin: String) {
        if (_state.value.phase == InputPhase.LEVEL_1_SCANNING) enterLevel2()
        val state = _state.value
        if (state.phase != InputPhase.LEVEL_2_LETTER_SELECT) return
        val index = state.pinyinCombinations.indexOf(pinyin)
        if (index < 0) return
        switchPinyinHighlight(index + 1)
        focusOnChars()
    }

    /** Handles the Return and Initials cells shown beside pinyin combinations. */
    fun selectPinyinNavigationByTouch(index: Int) {
        val state = _state.value
        if (state.phase != InputPhase.LEVEL_2_LETTER_SELECT) return
        when (index) {
            0 -> returnToLevel1()
            state.pinyinCombinations.size + 1 -> {
                if (state.hasInitialPredictionOption) enterInitialPrediction()
            }
        }
    }

    /** Commits a visible character candidate directly. */
    fun selectCharacterByTouch(character: String) {
        val state = _state.value
        if (character !in state.charCandidates) return
        stopActiveTimer()
        commitText(state, character)
    }

    /** Commits a common phrase directly from the level-one preview grid. */
    fun selectCommonPhraseByTouch(phrase: String) {
        val state = _state.value
        if (state.phase != InputPhase.LEVEL_1_SCANNING) return
        if (phrase !in rankedCommonPhrases()) return
        stopActiveTimer()
        if (!state.mode.usesAssistantPhrases) CommonPhraseUsage.record(phrase)
        commitText(state, phrase)
    }

    private fun switchSide(side: ScanSide) {
        val s = _state.value
        activeCursor = activeCursor.copy(size = 6, index = s.highlightedBlockIndex, previousIndex = -1)
        _state.value = s.copy(scanSide = side, dwellRevision = s.dwellRevision + 1)
        restartActiveTimer()
    }

    private fun hasActivePinyinInput(state: InputState): Boolean =
        state.selectedBlocks.isNotEmpty() ||
            state.currentPinyin.isNotEmpty() ||
            state.selectedLetters.isNotEmpty()

    internal fun rankedCommonPhrases(mode: InputMode = _state.value.mode): List<String> {
        if (mode.usesAssistantPhrases) return CharacterDictionary.ASSISTANT_PHRASES
        if (priorityPhrases.isNotEmpty()) {
            return (CommonPhraseUsage.sorted(priorityPhrases, CharacterDictionary.URGENT_PHRASE_SET) +
                CommonPhraseUsage.sorted(CharacterDictionary.COMMON_PHRASES, CharacterDictionary.URGENT_PHRASE_SET)).distinct()
        }
        return CommonPhraseUsage.sorted(
            CharacterDictionary.COMMON_PHRASES,
            urgentPinned = CharacterDictionary.URGENT_PHRASE_SET
        )
    }

    private fun enterCommonPhraseSelection() {
        stopActiveTimer()
        val s = _state.value
        val phrases = rankedCommonPhrases(s.mode).map { phrase ->
            PredictionCandidate(
                text = phrase,
                source = PredictionSource.USER,
                id = "common:$phrase",
                kind = PredictionItemKind.COMMON_PHRASE
            )
        }
        val items = SelectionPaging.commonPhraseItems(
            phrases,
            0,
            if (s.mode.usesAssistantPhrases) emptySet() else CharacterDictionary.URGENT_PHRASE_SET
        )
        val initial = if (items.size > 1) 1 else 0
        activeCursor = ScanCursor(items.size, index = initial, direction = 1)
        _state.value = s.copy(
            phase = InputPhase.PREDICTION,
            currentChar = "",
            predictionCandidates = phrases,
            highlightedPredictionIndex = initial,
            predictionPage = 0,
            isCommonPhraseSelection = true,
            charScanDirection = 1,
            dwellRevision = s.dwellRevision + 1
        )
        restartActiveTimer()
    }

    private fun removeLastCodePoint(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, text.offsetByCodePoints(text.length, -1))
    }

    private fun enterLevel2() {
        stopActiveTimer()
        val s = _state.value
        val blocksKey = s.selectedBlocks.joinToString("")
        prepareInitialPrediction(s, blocksKey)
        val preparedState = _state.value
        val combos = s.pinyinCandidates.filter { it.isNotEmpty() }.take(12).toList()
        if (s.selectedBlocks.size > MAX_SINGLE_SYLLABLE_BLOCKS) {
            enterInitialPrediction()
            return
        }
        if (combos.isEmpty() && !preparedState.hasInitialPredictionOption) {
            notifyToast("这几个字母块拼不出拼音，可用「删除」去掉", false)
            restartActiveTimer()
            return
        }
        if (combos.isEmpty()) {
            val total = if (preparedState.hasInitialPredictionOption) 2 else 1
            activeCursor = ScanCursor(total, index = 0, direction = 1)
            _state.value = preparedState.copy(
                phase = InputPhase.LEVEL_2_LETTER_SELECT,
                isCharFocused = false,
                pinyinCombinations = emptyList(),
                highlightedPinyinIndex = 0,
                currentPinyin = "",
                charCandidates = emptyList(),
                charPage = 0,
                charScanDirection = 1,
                dwellRevision = preparedState.dwellRevision + 1
            )
            restartActiveTimer()
            return
        }
        val first = combos.first()
        val chars = loadCharsForPinyin(first, preparedState)
        val total = combos.size + if (preparedState.hasInitialPredictionOption) 2 else 1
        activeCursor = ScanCursor(total, index = 1, direction = 1)
        _state.value = preparedState.copy(
            phase = InputPhase.LEVEL_2_LETTER_SELECT,
            isCharFocused = false,
            pinyinCombinations = combos,
            highlightedPinyinIndex = 1,
            currentPinyin = first,
            charCandidates = chars.map { it.char },
            charPage = 0,
            charScanDirection = 1,
            dwellRevision = preparedState.dwellRevision + 1
        )
        restartActiveTimer()
    }

    private fun focusPinyinLayer(focusIndex: Int) {
        stopActiveTimer()
        val s = _state.value
        val total = s.pinyinCombinations.size + if (s.hasInitialPredictionOption) 2 else 1
        val clamped = focusIndex.coerceIn(0, (total - 1).coerceAtLeast(0))
        activeCursor = ScanCursor(total, index = clamped, direction = 1)
        _state.value = s.copy(
            phase = InputPhase.LEVEL_2_LETTER_SELECT,
            isCharFocused = false,
            highlightedPinyinIndex = clamped,
            charPage = 0,
            highlightedCharIndex = 0,
            charScanDirection = 1,
            dwellRevision = s.dwellRevision + 1
        )
        if (clamped in 1..s.pinyinCombinations.size) switchPinyinHighlight(clamped)
        restartActiveTimer()
    }

    // ==================== Level 2 ====================

    private fun confirmPinyin(state: InputState) {
        when (state.highlightedPinyinIndex) {
            0 -> returnToLevel1()
            state.pinyinCombinations.size + 1 -> enterInitialPrediction()
            else -> focusOnChars()
        }
    }

    private fun focusOnChars() {
        stopActiveTimer()
        val s = _state.value
        val items = SelectionPaging.characterItems(s.charCandidates, 0)
        val initial = if (items.size > 1) 1 else 0
        activeCursor = ScanCursor(items.size, index = initial, direction = 1)
        _state.value = s.copy(
            isCharFocused = true,
            highlightedCharIndex = initial,
            charPage = 0,
            charScanDirection = 1,
            dwellRevision = s.dwellRevision + 1
        )
        restartActiveTimer()
    }

    private fun loadCharsForPinyin(pinyin: String, state: InputState): List<com.example.input_ds.model.CharCandidate> {
        val prev = state.outputText.takeIf { it.isNotEmpty() }?.last()?.toString()
        val exact = characterLookupEngine.lookupSingleChar(pinyin, prev)
        if (exact.isNotEmpty()) return exact
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
        val pinyin = s.pinyinCombinations.getOrNull(index - 1) ?: return
        val chars = loadCharsForPinyin(pinyin, s)
        _state.value = s.copy(
            highlightedPinyinIndex = index,
            currentPinyin = pinyin,
            charCandidates = chars.map { it.char },
            highlightedCharIndex = 0,
            charPage = 0
        )
    }

    // ==================== Level 3 ====================

    private fun confirmCharacter() {
        val s = _state.value
        val item = SelectionPaging.characterItems(s.charCandidates, s.charPage)
            .getOrNull(s.highlightedCharIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopActiveTimer()
                val pageCount = SelectionPaging.totalPages(s.charCandidates.size, SelectionPaging.CHARACTERS_PER_PAGE)
                val nextPage = if (s.charPage + 1 < pageCount) s.charPage + 1 else 0
                val nextItems = SelectionPaging.characterItems(s.charCandidates, nextPage)
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = s.copy(
                    charPage = nextPage,
                    highlightedCharIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = s.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopActiveTimer()
                val nextPage = (s.charPage - 1).coerceAtLeast(0)
                val nextItems = SelectionPaging.characterItems(s.charCandidates, nextPage)
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = s.copy(
                    charPage = nextPage,
                    highlightedCharIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = s.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.BACK -> {
                val backIdx = (s.pinyinCombinations.indexOf(s.currentPinyin) + 1).coerceAtLeast(1)
                focusPinyinLayer(backIdx)
            }
            SelectionItemAction.SELECT -> {
                val ch = s.charCandidates.getOrNull(item.sourceIndex) ?: return
                stopActiveTimer()
                commitText(s, ch)
            }
            else -> Unit
        }
    }

    // ==================== Prediction ====================

    private fun commitText(baseState: InputState, addition: String) {
        val newOutput = baseState.outputText + addition
        drafts[baseState.mode.draftKey] = newOutput
        UserDictionary.record(takeLastCodePoints(newOutput, 4))
        notifyToast("已写入「$addition」", true)
        requestPredictions(baseState, newOutput, takeLastCodePoints(addition, 1))
    }

    private fun confirmPrediction(onSubmit: () -> Unit = ::triggerSend) {
        val s = _state.value
        val item = predictionItems(s).getOrNull(s.highlightedPredictionIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopActiveTimer()
                val pageCount = SelectionPaging.totalPages(s.predictionCandidates.size, SelectionPaging.PHRASES_PER_PAGE)
                val nextPage = if (s.predictionPage + 1 < pageCount) s.predictionPage + 1 else 0
                val nextItems = SelectionPaging.commonPhraseItems(
                    s.predictionCandidates,
                    nextPage,
                    if (s.mode.usesAssistantPhrases) emptySet() else CharacterDictionary.URGENT_PHRASE_SET
                )
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = s.copy(
                    predictionPage = nextPage,
                    highlightedPredictionIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = s.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopActiveTimer()
                val nextPage = (s.predictionPage - 1).coerceAtLeast(0)
                val nextItems = SelectionPaging.commonPhraseItems(s.predictionCandidates, nextPage)
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = s.copy(
                    predictionPage = nextPage,
                    highlightedPredictionIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = s.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.BACK, SelectionItemAction.CONTINUE_INPUT -> {
                stopActiveTimer()
                returnToLevel1()
            }
            SelectionItemAction.SEND -> {
                onSubmit()
            }
            SelectionItemAction.SELECT -> {
                val candidate = item.sourceId?.let { id ->
                    s.predictionCandidates.firstOrNull { it.id == id }
                } ?: return
                if (s.isCommonPhraseSelection && !s.mode.usesAssistantPhrases) {
                    CommonPhraseUsage.record(candidate.text)
                }
                if (candidate.text.isNotEmpty()) {
                    stopActiveTimer()
                    commitText(s, candidate.text)
                } else {
                    stopActiveTimer()
                    returnToLevel1()
                }
            }
        }
    }

    /** Touch counterpart for prediction, common-phrase and their navigation cells. */
    fun selectPredictionByTouch(index: Int) {
        val state = _state.value
        if (state.phase != InputPhase.PREDICTION || index !in predictionItems(state).indices) return
        _state.value = state.copy(highlightedPredictionIndex = index)
        confirmPrediction()
    }

    /** Touch counterpart for character grid items including Back / Next page. */
    fun selectCharacterItemByTouch(index: Int) {
        val state = _state.value
        val items = SelectionPaging.characterItems(state.charCandidates, state.charPage)
        if ((state.phase != InputPhase.LEVEL_2_LETTER_SELECT || !state.isCharFocused) &&
            state.phase != InputPhase.LEVEL_3_CHAR_SELECT
        ) return
        if (index !in items.indices) return
        _state.value = state.copy(highlightedCharIndex = index)
        confirmCharacter()
    }

    private fun predictionItems(state: InputState) =
        if (state.isCommonPhraseSelection) {
            SelectionPaging.commonPhraseItems(
                state.predictionCandidates,
                state.predictionPage,
                if (state.mode.usesAssistantPhrases) emptySet() else CharacterDictionary.URGENT_PHRASE_SET
            )
        } else {
            SelectionPaging.predictionItems(state.predictionCandidates, state.mode.sendLabel)
        }

    private fun requestPredictions(baseState: InputState, context: String, currentChar: String) {
        stopActiveTimer()
        cancelInitialPredictionRequest()
        predictionJob?.cancel()
        val requestSequence = ++predictionRequestId
        val requestId = "ctx-${UUID.randomUUID()}"
        val emptyItems = SelectionPaging.predictionItems(emptyList(), baseState.mode.sendLabel)
        activeCursor = ScanCursor(emptyItems.size, index = 0, direction = 1)
        _state.value = baseState.copy(
            phase = InputPhase.PREDICTION,
            isCharFocused = false,
            selectedBlocks = emptyList(),
            pinyinCandidates = emptyList(),
            pinyinCombinations = emptyList(),
            currentPinyin = "",
            charCandidates = emptyList(),
            outputText = context,
            currentChar = currentChar,
            predictionCandidates = emptyList(),
            highlightedPredictionIndex = 0,
            predictionPage = 0,
            isCommonPhraseSelection = false,
            charScanDirection = 1,
            dwellRevision = baseState.dwellRevision + 1
        )
        predictionJob = viewModelScope.launch {
            val localCandidates = localPredictionProvider.predict(context, MAX_PREDICTIONS * 2)
                .distinctBy { it.text }
                .take(MAX_VISIBLE_PREDICTIONS)
            if (!isCurrentContextRequest(requestSequence, context)) return@launch
            val initialItems = SelectionPaging.predictionItems(localCandidates, _state.value.mode.sendLabel)
            val initialIndex = if (localCandidates.isNotEmpty()) 1 else 0
            activeCursor = ScanCursor(initialItems.size, index = initialIndex, direction = 1)
            _state.value = _state.value.copy(
                predictionCandidates = localCandidates,
                highlightedPredictionIndex = initialIndex,
                predictionPage = 0,
                charScanDirection = 1,
                dwellRevision = _state.value.dwellRevision + 1
            )
            restartActiveTimer()

            coroutineScope {
                launch {
                    val rimeBatch = rimePredictionProvider.predictWithDebug(
                        context,
                        MAX_PREDICTIONS * 2
                    )
                    if (!isCurrentContextRequest(requestSequence, context)) return@launch
                    val added = appendContextCandidates(
                        requestSequence,
                        context,
                        rimeBatch.candidates
                    )
                    if (predictionDebugEnabled) {
                        val diagnostics = rimeBatch.diagnostics
                        publishPredictionDebug(
                            PredictionDebugInfo(
                                contextPreview = takeLastCodePoints(context, 12),
                                submittedRimeContext = diagnostics.submittedContext,
                                localCount = localCandidates.size,
                                rimeRawCount = diagnostics.rawCount,
                                rimeConvertedCount = diagnostics.convertedCount,
                                rimeFilteredCount = diagnostics.filteredCount,
                                rimeMergedCount = added,
                                mergedCount = _state.value.predictionCandidates.size,
                                elapsedMs = diagnostics.elapsedMs,
                                status = diagnostics.status,
                                message = diagnostics.message,
                                requestId = requestId
                            )
                        )
                    }
                }
                launch {
                    if (!remotePredictionsEnabled) return@launch
                    val conversation = conversationContextProvider.getContext()
                    val serverContext = takeLastCodePoints(context, MAX_SERVER_TEXT_CODE_POINTS)
                    val result = llmPredictionProvider.predictContext(
                        ContextCompletionRequest(
                            requestId = requestId,
                            currentText = serverContext,
                            conversation = conversation.turns.takeLast(MAX_CONVERSATION_TURNS).map { turn ->
                                turn.copy(text = takeLastCodePoints(turn.text, MAX_SERVER_TEXT_CODE_POINTS))
                            }.filter { it.role == "user" || it.role == "other" },
                            localCandidates = localCandidates.map { serverContext + it.text }
                                .filter { it.codePointCount(0, it.length) <= MAX_SERVER_CANDIDATE_CODE_POINTS }
                                .take(MAX_SERVER_CANDIDATES),
                            knownEntities = conversation.knownEntities
                                .filter { it.isNotBlank() && it.codePointCount(0, it.length) <= MAX_SERVER_CANDIDATE_CODE_POINTS }
                                .take(MAX_SERVER_CANDIDATES),
                            clientTime = currentClientTime(),
                            limit = MAX_PREDICTIONS
                        )
                    )
                    if (!isCurrentContextRequest(requestSequence, context)) return@launch
                    val response = result.value
                    val remoteCandidates = if (
                        response != null &&
                        response.requestId == requestId &&
                        response.currentText == serverContext
                    ) {
                        response.candidates.mapNotNull { candidate ->
                            if (!candidate.fullText.startsWith(serverContext) ||
                                candidate.fullText != serverContext + candidate.appendText ||
                                !isDisplayablePrediction(candidate.appendText)
                            ) return@mapNotNull null
                            PredictionCandidate(
                                text = candidate.appendText,
                                source = PredictionSource.LLM,
                                id = candidate.id,
                                kind = PredictionItemKind.CONTEXT_COMPLETION
                            )
                        }
                    } else {
                        emptyList()
                    }
                    val added = appendContextCandidates(requestSequence, context, remoteCandidates)
                    if (predictionDebugEnabled) {
                        val rawCount = response?.candidates?.size ?: 0
                        val status = contextLlmDebugStatus(
                            responseAvailable = response != null,
                            rawCount = rawCount,
                            acceptedCount = remoteCandidates.size,
                            addedCount = added
                        )
                        val diagnosticMessage = result.error ?: when (status) {
                            PredictionDebugStatus.LLM_EMPTY ->
                                "服务端请求成功，但返回的 candidates 为空；未发生追加"
                            PredictionDebugStatus.LLM_RESULTS_FILTERED ->
                                "服务端返回了候选，但响应标识、前缀、长度或字符校验未通过"
                            PredictionDebugStatus.LLM_NO_NEW_CANDIDATES ->
                                "LLM候选与现有候选重复，或可见候选列表已满"
                            else -> null
                        }
                        publishPredictionDebug(
                            PredictionDebugInfo(
                                contextPreview = takeLastCodePoints(context, 12),
                                submittedRimeContext = "",
                                localCount = localCandidates.size,
                                rimeRawCount = -1,
                                rimeConvertedCount = -1,
                                rimeFilteredCount = -1,
                                rimeMergedCount = 0,
                                mergedCount = _state.value.predictionCandidates.size,
                                elapsedMs = 0L,
                                status = status,
                                message = diagnosticMessage,
                                requestId = requestId,
                                llmRawCount = rawCount,
                                llmAcceptedCount = remoteCandidates.size,
                                llmMergedCount = added,
                                llmElapsedMs = response?.timings?.totalMs?.toLong() ?: 0L
                            )
                        )
                    }
                }
            }
        }
    }

    private fun isCurrentContextRequest(requestSequence: Long, context: String): Boolean {
        val current = _state.value
        return requestSequence == predictionRequestId &&
            current.phase == InputPhase.PREDICTION &&
            !current.isCommonPhraseSelection &&
            current.outputText == context
    }

    private fun appendContextCandidates(
        requestSequence: Long,
        context: String,
        candidates: List<PredictionCandidate>
    ): Int {
        if (!isCurrentContextRequest(requestSequence, context)) return 0
        val current = _state.value
        val merge = StablePredictionMerge.append(
            existing = current.predictionCandidates,
            incoming = candidates,
            limit = MAX_VISIBLE_PREDICTIONS,
            isValid = { isDisplayablePrediction(it.text) }
        )
        if (merge.addedCount > 0) {
            val updatedItems = SelectionPaging.predictionItems(merge.candidates, current.mode.sendLabel)
            activeCursor = activeCursor.copy(size = updatedItems.size)
            _state.value = current.copy(predictionCandidates = merge.candidates)
        }
        return merge.addedCount
    }

    private fun isDisplayablePrediction(text: String): Boolean {
        if (text.isEmpty() || text.any { it.isWhitespace() }) return false
        val codePoints = text.codePointCount(0, text.length)
        if (codePoints !in 1..MAX_PREDICTION_CODE_POINTS) return false
        var offset = 0
        while (offset < text.length) {
            val codePoint = text.codePointAt(offset)
            if (Character.UnicodeScript.of(codePoint) != Character.UnicodeScript.HAN) return false
            offset += Character.charCount(codePoint)
        }
        return true
    }

    private fun publishPredictionDebug(info: PredictionDebugInfo) {
        val eventId = ++predictionDebugEventId
        _predictionDebugInfo.value = info.copy(eventId = eventId)
        predictionDebugClearJob?.cancel()
        predictionDebugClearJob = viewModelScope.launch {
            delay(PREDICTION_DEBUG_FALLBACK_CLEAR_MS)
            clearPredictionDebugInfo(eventId)
        }
    }

    private fun currentClientTime(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date())

    // ==================== Initial sentence ====================

    private fun prepareInitialPrediction(baseState: InputState, blocksKey: String) {
        initialPredictionJob?.cancel()
        if (blocksKey.isEmpty()) return
        val requestId = "init-${UUID.randomUUID()}"
        val localValues = initialPhraseIndex.lookup(blocksKey, MAX_INITIAL_PREDICTIONS)
        val localCandidates = localValues.map { text ->
            PredictionCandidate(
                text = text,
                source = PredictionSource.INITIAL_INDEX,
                id = "initial-local:$blocksKey:$text",
                kind = PredictionItemKind.INITIAL_SENTENCE
            )
        }
        _state.value = baseState.copy(
            hasInitialPredictionOption = true,
            initialBlocksKey = blocksKey,
            initialRequestId = requestId,
            initialPredictionStatus = when {
                remotePredictionsEnabled -> PredictionStatus.LOADING
                localCandidates.isEmpty() -> PredictionStatus.UNAVAILABLE
                else -> PredictionStatus.READY
            },
            initialCandidates = localCandidates,
            initialPage = 0,
            highlightedInitialIndex = if (localCandidates.isNotEmpty()) 1 else 0,
            initialHasMore = false
        )
        if (!remotePredictionsEnabled) return
        initialPredictionJob = viewModelScope.launch {
            val conversation = conversationContextProvider.getContext()
            val result = llmPredictionProvider.predictInitial(
                InitialSentenceRequest(
                    requestId = requestId,
                    blocksKey = blocksKey,
                    currentText = takeLastCodePoints(baseState.outputText, MAX_SERVER_TEXT_CODE_POINTS),
                    conversation = conversation.turns.takeLast(MAX_CONVERSATION_TURNS).map { turn ->
                        turn.copy(text = takeLastCodePoints(turn.text, MAX_SERVER_TEXT_CODE_POINTS))
                    }.filter { it.role == "user" || it.role == "other" },
                    localCandidates = localValues
                        .filter { it.codePointCount(0, it.length) <= MAX_INITIAL_LOCAL_CANDIDATE_CODE_POINTS }
                        .take(MAX_SERVER_CANDIDATES),
                    limit = MAX_INITIAL_PREDICTIONS
                )
            )
            val current = _state.value
            if (current.initialRequestId != requestId || current.initialBlocksKey != blocksKey) {
                return@launch
            }
            val response = result.value
            if (response == null || response.requestId != requestId || response.blocksKey != blocksKey) {
                _state.value = current.copy(
                    initialPredictionStatus = if (current.initialCandidates.isEmpty()) {
                        PredictionStatus.UNAVAILABLE
                    } else {
                        PredictionStatus.READY
                    }
                )
                return@launch
            }
            val seenText = current.initialCandidates.mapTo(LinkedHashSet()) { it.text }
            val seenIds = current.initialCandidates.mapTo(LinkedHashSet()) { it.id }
            val additions = response.candidates.mapNotNull { candidate ->
                if (!initialPhraseIndex.matches(candidate.text, blocksKey) ||
                    !seenText.add(candidate.text) ||
                    !seenIds.add(candidate.id)
                ) return@mapNotNull null
                PredictionCandidate(
                    text = candidate.text,
                    source = if (candidate.source == "index") {
                        PredictionSource.INITIAL_INDEX
                    } else {
                        PredictionSource.LLM
                    },
                    id = candidate.id,
                    kind = PredictionItemKind.INITIAL_SENTENCE
                )
            }.take((MAX_INITIAL_PREDICTIONS - current.initialCandidates.size).coerceAtLeast(0))
            _state.value = current.copy(
                initialPredictionStatus = PredictionStatus.READY,
                initialCandidates = current.initialCandidates + additions,
                initialHasMore = response.hasMore
            )
        }
    }

    private fun enterInitialPrediction() {
        stopActiveTimer()
        val state = _state.value
        if (state.initialBlocksKey.isEmpty()) {
            returnToLevel1()
            return
        }
        val items = SelectionPaging.initialSentenceItems(state.initialCandidates, 0)
        val initial = if (items.size > 1) 1 else 0
        activeCursor = ScanCursor(items.size, index = initial, direction = 1)
        _state.value = state.copy(
            phase = InputPhase.INITIAL_PREDICTION,
            highlightedInitialIndex = initial,
            initialPage = 0,
            charScanDirection = 1,
            dwellRevision = state.dwellRevision + 1
        )
        restartActiveTimer()
    }

    private fun confirmInitialPrediction() {
        val state = _state.value
        val item = SelectionPaging.initialSentenceItems(state.initialCandidates, state.initialPage)
            .getOrNull(state.highlightedInitialIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopActiveTimer()
                val pageCount = SelectionPaging.totalPages(state.initialCandidates.size, SelectionPaging.INITIAL_PER_PAGE)
                val nextPage = if (state.initialPage + 1 < pageCount) state.initialPage + 1 else 0
                val nextItems = SelectionPaging.initialSentenceItems(state.initialCandidates, nextPage)
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = state.copy(
                    initialPage = nextPage,
                    highlightedInitialIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = state.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopActiveTimer()
                val nextPage = (state.initialPage - 1).coerceAtLeast(0)
                val nextItems = SelectionPaging.initialSentenceItems(state.initialCandidates, nextPage)
                val initial = if (nextItems.size > 1) 1 else 0
                activeCursor = ScanCursor(nextItems.size, index = initial, direction = 1)
                _state.value = state.copy(
                    initialPage = nextPage,
                    highlightedInitialIndex = initial,
                    charScanDirection = 1,
                    dwellRevision = state.dwellRevision + 1
                )
                restartActiveTimer()
            }
            SelectionItemAction.BACK -> {
                stopActiveTimer()
                returnToLevel1()
            }
            SelectionItemAction.SELECT -> {
                val candidate = item.sourceId?.let { id ->
                    state.initialCandidates.firstOrNull { it.id == id }
                } ?: return
                stopActiveTimer()
                commitText(state, candidate.text)
            }
            SelectionItemAction.CONTINUE_INPUT, SelectionItemAction.SEND -> Unit
        }
    }

    /** Touch counterpart for initial-sentence results and paging controls. */
    fun selectInitialPredictionByTouch(index: Int) {
        val state = _state.value
        val items = SelectionPaging.initialSentenceItems(state.initialCandidates, state.initialPage)
        if (state.phase != InputPhase.INITIAL_PREDICTION || index !in items.indices) return
        _state.value = state.copy(highlightedInitialIndex = index)
        confirmInitialPrediction()
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

    private fun cancelInitialPredictionRequest() {
        initialPredictionJob?.cancel()
        initialPredictionJob = null
    }

    // ==================== 退出 ====================

    private fun returnToLevel1() {
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopActiveTimer()
        val s = _state.value
        activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size, index = 0, direction = 1)
        _state.value = InputState(
            mode = s.mode,
            scanMode = s.scanMode,
            phase = InputPhase.LEVEL_1_SCANNING,
            scanSide = ScanSide.LEFT,
            highlightedBlockIndex = 0,
            outputText = s.outputText,
            scanIntervalMs = s.scanIntervalMs,
            dwellRevision = s.dwellRevision + 1
        )
        restartActiveTimer()
    }

    fun sendText() {
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopActiveTimer()
        val previous = _state.value
        if (previous.outputText.isNotBlank()) {
            conversationContextProvider.append(ConversationTurn("user", previous.outputText))
        }
        drafts[previous.mode.draftKey] = ""
        activeCursor = ScanCursor(LetterBlockMapping.LEFT_BLOCKS.size, index = 0, direction = 1)
        _state.value = InputState(
            mode = previous.mode,
            scanMode = previous.scanMode,
            scanIntervalMs = previous.scanIntervalMs,
            dwellRevision = previous.dwellRevision + 1
        )
        restartActiveTimer()
    }

    fun updateConversationContext(
        turns: List<ConversationTurn>,
        knownEntities: List<String> = emptyList()
    ) {
        conversationContextProvider.update(
            ConversationContext(turns = turns, knownEntities = knownEntities)
        )
    }

    fun setScanInterval(intervalMs: Long) {
        val saved = ScanSettings.write(getApplication(), intervalMs)
        _state.value = _state.value.copy(scanIntervalMs = saved, dwellRevision = _state.value.dwellRevision + 1)
    }

    // ==================== 后台 ====================

    private fun isValidPinyinCombo(pinyin: String): Boolean {
        return CharacterDictionary.PINYIN_TO_CHARS.containsKey(pinyin)
    }

    private fun updateBackendCandidates() {
        val s = _state.value
        if (s.selectedBlocks.isEmpty()) {
            _state.value = s.copy(pinyinCandidates = emptyList(), charCandidates = emptyList())
            return
        }
        val prev = s.outputText.takeIf { it.isNotEmpty() }?.last()?.toString()

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

    // ==================== 统一扫描定时器 ====================

    private fun candidateItemCount(s: InputState): Int = when {
        s.phase == InputPhase.LEVEL_1_SCANNING -> LetterBlockMapping.LEFT_BLOCKS.size
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused ->
            s.pinyinCombinations.size + if (s.hasInitialPredictionOption) 2 else 1
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT || s.phase == InputPhase.LEVEL_3_CHAR_SELECT ->
            SelectionPaging.characterItems(s.charCandidates, s.charPage).size
        s.phase == InputPhase.PREDICTION -> predictionItems(s).size
        s.phase == InputPhase.INITIAL_PREDICTION ->
            SelectionPaging.initialSentenceItems(s.initialCandidates, s.initialPage).size
        else -> 0
    }

    private fun currentCandidateIndex(s: InputState): Int = when {
        s.phase == InputPhase.LEVEL_1_SCANNING -> s.highlightedBlockIndex
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused -> s.highlightedPinyinIndex
        s.phase == InputPhase.LEVEL_2_LETTER_SELECT || s.phase == InputPhase.LEVEL_3_CHAR_SELECT -> s.highlightedCharIndex
        s.phase == InputPhase.PREDICTION -> s.highlightedPredictionIndex
        s.phase == InputPhase.INITIAL_PREDICTION -> s.highlightedInitialIndex
        else -> 0
    }

    private fun restartActiveTimer() {
        stopActiveTimer()
        if (!inputVisible || paused || frozen) return
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_1_SCANNING && s.scanMode == ScanMode.MANUAL) return
        scanJob = viewModelScope.launch {
            runScanTimer(
                interval = { _state.value.scanIntervalMs },
                onFailure = { Log.e(TAG, "Composer scan tick failed", it) }
            ) {
                advanceActiveCursor(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun stopActiveTimer() {
        scanJob?.cancel()
        scanJob = null
    }

    private fun advanceActiveCursor(nowMs: Long) {
        val s = _state.value
        val count = candidateItemCount(s)
        if (count <= 0) return
        if (s.phase == InputPhase.LEVEL_1_SCANNING) {
            val next = activeCursor.copy(size = count, index = s.highlightedBlockIndex, direction = 1)
                .advance(nowMs, ScanMode.AUTO)
            activeCursor = next
            _state.value = s.copy(highlightedBlockIndex = next.index)
            return
        }
        if (s.scanMode == ScanMode.MANUAL) return
        val next = activeCursor.copy(size = count, index = currentCandidateIndex(s), direction = s.charScanDirection)
            .advance(nowMs, ScanMode.AUTO)
        activeCursor = next
        when {
            s.phase == InputPhase.LEVEL_2_LETTER_SELECT && !s.isCharFocused -> {
                if (next.index in 1..s.pinyinCombinations.size) switchPinyinHighlight(next.index)
                else _state.value = s.copy(highlightedPinyinIndex = next.index)
            }
            s.phase == InputPhase.LEVEL_2_LETTER_SELECT || s.phase == InputPhase.LEVEL_3_CHAR_SELECT ->
                _state.value = s.copy(highlightedCharIndex = next.index)
            s.phase == InputPhase.PREDICTION ->
                _state.value = s.copy(highlightedPredictionIndex = next.index)
            s.phase == InputPhase.INITIAL_PREDICTION ->
                _state.value = s.copy(highlightedInitialIndex = next.index)
            else -> Unit
        }
    }

    private fun notifyToast(message: String, ok: Boolean) {
        if (!frozen) onToastCallback?.invoke(message, ok)
    }

    override fun onCleared() {
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        predictionDebugClearJob?.cancel()
        flashJob?.cancel()
        stopActiveTimer()
        super.onCleared()
    }

    private companion object {
        const val TAG = "InputMethodViewModel"
        const val MAX_PREDICTIONS = 12
        const val MAX_VISIBLE_PREDICTIONS = SelectionPaging.MAX_PREDICTIONS
        const val MAX_INITIAL_PREDICTIONS = 16
        const val MAX_SINGLE_SYLLABLE_BLOCKS = 6
        const val MAX_INITIAL_BLOCKS = 20
        const val MAX_CONVERSATION_TURNS = 4
        const val MAX_SERVER_CANDIDATES = 32
        const val MAX_SERVER_TEXT_CODE_POINTS = 500
        const val MAX_SERVER_CANDIDATE_CODE_POINTS = 100
        const val MAX_INITIAL_LOCAL_CANDIDATE_CODE_POINTS = 40
        const val MAX_PREDICTION_CODE_POINTS = 8
        const val PREDICTION_DEBUG_FALLBACK_CLEAR_MS = 4_500L
    }
}

internal fun redirectCandidateScan(current: InputState, signal: ControlSignal): InputState? {
    if (current.phase == InputPhase.LEVEL_1_SCANNING) return null
    return when (signal) {
        ControlSignal.LEFT_LOOK -> current.copy(charScanDirection = -1)
        ControlSignal.RIGHT_LOOK -> current.copy(charScanDirection = 1)
        else -> null
    }
}

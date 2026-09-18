package com.example.input_ds.viewmodel

import android.app.Application
import android.content.pm.ApplicationInfo
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
import com.example.input_ds.engine.MutableConversationContextProvider
import com.example.input_ds.engine.LocalPredictionProvider
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
import com.example.input_ds.model.InputPhase
import com.example.input_ds.model.InputState
import com.example.input_ds.model.PredictionStatus
import com.example.input_ds.model.ScanSide
import com.example.input_ds.model.ScanSettings
import com.example.input_ds.model.SelectionItemAction
import com.example.input_ds.model.SelectionPaging
import com.example.input_ds.personalization.ContextCompletionRequest
import com.example.input_ds.personalization.ConversationTurn
import com.example.input_ds.personalization.InitialSentenceRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class InputMethodViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(
        InputState(scanIntervalMs = ScanSettings.read(application))
    )
    val state: StateFlow<InputState> = _state
    private val _predictionDebugInfo = MutableStateFlow<PredictionDebugInfo?>(null)
    val predictionDebugInfo: StateFlow<PredictionDebugInfo?> = _predictionDebugInfo
    private val pinyinRecoveryEngine = PinyinRecoveryEngine()
    private val characterLookupEngine = CharacterLookupEngine()
    private val localPredictionProvider = LocalPredictionProvider()
    private val rimePredictionProvider = RimePredictionProvider(application)
    private val llmPredictionProvider = LlmPredictionProvider(application)
    private val conversationContextProvider = MutableConversationContextProvider()
    private val initialPhraseIndex = InitialPhraseIndex(application)
    private val predictionDebugEnabled =
        application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private var scanJob: Job? = null
    private var pinyinScanJob: Job? = null
    private var charScanJob: Job? = null
    private var predScanJob: Job? = null
    private var predictionJob: Job? = null
    private var initialPredictionJob: Job? = null
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
            InputPhase.INITIAL_PREDICTION -> handleInitialPredictionSignal(signal)
        }
    }

    // ==================== Level 1 ====================

    private fun handleLevel1Signal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> {
                if (_state.value.scanSide == ScanSide.LEFT) selectHighlightedBlock()
                else switchToLeftSide()
            }
            ControlSignal.RIGHT_LOOK -> {
                if (_state.value.scanSide == ScanSide.RIGHT) selectHighlightedBlock()
                else switchToRightSide()
            }
            ControlSignal.BITE -> {
                if (hasActivePinyinInput(_state.value)) enterLevel2()
                else enterCommonPhraseSelection()
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun selectHighlightedBlock() {
        val s = _state.value
        val sideBlocks = if (s.scanSide == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS
                         else LetterBlockMapping.RIGHT_BLOCKS
        val idx = s.highlightedBlockIndex
        if (idx >= sideBlocks.size) return
        applyBlock(sideBlocks[idx])
    }

    private fun applyBlock(block: Int) {
        val s = _state.value

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
        if (s.selectedBlocks.size >= MAX_INITIAL_BLOCKS) return
        _state.value = s.copy(selectedBlocks = s.selectedBlocks + block, pinyinCombinations = emptyList())
        updateBackendCandidates()
    }

    /** Touch counterpart of the BCI block selection path. */
    fun selectBlockByTouch(block: Int) {
        if (block !in LetterBlockMapping.LEFT_BLOCKS && block !in LetterBlockMapping.RIGHT_BLOCKS) return
        if (_state.value.phase != InputPhase.LEVEL_1_SCANNING) returnToLevel1()
        val side = if (block in LetterBlockMapping.LEFT_BLOCKS) ScanSide.LEFT else ScanSide.RIGHT
        val sideBlocks = if (side == ScanSide.LEFT) LetterBlockMapping.LEFT_BLOCKS else LetterBlockMapping.RIGHT_BLOCKS
        _state.value = _state.value.copy(
            scanSide = side,
            highlightedBlockIndex = sideBlocks.indexOf(block).coerceAtLeast(0)
        )
        applyBlock(block)
        restartScanning()
    }

    /** Selects a visible pinyin combination without synthesizing a BCI signal. */
    fun selectPinyinByTouch(pinyin: String) {
        if (_state.value.phase == InputPhase.LEVEL_1_SCANNING) enterLevel2()
        val state = _state.value
        if (state.phase != InputPhase.LEVEL_2_LETTER_SELECT) return
        val index = state.pinyinCombinations.indexOf(pinyin)
        if (index < 0) return
        stopPinyinScanning()
        stopCharScanning()
        switchPinyinHighlight(index + 1)
        _state.value = _state.value.copy(isCharFocused = false)
        startPinyinScanning()
    }

    /** Commits a visible character candidate directly. */
    fun selectCharacterByTouch(character: String) {
        val state = _state.value
        if (character !in state.charCandidates) return
        stopScanning(); stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        val newOutput = state.outputText + character
        UserDictionary.record(takeLastCodePoints(newOutput, 4))
        requestPredictions(state, newOutput, character)
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

    private fun hasActivePinyinInput(state: InputState): Boolean =
        state.selectedBlocks.isNotEmpty() ||
            state.currentPinyin.isNotEmpty() ||
            state.selectedLetters.isNotEmpty()

    private fun enterCommonPhraseSelection() {
        stopScanning()
        val s = _state.value
        _state.value = s.copy(
            phase = InputPhase.PREDICTION,
            currentChar = "",
            predictionCandidates = CommonPhraseUsage.sorted(CharacterDictionary.COMMON_PHRASES).map { phrase ->
                PredictionCandidate(
                    text = phrase,
                    source = PredictionSource.USER,
                    id = "common:$phrase",
                    kind = PredictionItemKind.COMMON_PHRASE
                )
            },
            highlightedPredictionIndex = 0,
            predictionPage = 0,
            isCommonPhraseSelection = true,
            charScanDirection = 1
        )
        startPredScanning()
    }

    private fun removeLastCodePoint(text: String): String {
        if (text.isEmpty()) return text
        return text.substring(0, text.offsetByCodePoints(text.length, -1))
    }

    private fun enterLevel2() {
        stopScanning()
        val s = _state.value
        val blocksKey = s.selectedBlocks.joinToString("")
        prepareInitialPrediction(s, blocksKey)
        val preparedState = _state.value
        val combos = s.pinyinCandidates.filter { it.isNotEmpty() }.take(12).toList()
        if (s.selectedBlocks.size > MAX_SINGLE_SYLLABLE_BLOCKS) {
            enterInitialPrediction()
            return
        }
        if (combos.isEmpty()) {
            _state.value = preparedState.copy(
                phase = InputPhase.LEVEL_2_LETTER_SELECT,
                pinyinCombinations = emptyList(),
                highlightedPinyinIndex = 0,
                currentPinyin = "",
                charCandidates = emptyList(),
                charPage = 0,
                charScanDirection = 1
            )
            startPinyinScanning()
            return
        }
        val first = combos.first()
        val chars = loadCharsForPinyin(first, preparedState)
        _state.value = preparedState.copy(phase = InputPhase.LEVEL_2_LETTER_SELECT, pinyinCombinations = combos,
            highlightedPinyinIndex = 0, currentPinyin = first,
            charCandidates = chars.map { it.char }, charPage = 0, charScanDirection = 1)
        startPinyinScanning()
    }

    // ==================== Level 2 ====================

    private fun handleLevel2Signal(signal: ControlSignal) {
        if (!_state.value.isCharFocused) handlePinyinSelection(signal)
        else handleCharSelection(signal)
    }

    private fun handlePinyinSelection(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK ->
                _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK ->
                _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> {
                val state = _state.value
                when (state.highlightedPinyinIndex) {
                    0 -> returnToLevel1()
                    state.pinyinCombinations.size + 1 -> enterInitialPrediction()
                    else -> focusOnChars()
                }
            }
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun handleCharSelection(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmCharacter()
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun focusOnChars() {
        stopPinyinScanning()
        stopCharScanning()
        _state.value = _state.value.copy(
            isCharFocused = true,
            highlightedCharIndex = 0,
            charPage = 0,
            charScanDirection = 1
        )
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
        val pinyin = s.pinyinCombinations.getOrNull(index - 1) ?: return
        val chars = loadCharsForPinyin(pinyin, s)
        _state.value = s.copy(
            highlightedPinyinIndex = index, currentPinyin = pinyin,
            charCandidates = chars.map { it.char }, highlightedCharIndex = 0, charPage = 0
        )
    }

    private fun advancePinyinHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_2_LETTER_SELECT || s.isCharFocused) return
        val combos = s.pinyinCombinations
        val total = combos.size + if (s.hasInitialPredictionOption) 2 else 1
        if (total <= 1) return
        val next =
            (s.highlightedPinyinIndex + s.charScanDirection + total) % total
        if (next in 1..combos.size) {
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
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun advanceCharHighlight() {
        val s = _state.value
        if (s.phase != InputPhase.LEVEL_2_LETTER_SELECT || !s.isCharFocused) return
        val items = SelectionPaging.characterItems(s.charCandidates, s.charPage)
        if (items.isEmpty()) return
        val next = (s.highlightedCharIndex + s.charScanDirection + items.size) % items.size
        _state.value = s.copy(highlightedCharIndex = next)
    }

    private fun confirmCharacter() {
        val s = _state.value
        val item = SelectionPaging.characterItems(s.charCandidates, s.charPage)
            .getOrNull(s.highlightedCharIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopCharScanning()
                _state.value = s.copy(charPage = s.charPage + 1, highlightedCharIndex = 0)
                startCharScanning()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopCharScanning()
                _state.value = s.copy(charPage = (s.charPage - 1).coerceAtLeast(0), highlightedCharIndex = 0)
                startCharScanning()
            }
            SelectionItemAction.BACK -> {
                _state.value = s.copy(isCharFocused = false, charPage = 0, highlightedCharIndex = 0)
                stopCharScanning()
                startPinyinScanning()
            }
            SelectionItemAction.SELECT -> {
                val ch = s.charCandidates.getOrNull(item.sourceIndex) ?: return
                stopCharScanning()
                val newOut = s.outputText + ch
                UserDictionary.record(takeLastCodePoints(newOut, 4))
                requestPredictions(s, newOut, ch)
            }
            else -> Unit
        }
    }

    // ==================== Prediction ====================

    private fun handlePredictionSignal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmPrediction()
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
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
        val items = when (s.phase) {
            InputPhase.PREDICTION -> predictionItems(s)
            InputPhase.INITIAL_PREDICTION ->
                SelectionPaging.initialSentenceItems(s.initialCandidates, s.initialPage)
            else -> return
        }
        if (items.isEmpty()) return
        if (s.phase == InputPhase.INITIAL_PREDICTION) {
            val next = (s.highlightedInitialIndex + s.charScanDirection + items.size) % items.size
            _state.value = s.copy(highlightedInitialIndex = next)
        } else {
            val next = (s.highlightedPredictionIndex + s.charScanDirection + items.size) % items.size
            _state.value = s.copy(highlightedPredictionIndex = next)
        }
    }

    private fun confirmPrediction() {
        val s = _state.value
        val item = predictionItems(s).getOrNull(s.highlightedPredictionIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopPredScanning()
                _state.value = s.copy(predictionPage = s.predictionPage + 1, highlightedPredictionIndex = 0)
                startPredScanning()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopPredScanning()
                _state.value = s.copy(predictionPage = (s.predictionPage - 1).coerceAtLeast(0), highlightedPredictionIndex = 0)
                startPredScanning()
            }
            SelectionItemAction.BACK, SelectionItemAction.CONTINUE_INPUT -> {
                stopPredScanning()
                returnToLevel1()
            }
            SelectionItemAction.SELECT -> {
                val candidate = item.sourceId?.let { id ->
                    s.predictionCandidates.firstOrNull { it.id == id }
                } ?: return
                if (s.isCommonPhraseSelection) CommonPhraseUsage.record(candidate.text)
                val newOut = s.outputText + candidate.text
                if (candidate.text.isNotEmpty()) {
                    val lastCodePoint = takeLastCodePoints(candidate.text, 1)
                    UserDictionary.record(takeLastCodePoints(newOut, 4))
                    requestPredictions(s, newOut, lastCodePoint)
                } else {
                    stopPredScanning()
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

    private fun predictionItems(state: InputState) =
        if (state.isCommonPhraseSelection) {
            SelectionPaging.commonPhraseItems(state.predictionCandidates, state.predictionPage)
        } else {
            SelectionPaging.predictionItems(state.predictionCandidates)
        }

    private fun requestPredictions(baseState: InputState, context: String, currentChar: String) {
        stopPredScanning()
        cancelInitialPredictionRequest()
        predictionJob?.cancel()
        val requestSequence = ++predictionRequestId
        val requestId = "ctx-${UUID.randomUUID()}"
        _state.value = baseState.copy(
            phase = InputPhase.PREDICTION,
            isCharFocused = false,
            outputText = context,
            currentChar = currentChar,
            predictionCandidates = emptyList(),
            highlightedPredictionIndex = 0,
            predictionPage = 0,
            isCommonPhraseSelection = false,
            charScanDirection = 1
        )
        predictionJob = viewModelScope.launch {
            val localCandidates = localPredictionProvider.predict(context, MAX_PREDICTIONS * 2)
                .distinctBy { it.text }
                .take(MAX_VISIBLE_PREDICTIONS)
            if (!isCurrentContextRequest(requestSequence, context)) return@launch
            _state.value = _state.value.copy(
                predictionCandidates = localCandidates,
                highlightedPredictionIndex = 0,
                predictionPage = 0,
                charScanDirection = 1
            )
            startPredScanning()

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
            _state.value = current.copy(
                predictionCandidates = merge.candidates
            )
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
            initialPredictionStatus = PredictionStatus.LOADING,
            initialCandidates = localCandidates,
            initialPage = 0,
            highlightedInitialIndex = 0,
            initialHasMore = false
        )
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
        stopPinyinScanning()
        stopCharScanning()
        val state = _state.value
        if (state.initialBlocksKey.isEmpty()) {
            returnToLevel1()
            return
        }
        _state.value = state.copy(
            phase = InputPhase.INITIAL_PREDICTION,
            highlightedInitialIndex = 0,
            initialPage = 0,
            charScanDirection = 1
        )
        startPredScanning()
    }

    private fun handleInitialPredictionSignal(signal: ControlSignal) {
        when (signal) {
            ControlSignal.LEFT_LOOK -> _state.value = _state.value.copy(charScanDirection = -1)
            ControlSignal.RIGHT_LOOK -> _state.value = _state.value.copy(charScanDirection = 1)
            ControlSignal.BITE -> confirmInitialPrediction()
            ControlSignal.LEFT_RIGHT, ControlSignal.RIGHT_LEFT -> Unit
        }
    }

    private fun confirmInitialPrediction() {
        val state = _state.value
        val item = SelectionPaging.initialSentenceItems(state.initialCandidates, state.initialPage)
            .getOrNull(state.highlightedInitialIndex) ?: return
        when (item.action) {
            SelectionItemAction.NEXT_PAGE -> {
                stopPredScanning()
                _state.value = state.copy(
                    initialPage = state.initialPage + 1,
                    highlightedInitialIndex = 0
                )
                startPredScanning()
            }
            SelectionItemAction.PREVIOUS_PAGE -> {
                stopPredScanning()
                _state.value = state.copy(
                    initialPage = (state.initialPage - 1).coerceAtLeast(0),
                    highlightedInitialIndex = 0
                )
                startPredScanning()
            }
            SelectionItemAction.BACK -> {
                stopPredScanning()
                returnToLevel1()
            }
            SelectionItemAction.SELECT -> {
                val candidate = item.sourceId?.let { id ->
                    state.initialCandidates.firstOrNull { it.id == id }
                } ?: return
                stopPredScanning()
                val newOutput = state.outputText + candidate.text
                UserDictionary.record(candidate.text)
                requestPredictions(state, newOutput, takeLastCodePoints(candidate.text, 1))
            }
            SelectionItemAction.CONTINUE_INPUT -> Unit
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
        stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        _state.value = InputState(
            outputText = _state.value.outputText,
            scanIntervalMs = _state.value.scanIntervalMs
        )
        startScanning()
    }

    fun sendText() {
        cancelPredictionRequest()
        cancelInitialPredictionRequest()
        stopScanning(); stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        val previous = _state.value
        if (previous.outputText.isNotBlank()) {
            conversationContextProvider.append(ConversationTurn("user", previous.outputText))
        }
        _state.value = InputState(
            scanIntervalMs = previous.scanIntervalMs
        )
        startScanning()
    }

    /** Injection point for a future system IME, accessibility service, or manual demo panel. */
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
        _state.value = _state.value.copy(scanIntervalMs = saved)
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
        cancelInitialPredictionRequest()
        predictionDebugClearJob?.cancel()
        rimePredictionProvider.close()
        stopScanning(); stopPinyinScanning(); stopCharScanning(); stopPredScanning()
        super.onCleared()
    }

    private companion object {
        const val MAX_PREDICTIONS = 12
        const val MAX_VISIBLE_PREDICTIONS = SelectionPaging.GRID_SIZE - 1
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

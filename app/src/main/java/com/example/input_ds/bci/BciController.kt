package com.example.input_ds.bci

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.input_ds.model.ControlSignal
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.CollectionSettings
import com.example.input_ds.personalization.UserModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class BciWindowEpoch(
    val commandEpoch: Long,
    val acquisitionEpoch: Long
)

/** Invalidates in-flight command work whenever delivery or acquisition changes. */
internal class BciCommandGate(initiallyEnabled: Boolean = true) {
    private val lock = Any()
    private var enabled = initiallyEnabled
    private var commandEpoch = 0L

    val isEnabled: Boolean
        get() = synchronized(lock) { enabled }

    fun setEnabled(value: Boolean): Boolean = synchronized(lock) {
        if (enabled == value) return@synchronized false
        enabled = value
        commandEpoch++
        true
    }

    fun invalidate() {
        synchronized(lock) {
            commandEpoch++
        }
    }

    fun snapshot(acquisitionEpoch: Long): BciWindowEpoch? = synchronized(lock) {
        if (enabled) BciWindowEpoch(commandEpoch, acquisitionEpoch) else null
    }

    fun isCurrent(snapshot: BciWindowEpoch, acquisitionEpoch: Long): Boolean =
        synchronized(lock) {
            enabled &&
                commandEpoch == snapshot.commandEpoch &&
                acquisitionEpoch == snapshot.acquisitionEpoch
        }
}

/** Runs asynchronous BCI control over continuous, overlapping EEG windows. */
class BciController(
    private val context: Context,
    private val bleManager: NaoyunBleManager,
    private val onSignal: (ControlSignal) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _prediction = MutableStateFlow("等待启动…")
    val prediction: StateFlow<String> = _prediction
    private val _classId = MutableStateFlow(-1)
    val classId: StateFlow<Int> = _classId
    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence
    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status

    private var modelInference: ModelInference? = null
    private var loadedModelContract: JSONObject? = null
    private var calibration: AsyncCalibration? = null
    private var asyncDetector: FourClassActionDetector? = null
    private var sixActionDetector: SixActionDetector? = null
    private var asyncLogger: AsyncControlLogger? = null
    private val commandGate = BciCommandGate()
    @Volatile private var controlActive = false

    val isCommandDeliveryEnabled: Boolean
        get() = commandGate.isEnabled

    /**
     * Suppresses semantic commands without stopping BLE acquisition. Re-enabling
     * requires one new complete model window before a command can be delivered.
     */
    fun setCommandDeliveryEnabled(enabled: Boolean) {
        if (!commandGate.setEnabled(enabled)) return
        _status.value = if (enabled) {
            "控制命令已开启，等待新完整窗口"
        } else {
            "控制命令已屏蔽"
        }
        Log.d(TAG, "command delivery enabled=$enabled; pending windows invalidated")
    }

    /** Invalidates in-flight work while preserving the current gate state. */
    fun invalidatePendingCommands() {
        commandGate.invalidate()
        Log.d(TAG, "pending command windows invalidated")
    }

    fun loadModel(): Boolean {
        modelInference = ModelInference(context)
        val loaded = modelInference!!.loadModel()
        val usesActiveModel = loaded && modelInference!!.loadedFromActiveModel
        loadedModelContract = if (usesActiveModel) UserModelManager.activePreprocessingContract(context) else null
        calibration = if (!loaded) null else runCatching {
            val resolvedCalibration = if (usesActiveModel) {
                AsyncCalibrationManager.loadForActiveModel(context, modelInference!!.inputPoints)
            } else {
                AsyncCalibrationManager.loadForBuiltInModel(modelInference!!.inputPoints)
            }
            resolvedCalibration.also {
                require(it.protocol == modelInference!!.protocol) {
                    "模型协议 ${modelInference!!.protocol.wireName} 与校准协议 ${it.protocol.wireName} 不一致"
                }
            }
        }.getOrElse { error ->
            _status.value = "校准加载失败：${error.message ?: "未知错误"}"
            modelInference?.close()
            modelInference = null
            loadedModelContract = null
            Log.e(TAG, "calibration load failed", error)
            return false
        }
        _status.value = if (loaded) {
            "模型已就绪：${modelInference!!.inputPoints} 点窗口"
        } else {
            "模型加载失败：${modelInference?.lastError ?: "未知错误"}"
        }
        Log.d(TAG, "ONNX loaded=$loaded windowPoints=${modelInference?.inputPoints}")
        return loaded
    }

    val isModelLoaded: Boolean
        get() = modelInference?.isLoaded == true

    val loadedProtocol: ClassificationProtocol?
        get() = modelInference?.takeIf { it.isLoaded }?.protocol

    fun start() {
        if (controlActive) return
        if (!isModelLoaded) {
            _status.value = "模型未加载，控制未启动"
            return
        }
        commandGate.invalidate()
        controlActive = true
        asyncDetector = null
        sixActionDetector = null
        when (calibration?.protocol) {
            ClassificationProtocol.FOUR_CLASS -> asyncDetector = calibration?.let(::FourClassActionDetector)
            ClassificationProtocol.SIX_ACTION -> sixActionDetector = calibration?.let(::SixActionDetector)
            null -> Unit
        }
        _classId.value = -1
        _confidence.value = 0f
        _prediction.value = "异步监听中…"
        _status.value = "异步控制已启动：75%重叠滑窗；${calibration?.source}"
        Log.d(TAG, "异步控制已启动 modelPoints=${modelInference?.inputPoints} calibration=$calibration")
        startAsynchronousLoop()
    }

    private fun startAsynchronousLoop() {
        val modelPoints = modelInference?.inputPoints ?: return
        val stridePoints = AsyncWindowPolicy.stridePoints(modelPoints)
        val selectedPreprocessing = bleManager.preprocessing.value
        val modelContract = loadedModelContract
        val fourDetector = asyncDetector
        val sixDetector = sixActionDetector
        if (fourDetector == null && sixDetector == null) {
            _status.value = "当前模型没有匹配的异步动作检测器"
            controlActive = false
            return
        }
        val protocol = modelInference?.protocol ?: return
        asyncLogger?.close()
        asyncLogger = AsyncControlLogger(
            context,
            modelPoints,
            stridePoints,
            calibration ?: return,
            modelContract.describePreprocessing(selectedPreprocessing),
            modelInference?.loadedModelDescription ?: "未知模型",
            protocol = protocol,
            classOrder = modelInference?.labelNames ?: protocol.labelNames
        )
        job?.cancel()
        job = scope.launch {
            var activeEpoch: BciWindowEpoch? = null
            var epochStartSample = bleManager.buffer.synchronizedCount()
            var lastProcessedEnd = epochStartSample
            while (isActive && controlActive) {
                val acquisitionEpoch = bleManager.synchronizedDataEpoch.value
                val windowEpoch = commandGate.snapshot(acquisitionEpoch)
                if (windowEpoch == null) {
                    activeEpoch = null
                    delay(ASYNC_POLL_INTERVAL_MS)
                    continue
                }

                val available = bleManager.buffer.synchronizedCount()
                if (windowEpoch != activeEpoch) {
                    fourDetector?.reset()
                    sixDetector?.reset()
                    activeEpoch = windowEpoch
                    epochStartSample = available
                    lastProcessedEnd = available
                    Log.d(
                        TAG,
                        "new inference epoch command=${windowEpoch.commandEpoch} " +
                            "acquisition=${windowEpoch.acquisitionEpoch} start=$available"
                    )
                }

                if (available - epochStartSample < modelPoints ||
                    available - lastProcessedEnd < stridePoints
                ) {
                    delay(ASYNC_POLL_INTERVAL_MS)
                    continue
                }
                // Always evaluate the newest complete window to avoid control lag.
                val endSample = available
                lastProcessedEnd = endSample
                val rawStart = endSample - modelPoints
                val rawLeft = bleManager.buffer.getLeftRange(rawStart, endSample)
                val rawRight = bleManager.buffer.getRightRange(rawStart, endSample)
                if (rawLeft.size != modelPoints || rawRight.size != modelPoints) {
                    _status.value = "异步窗口点数不足，已跳过"
                    continue
                }
                val historyStart = (endSample - MAX_FILTER_HISTORY_POINTS).coerceAtLeast(0)
                val left = bleManager.buffer.getLeftRange(historyStart, endSample)
                val right = bleManager.buffer.getRightRange(historyStart, endSample)
                val inferenceStarted = SystemClock.elapsedRealtime()
                val processed = if (modelContract != null) {
                    EegPreprocessor.preprocess(left, right, modelContract)
                } else {
                    EegPreprocessor.preprocess(left, right, modelPoints, selectedPreprocessing)
                }
                if (processed == null) {
                    _status.value = "异步窗口预处理失败，已跳过"
                    continue
                }
                val result = modelInference?.predictResult(processed)
                if (result == null) {
                    _status.value = "异步推理失败：${modelInference?.lastError ?: "未知错误"}"
                    continue
                }
                val currentAcquisitionEpoch = bleManager.synchronizedDataEpoch.value
                if (!commandGate.isCurrent(windowEpoch, currentAcquisitionEpoch)) {
                    Log.d(TAG, "inference epoch changed; discard end=$endSample")
                    continue
                }
                if (bleManager.buffer.synchronizedCount() - endSample >= stridePoints) {
                    Log.d(TAG, "异步结果已落后一个步长，丢弃 end=$endSample")
                    continue
                }
                val timestampMs = endSample * 1000L / SAMPLE_RATE_HZ
                val fourDecision = fourDetector?.update(
                    result.probabilities,
                    rawLeft,
                    rawRight,
                    timestampMs
                )
                val sixDecision = sixDetector?.update(
                    result.probabilities,
                    rawLeft,
                    rawRight,
                    timestampMs
                )
                val eventClass = fourDecision?.eventClass ?: sixDecision?.eventClass
                val label = modelInference?.labelNames?.getOrNull(result.classId) ?: "unknown"
                val className = CollectionSettings.LABEL_DISPLAY[label] ?: label
                _classId.value = result.classId
                _confidence.value = result.confidence
                _prediction.value = "$className ${"%.0f".format(result.confidence * 100)}%"
                val elapsed = SystemClock.elapsedRealtime() - inferenceStarted
                if (fourDecision != null) {
                    asyncLogger?.logWindow(
                        endSample, result.probabilities, fourDecision, elapsed,
                        modelPoints, rawLeft, rawRight
                    )
                } else if (sixDecision != null) {
                    asyncLogger?.logWindow(
                        endSample, result.probabilities, sixDecision, elapsed,
                        modelPoints, rawLeft, rawRight
                    )
                }
                _status.value = when {
                    fourDecision != null -> buildString {
                        append("异步 ${fourDecision.state} · 活动量 ${"%.1f".format(fourDecision.features.activityStdUv)}µV")
                        append(" · 极性 ${"%.2f".format(fourDecision.features.directionScore)} · ${elapsed}ms")
                    }
                    sixDecision != null -> buildString {
                        append("六分类 ${sixDecision.snapshot.state} · 活动量 ${"%.1f".format(sixDecision.features.activityStdUv)}µV")
                        append(" · 模板 ${sixDecision.features.motionAllowedClass ?: "-"} · ${elapsed}ms")
                    }
                    else -> "异步控制等待中"
                }
                eventClass?.toSignal(protocol)?.let { signal ->
                    withContext(Dispatchers.Main.immediate) {
                        if (
                            controlActive && commandGate.isCurrent(
                                windowEpoch,
                                bleManager.synchronizedDataEpoch.value
                            )
                        ) {
                            onSignal(signal)
                        }
                    }
                }
            }
        }
    }

    private fun Int.toSignal(protocol: ClassificationProtocol): ControlSignal? = when (protocol) {
        ClassificationProtocol.FOUR_CLASS -> when (this) {
            1 -> ControlSignal.BITE
            2 -> ControlSignal.LEFT_LOOK
            3 -> ControlSignal.RIGHT_LOOK
            else -> null
        }
        ClassificationProtocol.SIX_ACTION -> when (this) {
            1 -> ControlSignal.LEFT_LOOK
            2 -> ControlSignal.RIGHT_LOOK
            3 -> ControlSignal.BITE
            4 -> ControlSignal.LEFT_RIGHT
            5 -> ControlSignal.RIGHT_LEFT
            else -> null
        }
    }

    fun stop() {
        controlActive = false
        commandGate.invalidate()
        job?.cancel()
        job = null
        modelInference?.close()
        modelInference = null
        loadedModelContract = null
        asyncDetector?.reset()
        asyncDetector = null
        sixActionDetector?.reset()
        sixActionDetector = null
        asyncLogger?.close()
        asyncLogger = null
        calibration = null
        _status.value = "已停止"
    }

    private fun JSONObject?.describePreprocessing(fallback: Set<EegPreprocessStep>): String {
        val steps = this?.optJSONArray("window_steps")
            ?: return EegPreprocessStep.describe(fallback)
        if (steps.length() == 0) return "原始信号"
        return buildList {
            for (index in 0 until steps.length()) {
                val step = steps.optJSONObject(index) ?: continue
                add(
                    when (step.optString("operation")) {
                        "bandpass" -> "${step.optDouble("low_hz").compact()}–${step.optDouble("high_hz").compact()}Hz 带通"
                        "zscore" -> "Z-score"
                        "scale" -> "缩放"
                        "clip" -> "截幅"
                        else -> step.optString("operation", "未知预处理")
                    }
                )
            }
        }.joinToString(" + ").ifBlank { "原始信号" }
    }

    private fun Double.compact(): String =
        if (this % 1.0 == 0.0) toInt().toString() else toString()

    private companion object {
        const val TAG = "BciController"
        const val SAMPLE_RATE_HZ = 500L
        const val MAX_FILTER_HISTORY_POINTS = 15_000
        const val ASYNC_POLL_INTERVAL_MS = 20L
    }
}

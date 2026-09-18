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
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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
    private val inferenceDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bci-inference").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(inferenceDispatcher + SupervisorJob())

    private val _prediction = MutableStateFlow("等待启动…")
    val prediction: StateFlow<String> = _prediction
    private val _classId = MutableStateFlow(-1)
    val classId: StateFlow<Int> = _classId
    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence
    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status

    private var modelInference: ModelInference? = null
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
        _status.value = "异步控制已启动：100ms步长 · 5窗证据；${calibration?.source}"
        Log.d(TAG, "异步控制已启动 modelPoints=${modelInference?.inputPoints} calibration=$calibration")
        startAsynchronousLoop()
    }

    private fun startAsynchronousLoop() {
        val modelPoints = modelInference?.inputPoints ?: return
        val stridePoints = AsyncWindowPolicy.stridePoints(modelPoints)
        val selectedPreprocessing = EegPreprocessStep.DEFAULT
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
            EegPreprocessStep.describe(selectedPreprocessing),
            modelInference?.loadedModelDescription ?: "未知模型",
            protocol = protocol,
            classOrder = modelInference?.labelNames ?: protocol.labelNames
        )
        job?.cancel()
        job = scope.launch {
            var activeEpoch: BciWindowEpoch? = null
            var epochStartTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            var nextWindowEndUs: Long? = null
            val windowDurationUs = (modelPoints - 1L) * SAMPLE_PERIOD_US
            val strideDurationUs = stridePoints * SAMPLE_PERIOD_US
            while (isActive && controlActive) {
                val acquisitionEpoch = bleManager.synchronizedDataEpoch.value
                val windowEpoch = commandGate.snapshot(acquisitionEpoch)
                if (windowEpoch == null) {
                    activeEpoch = null
                    delay(ASYNC_POLL_INTERVAL_MS)
                    continue
                }

                if (windowEpoch != activeEpoch) {
                    fourDetector?.reset()
                    sixDetector?.reset()
                    activeEpoch = windowEpoch
                    epochStartTimeUs = SystemClock.elapsedRealtimeNanos() / 1_000L
                    nextWindowEndUs = null
                    Log.d(
                        TAG,
                        "new inference epoch command=${windowEpoch.commandEpoch} " +
                            "acquisition=${windowEpoch.acquisitionEpoch} startUs=$epochStartTimeUs"
                    )
                }

                val commonEndUs = bleManager.commonAlignedTimeUs()
                if (commonEndUs == null) {
                    delay(ASYNC_POLL_INTERVAL_MS)
                    continue
                }
                if (nextWindowEndUs == null) {
                    val latest = bleManager.latestAlignedWindow(modelPoints)
                    if (
                        latest == null ||
                        latest.endTimeUs < epochStartTimeUs + windowDurationUs
                    ) {
                        delay(ASYNC_POLL_INTERVAL_MS)
                        continue
                    }
                    nextWindowEndUs = latest.endTimeUs
                }
                var scheduledEndUs = nextWindowEndUs ?: continue
                if (commonEndUs < scheduledEndUs) {
                    delay(ASYNC_POLL_INTERVAL_MS)
                    continue
                }
                val pendingSteps = ((commonEndUs - scheduledEndUs) / strideDurationUs).toInt()
                if (pendingSteps >= MAX_CATCH_UP_WINDOWS) {
                    val skippedSteps = pendingSteps - (MAX_CATCH_UP_WINDOWS - 1)
                    scheduledEndUs += skippedSteps * strideDurationUs
                    nextWindowEndUs = scheduledEndUs
                    fourDetector?.reset()
                    sixDetector?.reset()
                    Log.w(TAG, "推理积压，跳过 $skippedSteps 个步长并清空时序证据")
                }
                val rawStereo = bleManager.alignedWindow(
                    scheduledEndUs - windowDurationUs,
                    scheduledEndUs,
                    modelPoints
                )
                if (rawStereo == null) {
                    nextWindowEndUs = scheduledEndUs + strideDurationUs
                    fourDetector?.reset()
                    sixDetector?.reset()
                    Log.w(TAG, "固定步长窗口不可用，清空时序证据 endUs=$scheduledEndUs")
                    continue
                }
                nextWindowEndUs = scheduledEndUs + strideDurationUs
                val rawLeft = rawStereo.left
                val rawRight = rawStereo.right
                if (rawLeft.size != modelPoints || rawRight.size != modelPoints) {
                    _status.value = "异步窗口点数不足，已跳过"
                    continue
                }
                val inferenceStarted = SystemClock.elapsedRealtime()
                val processed = EegPreprocessor.preprocess(
                    rawLeft,
                    rawRight,
                    modelPoints,
                    selectedPreprocessing
                )
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
                    Log.d(TAG, "inference epoch changed; discard endUs=${rawStereo.endTimeUs}")
                    continue
                }
                val timestampMs = rawStereo.endTimeUs / 1_000L
                val endSample = (rawStereo.endTimeUs / SAMPLE_PERIOD_US)
                    .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
        asyncDetector?.reset()
        asyncDetector = null
        sixActionDetector?.reset()
        sixActionDetector = null
        asyncLogger?.close()
        asyncLogger = null
        calibration = null
        scope.cancel()
        inferenceDispatcher.close()
        _status.value = "已停止"
    }

    private companion object {
        const val TAG = "BciController"
        const val SAMPLE_PERIOD_US = 2_000L
        const val ASYNC_POLL_INTERVAL_MS = 20L
        const val MAX_CATCH_UP_WINDOWS = 5
    }
}

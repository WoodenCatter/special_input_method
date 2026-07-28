package com.example.input_ds.bci

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.example.input_ds.model.ControlSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * BCI 实时控制器。
 *
 * 与桌面 real_time.py 对齐：
 * - 每次高亮切换为一个独立控制轮次；
 * - 轮次开始后按墙上时间采集 800 ms（400 点）；
 * - 800–1200 ms 用于预处理、推理和执行；
 * - 连续历史先做二阶 1–45 Hz filtfilt，再截取本轮 400 点；
 * - 模型输入不做 Z-score；
 * - 置信度达到 60% 才执行控制命令；
 * - 0=休息，1=咬牙，2=左看，3=右看。
 */
class BciController(
    private val context: Context,
    private val bleManager: NaoyunBleManager,
    private val onSignal: (ControlSignal) -> Unit
) {
    private var job: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _prediction = MutableStateFlow("等待开始…")
    val prediction: StateFlow<String> = _prediction

    private val _classId = MutableStateFlow(-1)
    val classId: StateFlow<Int> = _classId

    private val _confidence = MutableStateFlow(0f)
    val confidence: StateFlow<Float> = _confidence

    private val _status = MutableStateFlow("未启动")
    val status: StateFlow<String> = _status

    private var modelInference: ModelInference? = null
    @Volatile private var controlActive = false
    @Volatile private var highlightEpoch = 0L

    fun loadModel(): Boolean {
        modelInference = ModelInference(context)
        val loaded = modelInference!!.loadModel()
        if (loaded && modelInference!!.inputPoints != MODEL_WINDOW_POINTS) {
            _status.value =
                "模型要求 ${modelInference!!.inputPoints} 点，" +
                        "输入法800ms模型应为 400 点"
            Log.e(
                TAG,
                "模型窗口不匹配: model=${modelInference!!.inputPoints}, " +
                        "required=$MODEL_WINDOW_POINTS"
            )
            modelInference?.close()
            modelInference = null
            return false
        }
        _status.value = if (loaded) {
            "模型已就绪：高亮后采集800ms，置信度门槛60%"
        } else {
            "模型加载失败：${modelInference?.lastError ?: "未知错误"}"
        }
        Log.d(
            "BciController",
            "ONNX 加载结果=$loaded, windowPoints=$MODEL_WINDOW_POINTS, " +
                    "confidence=$MIN_CONFIDENCE"
        )
        return loaded
    }

    val isModelLoaded: Boolean
        get() = modelInference?.isLoaded == true

    fun start() {
        if (controlActive) return
        if (!isModelLoaded) {
            _status.value = "模型未加载，控制未启动"
            return
        }

        bleManager.resetSynchronizedData()
        controlActive = true
        highlightEpoch++
        _classId.value = -1
        _confidence.value = 0f
        _prediction.value = "等待高亮轮次…"
        _status.value = "控制已启动，等待输入法高亮"
    }

    /**
     * Called immediately after the UI presents a new highlighted item.
     * A newer highlight cancels and invalidates every older inference result.
     */
    fun onHighlightStarted(blockDurationMs: Long) {
        if (!controlActive || !isModelLoaded) return
        val epoch = ++highlightEpoch
        job?.cancel()
        job = scope.launch {
            val startedAtMs = SystemClock.elapsedRealtime()
            val startSample = bleManager.buffer.synchronizedCount()
            _status.value = "本轮采集中：0/$MODEL_WINDOW_POINTS 点（800ms）"
            delay(CAPTURE_DURATION_MS)
            ensureActive()

            val available = bleManager.buffer.synchronizedCount()
            val endSample = startSample + MODEL_WINDOW_POINTS
            if (available < endSample) {
                _status.value =
                    "本轮数据不足：${(available - startSample).coerceAtLeast(0)}/" +
                            "$MODEL_WINDOW_POINTS 点，未执行"
                return@launch
            }

            val historyStart =
                (endSample - MAX_FILTER_HISTORY_POINTS).coerceAtLeast(0)
            val left = bleManager.buffer.getLeftRange(historyStart, endSample)
            val right = bleManager.buffer.getRightRange(historyStart, endSample)
            _status.value = "800ms采集完成，正在推理"
            val processed =
                EegPreprocessor.preprocess(left, right, MODEL_WINDOW_POINTS)
            if (processed == null) {
                _status.value = "本轮预处理失败，未执行"
                return@launch
            }

            val result = modelInference?.predictResult(processed)
            if (result == null) {
                _status.value =
                    "推理失败：${modelInference?.lastError ?: "未知错误"}"
                return@launch
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
            if (epoch != highlightEpoch ||
                elapsedMs >= blockDurationMs ||
                !controlActive
            ) {
                _status.value = "推理结果已超过当前高亮周期，已丢弃"
                Log.d(TAG, "丢弃过期结果 epoch=$epoch elapsed=${elapsedMs}ms")
                return@launch
            }

            val classId = result.classId
            val className =
                CLASS_NAMES[classId.coerceIn(CLASS_NAMES.indices)]
            _classId.value = classId
            _confidence.value = result.confidence
            val passesThreshold = result.confidence >= MIN_CONFIDENCE
            _prediction.value = buildString {
                append(className)
                append(" ${"%.0f".format(result.confidence * 100)}%")
                if (!passesThreshold) append("（低于门槛）")
            }

            val signal = when (classId) {
                1 -> ControlSignal.BITE
                2 -> ControlSignal.LEFT_LOOK
                3 -> ControlSignal.RIGHT_LOOK
                else -> null
            }
            if (signal != null && passesThreshold) {
                _status.value =
                    "已执行：$className ${"%.0f".format(result.confidence * 100)}% " +
                            "（${elapsedMs}ms）"
                withContext(Dispatchers.Main.immediate) {
                    if (epoch == highlightEpoch && controlActive) {
                        onSignal(signal)
                    }
                }
            } else if (signal != null) {
                _status.value =
                    "置信度不足，未执行：$className " +
                            "${"%.0f".format(result.confidence * 100)}% < 60%"
            } else {
                _status.value =
                    "静息 ${"%.0f".format(result.confidence * 100)}%，不执行命令"
            }
        }
    }

    fun stop() {
        controlActive = false
        highlightEpoch++
        job?.cancel()
        job = null
        modelInference?.close()
        modelInference = null
        _status.value = "已停止"
    }

    private companion object {
        const val TAG = "BciController"
        const val MODEL_WINDOW_POINTS = 400
        const val CAPTURE_DURATION_MS = 800L
        const val MAX_FILTER_HISTORY_POINTS = 15_000
        const val MIN_CONFIDENCE = 0.60f
        val CLASS_NAMES = arrayOf("静息", "咬牙", "左看", "右看")
    }
}

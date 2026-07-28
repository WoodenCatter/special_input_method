package com.example.input_ds.bci

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import java.io.File
import java.nio.FloatBuffer

/**
 * ONNX Runtime 模型推理（自适应输入 shape）
 *
 * 加载时自动检测模型输入名和维度，兼容 3D (1,2,N) / 4D (1,1,2,N)。
 */
class ModelInference(private val context: Context) {

    data class Prediction(
        val classId: Int,
        val confidence: Float,
        val logits: FloatArray
    )

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    var isLoaded = false; private set

    // 运行时检测的输入信息
    private var inputName: String = "eeg_input"
    private var inputShape: LongArray = longArrayOf(1, 2, 400)
    var inputPoints: Int = DEFAULT_POINTS
        private set
    var lastError: String? = null
        private set

    @Synchronized
    fun loadModel(modelPath: String? = null): Boolean {
        try {
            close()
            lastError = null
            val selectedModel = modelPath ?: findDefaultModel()
            Log.d("ModelInference", "开始加载: $selectedModel")
            env = OrtEnvironment.getEnvironment()
            val file = File(context.filesDir, selectedModel)
            val bytes: ByteArray
            if (file.exists()) { Log.d("ModelInference", "从内部存储: ${file.length()}B"); bytes = file.readBytes() }
            else { Log.d("ModelInference", "从 assets 读取..."); bytes = context.assets.open(selectedModel).use { it.readBytes() }; Log.d("ModelInference", "assets: ${bytes.size}B") }
            OrtSession.SessionOptions().use { opts ->
                session = env!!.createSession(bytes, opts)
            }
            // 检测输入信息，替换动态维度 -1 → 1
            val inputs = session!!.inputInfo
            if (inputs.isNotEmpty()) {
                val entry = inputs.entries.first()
                inputName = entry.key
                inputShape = (entry.value.info as TensorInfo).shape.map { if (it == -1L) 1L else it }.toLongArray()
                Log.d("ModelInference", "检测到输入: name=$inputName, shape=${inputShape.joinToString(",")}")
            }
            validateInputShape(inputShape)
            inputPoints = inputShape.last().toInt()
            isLoaded = true
            Log.d("ModelInference", "加载成功!")
            return true
        } catch (e: Exception) {
            Log.e("ModelInference", "加载失败: ${e.message}", e)
            session?.close()
            session = null
            lastError = e.message
            isLoaded = false
            return false
        }
    }

    private fun findDefaultModel(): String {
        val candidates = listOf("model.onnx", "csanet_model.onnx")
        val assets = context.assets.list("")?.toSet().orEmpty()
        return candidates.firstOrNull { name ->
            File(context.filesDir, name).exists() || name in assets
        } ?: candidates.first()
    }

    fun predict(data: Array<FloatArray>): Int = predictResult(data)?.classId ?: 0

    @Synchronized
    fun predictResult(data: Array<FloatArray>): Prediction? {
        if (!isLoaded || data.size < CHANNELS ||
            data[0].size < inputPoints || data[1].size < inputPoints) return null
        try {
            val shape = inputShape
            val totalElements = shape.fold(1L) { a, b -> a * b }.toInt()
            if (totalElements != CHANNELS * inputPoints) {
                throw IllegalStateException("不支持的模型输入元素数: $totalElements")
            }
            val inputBuffer = FloatBuffer.allocate(totalElements)
            for (ch in 0 until CHANNELS) {
                val start = data[ch].size - inputPoints
                for (t in 0 until inputPoints) inputBuffer.put(data[ch][start + t])
            }
            inputBuffer.rewind()
            OnnxTensor.createTensor(env, inputBuffer, shape).use { inputTensor ->
                session!!.run(mapOf(inputName to inputTensor)).use { results ->
                    val logits = extractLogits(results[0].value)
                    require(logits.size >= CLASS_COUNT) {
                        "模型输出类别数不足: ${logits.size}"
                    }
                    val cls = logits.indices.maxByOrNull { logits[it] } ?: 0
                    val confidence = softmaxConfidence(logits, cls)
                    Log.d(
                        "ModelInference",
                        "推理: class=$cls confidence=${"%.3f".format(confidence)} " +
                                "[${logits.joinToString(",") { "%.2f".format(it) }}]"
                    )
                    return Prediction(cls, confidence, logits)
                }
            }
        } catch (e: Exception) {
            Log.e("ModelInference", "推理失败: ${e.message}", e)
            lastError = e.message
            return null
        }
    }

    private fun validateInputShape(shape: LongArray) {
        require(shape.size == 3 || shape.size == 4) {
            "仅支持 (1,2,N) 或 (1,1,2,N)，实际为 ${shape.joinToString("x")}"
        }
        require(shape[shape.lastIndex - 1] == CHANNELS.toLong() && shape.last() > 0) {
            "模型末两维必须为 2xN，实际为 ${shape.joinToString("x")}"
        }
        require(
            shape.dropLast(2).fold(1L) { acc, dim -> acc * dim } == 1L
        ) {
            "当前仅支持 batch=1 的单窗口推理"
        }
    }

    private fun extractLogits(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            val first = value.firstOrNull()
            when (first) {
                is FloatArray -> first
                is Array<*> -> extractLogits(first)
                else -> throw IllegalStateException("无法解析模型输出类型")
            }
        }
        else -> throw IllegalStateException("无法解析模型输出类型: ${value?.javaClass?.name}")
    }

    private fun softmaxConfidence(logits: FloatArray, classId: Int): Float {
        val max = logits.maxOrNull() ?: return 0f
        val exps = logits.map { kotlin.math.exp((it - max).toDouble()) }
        val sum = exps.sum()
        return if (sum > 0.0) (exps[classId] / sum).toFloat() else 0f
    }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        // OrtEnvironment 是进程级共享实例，不在单个推理器中关闭。
        env = null
        isLoaded = false
    }

    companion object {
        private const val CHANNELS = 2
        private const val DEFAULT_POINTS = 400
        private const val CLASS_COUNT = 4
    }
}

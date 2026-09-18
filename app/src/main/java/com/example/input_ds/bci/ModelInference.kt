package com.example.input_ds.bci

import ai.onnxruntime.*
import android.content.Context
import android.util.Log
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.UserModelManager
import org.json.JSONArray
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
        val logits: FloatArray,
        val probabilities: FloatArray
    )

    private var session: OrtSession? = null
    private var env: OrtEnvironment? = null
    var isLoaded = false; private set

    // 运行时检测的输入信息
    private var inputName: String = "eeg_input"
    private var inputShape: LongArray = longArrayOf(1, 2, 400)
    private var outputSemantics: String = OUTPUT_LOGITS
    private var classCount: Int = ClassificationProtocol.FOUR_CLASS.classCount
    var protocol: ClassificationProtocol = ClassificationProtocol.FOUR_CLASS
        private set
    var loadedFromActiveModel: Boolean = false
        private set
    var labelNames: List<String> = ClassificationProtocol.FOUR_CLASS.labelNames
        private set
    var inputPoints: Int = DEFAULT_POINTS
        private set
    var lastError: String? = null
        private set
    var loadedModelDescription: String = "未加载"
        private set

    @Synchronized
    fun loadModel(modelPath: String? = null): Boolean {
        try {
            close()
            lastError = null
            val activeModelPath = if (modelPath == null) {
                UserModelManager.resolveActiveModel(context)?.absolutePath
            } else {
                null
            }
            val selectedModel = modelPath ?: activeModelPath ?: findDefaultModel()
            Log.d("ModelInference", "开始加载: $selectedModel")
            env = OrtEnvironment.getEnvironment()
            val selectedFile = File(selectedModel)
            val file = if (selectedFile.isAbsolute) selectedFile else File(context.filesDir, selectedModel)
            val bytes: ByteArray
            if (file.exists()) {
                loadedModelDescription = file.absolutePath
                Log.d("ModelInference", "从内部存储: ${file.length()}B"); bytes = file.readBytes()
            } else {
                loadedModelDescription = "assets/${selectedFile.name}"
                Log.d("ModelInference", "从 assets 读取..."); bytes = context.assets.open(selectedFile.name).use { it.readBytes() }; Log.d("ModelInference", "assets: ${bytes.size}B")
            }
            loadedFromActiveModel = activeModelPath != null &&
                runCatching { file.canonicalFile == File(activeModelPath).canonicalFile }.getOrDefault(false)
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
            val outputInfo = session!!.outputInfo.entries.firstOrNull()?.value?.info as? TensorInfo
                ?: error("ONNX 没有 float 输出张量")
            require(outputInfo.type == OnnxJavaType.FLOAT) { "ONNX 输出必须为 float32" }
            val outputShape = outputInfo.shape
            require(outputShape.isNotEmpty() && outputShape.last() > 0) { "ONNX 输出类别维必须固定" }
            require(outputShape.dropLast(1).all { it == 1L || it == -1L }) { "ONNX 仅支持单窗口分类输出" }
            classCount = outputShape.last().toInt()
            val metadata = session!!.metadata.customMetadata
            val metadataLabels = parseLabels(metadata["label_names"])
            labelNames = if (metadataLabels.isNotEmpty()) metadataLabels else {
                require(classCount == ClassificationProtocol.FOUR_CLASS.classCount) {
                    "缺少 label_names 的模型只能作为旧四分类模型加载"
                }
                ClassificationProtocol.FOUR_CLASS.labelNames
            }
            require(labelNames.size == classCount) { "ONNX label_names 与输出类别数不一致" }
            val protocolMetadata = metadata["protocol"] ?: metadata["classification_protocol"]
            protocol = ClassificationProtocol.fromWireName(protocolMetadata)
                ?: ClassificationProtocol.inferLegacy(labelNames)
                ?: error("ONNX 缺少有效分类协议")
            require(labelNames == protocol.labelNames) { "ONNX 分类协议与标签顺序不一致" }
            // The production API names the tensor `logits`; v0.3.0 does not
            // require an equivalent custom-metadata entry.
            outputSemantics = metadata["output_semantics"] ?: OUTPUT_LOGITS
            require(outputSemantics == OUTPUT_LOGITS || outputSemantics == OUTPUT_PROBABILITIES) {
                "ONNX output_semantics 必须为 logits 或 probabilities"
            }
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
        UserModelManager.resolveActiveModel(context)?.let { return it.absolutePath }
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
                    require(logits.size == classCount && logits.all(Float::isFinite)) {
                        "模型输出必须为 $classCount 个有限值，实际: ${logits.size}"
                    }
                    val probabilities = when (outputSemantics) {
                        OUTPUT_LOGITS -> softmax(logits)
                        OUTPUT_PROBABILITIES -> normalizeProbabilities(logits)
                        else -> error("不支持的输出语义")
                    }
                    require(probabilities.all(Float::isFinite)) { "模型概率包含 NaN/Inf" }
                    val cls = probabilities.indices.maxByOrNull { probabilities[it] } ?: 0
                    val confidence = probabilities[cls]
                    Log.d(
                        "ModelInference",
                        "推理: class=$cls confidence=${"%.3f".format(confidence)} " +
                                "[${logits.joinToString(",") { "%.2f".format(it) }}]"
                    )
                    return Prediction(cls, confidence, logits, probabilities)
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

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.maxOrNull() ?: return FloatArray(0)
        val exps = logits.map { kotlin.math.exp((it - max).toDouble()) }
        val sum = exps.sum()
        return if (sum > 0.0) {
            FloatArray(exps.size) { (exps[it] / sum).toFloat() }
        } else {
            FloatArray(logits.size)
        }
    }

    private fun normalizeProbabilities(values: FloatArray): FloatArray {
        require(values.all { it.isFinite() && it >= 0f }) { "概率输出必须为有限非负数" }
        val sum = values.sumOf(Float::toDouble)
        require(sum.isFinite() && kotlin.math.abs(sum - 1.0) <= 0.01) { "概率输出总和必须接近 1" }
        return FloatArray(values.size) { (values[it] / sum).toFloat() }
    }

    private fun parseLabels(raw: String?): List<String> = runCatching {
        val json = JSONArray(requireNotNull(raw))
        List(json.length()) { json.getString(it) }
    }.getOrDefault(emptyList())

    @Synchronized
    fun close() {
        session?.close()
        session = null
        // OrtEnvironment 是进程级共享实例，不在单个推理器中关闭。
        env = null
        isLoaded = false
        loadedFromActiveModel = false
        protocol = ClassificationProtocol.FOUR_CLASS
        labelNames = ClassificationProtocol.FOUR_CLASS.labelNames
        classCount = labelNames.size
        outputSemantics = OUTPUT_LOGITS
        loadedModelDescription = "未加载"
    }

    companion object {
        private const val CHANNELS = 2
        private const val DEFAULT_POINTS = 400
        private const val OUTPUT_LOGITS = "logits"
        private const val OUTPUT_PROBABILITIES = "probabilities"
    }
}

package com.example.input_ds.personalization

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

class UserModelManager(context: Context) {
    data class ActiveModel(
        val modelFile: String,
        val modelKey: String,
        val jobId: String?,
        val protocol: ClassificationProtocol,
        val labelNames: List<String>
    )

    data class ValidatedModel(
        val file: File,
        val sha256: String,
        val inputPoints: Int,
        val metadata: Map<String, String>
    )

    data class DeletionResult(
        val activeModelDeleted: Boolean,
        val sessionRemoved: Boolean
    )

    private val appContext = context.applicationContext
    private val repository = PersonalizationRepository(appContext)

    fun validateDownloaded(tempFile: File, session: TrainingSession, run: ModelRun): ValidatedModel {
        require(tempFile.isFile && tempFile.length() > 0) { "下载的 ONNX 文件为空" }
        val expectedPoints = (session.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()
        val environment = OrtEnvironment.getEnvironment()
        OrtSession.SessionOptions().use { options ->
            environment.createSession(tempFile.absolutePath, options).use { ortSession ->
                val input = ortSession.inputInfo.entries.firstOrNull() ?: error("ONNX 没有输入张量")
                val inputInfo = input.value.info as? TensorInfo ?: error("ONNX 输入不是张量")
                require(inputInfo.type == OnnxJavaType.FLOAT) { "ONNX 输入必须为 float32" }
                val rawShape = inputInfo.shape
                val shape = rawShape.map { if (it < 0) 1L else it }.toLongArray()
                require(shape.size == 3 || shape.size == 4) { "ONNX 输入维度必须为 3D 或 4D" }
                require(shape[shape.lastIndex - 1] == 2L && shape.last() == expectedPoints.toLong()) {
                    "ONNX 输入应为双通道 $expectedPoints 点，实际 ${shape.joinToString("x")}"
                }
                require(shape.dropLast(2).fold(1L, Long::times) == 1L) { "ONNX 仅支持 batch=1" }

                val metadata = ortSession.metadata.customMetadata
                require(metadata["algorithm"] == run.modelKey) { "ONNX algorithm 与所选模型不一致" }
                require(metadata["sample_rate_hz"]?.toDoubleOrNull()?.toInt() == CollectionSettings.SAMPLE_RATE_HZ) {
                    "ONNX 采样率不是 ${CollectionSettings.SAMPLE_RATE_HZ} Hz"
                }
                require(metadata["channels"]?.toIntOrNull() == 2) { "ONNX 通道数不是 2" }
                require(metadata["input_points"]?.toIntOrNull() == expectedPoints) { "ONNX input_points 不一致" }
                val modelLabels = parseStringArray(metadata["label_names"])
                require(modelLabels == session.labelNames) { "ONNX 标签顺序不一致" }
                val protocolMetadata = metadata["protocol"] ?: metadata["classification_protocol"]
                val modelProtocol = ClassificationProtocol.fromWireName(protocolMetadata)
                    ?: ClassificationProtocol.inferLegacy(modelLabels)
                require(modelProtocol == session.protocol) { "ONNX 分类协议不一致" }
                run.preprocessingSha256?.let { expectedHash ->
                    require(metadata["inference_preprocessing_sha256"] == expectedHash) {
                        "ONNX 预处理契约校验失败"
                    }
                }
                require(parseStringArray(metadata["input_adapters"]).contains("numpy-npz")) {
                    "ONNX 未记录 numpy-npz 输入适配器"
                }
                validateContract(metadata["inference_preprocessing"], expectedPoints)

                val output = ortSession.outputInfo.entries.firstOrNull() ?: error("ONNX 没有输出张量")
                val outputInfo = output.value.info as? TensorInfo ?: error("ONNX 输出不是张量")
                require(outputInfo.type == OnnxJavaType.FLOAT) { "ONNX 输出必须为 float32" }
                val outputShape = outputInfo.shape.map { if (it < 0) 1L else it }.toLongArray()
                require(outputShape.isNotEmpty() && outputShape.last() == session.labelNames.size.toLong()) {
                    "ONNX 输出类别数与 Session 不一致"
                }
                require(outputShape.dropLast(1).fold(1L, Long::times) == 1L) { "ONNX 仅支持单窗口输出" }
                // API v0.3.0 exports the named `logits` output but does not
                // currently duplicate that fact in custom metadata.
                val outputSemantics = metadata["output_semantics"] ?: "logits"
                require(outputSemantics == "logits" || outputSemantics == "probabilities") {
                    "ONNX output_semantics 必须为 logits 或 probabilities"
                }

                val values = FloatBuffer.allocate(2 * expectedPoints)
                OnnxTensor.createTensor(environment, values, shape).use { tensor ->
                    ortSession.run(mapOf(input.key to tensor)).use { output ->
                        require(output.size() > 0) { "ONNX 测试推理没有输出" }
                        val values = extractFloatOutput(output[0].value)
                        require(values.size == session.labelNames.size && values.all(Float::isFinite)) {
                            "ONNX 测试推理输出无效"
                        }
                        if (outputSemantics == "probabilities") {
                            val sum = values.sumOf(Float::toDouble)
                            require(values.all { it >= 0f } && kotlin.math.abs(sum - 1.0) <= 0.01) {
                                "ONNX 概率输出无效"
                            }
                        }
                    }
                }
                return ValidatedModel(tempFile, sha256(tempFile), expectedPoints, metadata)
            }
        }
    }

    fun acceptDownloaded(
        localUserId: String,
        profileId: String,
        jobId: String,
        run: ModelRun,
        validated: ValidatedModel
    ): File {
        val safeJob = jobId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val target = File(repository.modelDirectory(localUserId, profileId), "${run.modelKey}-$safeJob.onnx")
        if (target.exists() && !target.delete()) error("无法替换本地模型文件")
        if (!validated.file.renameTo(target)) {
            validated.file.copyTo(target, overwrite = true)
            validated.file.delete()
        }
        File(target.parentFile, "${target.nameWithoutExtension}.json").writeText(
            JSONObject().apply {
                put("job_id", jobId)
                put("model_key", run.modelKey)
                put("sha256", validated.sha256)
                put("input_points", validated.inputPoints)
                put("protocol", validated.metadata["protocol"] ?: validated.metadata["classification_protocol"] ?: JSONObject.NULL)
                put("label_names", JSONArray(parseStringArray(validated.metadata["label_names"])))
                put("inference_preprocessing", run.preset.contract(validated.inputPoints))
                put("validation_accuracy", run.accuracy ?: JSONObject.NULL)
                put("downloaded_at_epoch_ms", System.currentTimeMillis())
            }.toString(2),
            Charsets.UTF_8
        )
        return target
    }

    @Synchronized
    fun deleteLocalRunAndTrainingData(
        session: TrainingSession,
        run: ModelRun
    ): DeletionResult {
        val modelDirectory = repository.modelDirectory(session.localUserId, session.profileId).canonicalFile
        val pointerFile = checkedDirectChild(modelDirectory, ACTIVE_POINTER)
        val activeModelName = runCatching {
            JSONObject(pointerFile.readText(Charsets.UTF_8)).optString("model_file")
        }.getOrNull()
        val model = run.modelFile?.let { checkedDirectChild(modelDirectory, it) }
        val sidecar = model?.let {
            checkedDirectChild(modelDirectory, "${it.nameWithoutExtension}.json")
        }
        val deletingActiveModel = model != null && activeModelName == model.name

        if (deletingActiveModel) {
            check(!pointerFile.exists() || pointerFile.delete()) { "无法清除当前启用模型" }
        }
        model?.let {
            check(!it.exists() || it.delete()) { "无法删除本地 ONNX 文件" }
        }
        sidecar?.let {
            check(!it.exists() || it.delete()) { "无法删除本地模型校验文件" }
        }

        val sessionRemoved = repository.deleteCollectedDataForRun(
            localUserId = session.localUserId,
            sessionId = session.sessionId,
            modelKey = run.modelKey
        )
        return DeletionResult(
            activeModelDeleted = deletingActiveModel,
            sessionRemoved = sessionRemoved
        )
    }

    @Synchronized
    fun activate(localUserId: String, session: TrainingSession, run: ModelRun) {
        val modelName = requireNotNull(run.modelFile)
        val directory = repository.modelDirectory(localUserId, session.profileId)
        val model = File(directory, modelName)
        require(model.isFile) { "待启用模型不存在" }
        val pointerFile = File(directory, ACTIVE_POINTER)
        val previous = runCatching { JSONObject(pointerFile.readText(Charsets.UTF_8)) }.getOrNull()
        val points = (session.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()
        val pointer = JSONObject().apply {
            put("model_file", model.name)
            put("model_key", run.modelKey)
            put("preset", run.preset.wireName)
            put("job_id", run.jobId)
            put("profile_id", session.profileId ?: JSONObject.NULL)
            put("client_profile_id", session.clientProfileId ?: JSONObject.NULL)
            put("accuracy", run.accuracy ?: JSONObject.NULL)
            put("sha256", sha256(model))
            put("input_points", points)
            put("protocol", session.protocol.wireName)
            put("label_names", JSONArray(session.labelNames))
            put("inference_preprocessing", run.preset.contract(points))
            put("activated_at_epoch_ms", System.currentTimeMillis())
            previous?.optString("model_file")?.takeIf { it.isNotBlank() }?.let { put("previous_model_file", it) }
            previous?.optString("model_key")?.takeIf { it.isNotBlank() }?.let { put("previous_model_key", it) }
        }
        atomicWrite(pointerFile, pointer.toString(2))
    }

    private fun validateContract(raw: String?, expectedPoints: Int) {
        val actual = runCatching { JSONObject(requireNotNull(raw)) }
            .getOrElse { error("ONNX 缺少有效 inference_preprocessing") }
        require(actual.optInt("window_points") == expectedPoints) { "ONNX 预处理窗口长度不一致" }
        val actualSteps = actual.optJSONArray("window_steps") ?: JSONArray()
        val expectedSteps = PreprocessingPreset.UNIFIED_1_45.contract(expectedPoints)
            .getJSONArray("window_steps")
        require(actualSteps.length() == expectedSteps.length()) { "ONNX 预处理步骤数量不一致" }
        for (index in 0 until expectedSteps.length()) {
            val expected = expectedSteps.getJSONObject(index)
            val received = actualSteps.getJSONObject(index)
            require(received.optString("operation") == expected.optString("operation")) { "ONNX 第 ${index + 1} 个预处理操作不一致" }
            require(received.optString("implementation") == expected.optString("implementation")) { "ONNX 第 ${index + 1} 个预处理实现不一致" }
            require(received.optString("operation") == "bandpass") { "ONNX 只能使用统一带通预处理" }
            require(
                received.optDouble("low_hz") == expected.optDouble("low_hz") &&
                    received.optDouble("high_hz") == expected.optDouble("high_hz") &&
                    received.optInt("order") == expected.optInt("order")
            ) { "ONNX 带通参数不一致" }
        }
    }

    private fun parseStringArray(raw: String?): List<String> = runCatching {
        val array = JSONArray(requireNotNull(raw))
        List(array.length()) { array.getString(it) }
    }.getOrDefault(emptyList())

    private fun extractFloatOutput(value: Any?): FloatArray = when (value) {
        is FloatArray -> value
        is Array<*> -> value.firstOrNull()?.let(::extractFloatOutput)
            ?: error("ONNX 输出为空")
        else -> error("ONNX 输出不是 float32 数组")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun atomicWrite(target: File, content: String) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun checkedDirectChild(parent: File, name: String): File {
        val child = File(parent, name).canonicalFile
        require(child.parentFile == parent) { "模型文件路径无效" }
        return child
    }

    companion object {
        private const val ACTIVE_POINTER = "active_model.json"

        fun resolveActiveModel(context: Context): File? {
            val repository = PersonalizationRepository(context)
            val localUserId = repository.activeUserId() ?: return null
            val directory = repository.modelDirectory(localUserId)
            val pointer = runCatching {
                JSONObject(File(directory, ACTIVE_POINTER).readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            return File(directory, pointer.optString("model_file")).takeIf(File::isFile)
        }

        fun activeModel(context: Context, localUserId: String): ActiveModel? {
            val repository = PersonalizationRepository(context)
            val pointer = runCatching {
                JSONObject(
                    File(repository.modelDirectory(localUserId), ACTIVE_POINTER)
                        .readText(Charsets.UTF_8)
                )
            }.getOrNull() ?: return null
            val modelFile = pointer.optString("model_file").takeIf(String::isNotBlank) ?: return null
            if (!File(repository.modelDirectory(localUserId), modelFile).isFile) return null
            val protocol = ClassificationProtocol.fromWireName(pointer.optString("protocol"))
                ?: ClassificationProtocol.FOUR_CLASS
            val labelNames = pointer.optJSONArray("label_names")?.let { labels ->
                List(labels.length()) { labels.getString(it) }
            } ?: ClassificationProtocol.FOUR_CLASS.labelNames
            if (labelNames != protocol.labelNames) return null
            return ActiveModel(
                modelFile = modelFile,
                modelKey = pointer.optString("model_key"),
                jobId = pointer.optString("job_id").takeIf(String::isNotBlank),
                protocol = protocol,
                labelNames = labelNames
            )
        }

        fun activeProtocol(context: Context): ClassificationProtocol? {
            val repository = PersonalizationRepository(context)
            val localUserId = repository.activeUserId() ?: return null
            val pointer = runCatching {
                JSONObject(File(repository.modelDirectory(localUserId), ACTIVE_POINTER).readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            return ClassificationProtocol.fromWireName(pointer.optString("protocol"))
                ?: ClassificationProtocol.FOUR_CLASS
        }

        fun activePreset(context: Context): PreprocessingPreset? {
            val repository = PersonalizationRepository(context)
            val localUserId = repository.activeUserId() ?: return null
            val pointer = runCatching {
                JSONObject(File(repository.modelDirectory(localUserId), ACTIVE_POINTER).readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            return PreprocessingPreset.fromPersisted(pointer.optString("preset"))
        }

        fun activeTrainingSession(context: Context): TrainingSession? {
            val repository = PersonalizationRepository(context)
            val localUserId = repository.activeUserId() ?: return null
            val pointer = runCatching {
                JSONObject(File(repository.modelDirectory(localUserId), ACTIVE_POINTER).readText(Charsets.UTF_8))
            }.getOrNull() ?: return null
            val modelFile = pointer.optString("model_file")
            val jobId = pointer.optString("job_id")
            val modelKey = pointer.optString("model_key")
            val pointerProtocol = ClassificationProtocol.fromWireName(pointer.optString("protocol"))
                ?: ClassificationProtocol.FOUR_CLASS
            return repository.listSessions(localUserId).firstOrNull { session ->
                session.protocol == pointerProtocol && session.modelRuns.any { run ->
                    run.modelKey == modelKey &&
                        (run.modelFile == modelFile || jobId.isNotBlank() && run.jobId == jobId)
                }
            }
        }
    }
}

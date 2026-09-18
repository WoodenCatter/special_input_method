package com.example.input_ds.bci

import android.content.Context
import android.util.Log
import com.example.input_ds.personalization.ClassificationProtocol
import com.example.input_ds.personalization.CollectionSettings
import com.example.input_ds.personalization.PersonalizationRepository
import com.example.input_ds.personalization.TrainingSession
import com.example.input_ds.personalization.UserModelManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.math.roundToInt

object AsyncCalibrationManager {
    private const val FILE_NAME = "async_calibration.json"
    private const val CALIBRATION_VERSION = 2
    private const val TEMPLATE_POINTS = 64
    private const val FALLBACK_DIRECTION_STD_UV = 70f
    private const val FALLBACK_BITE_STD_UV = 105f

    internal data class SixCalibrationThresholds(
        val directionMinStdUv: Float,
        val sequenceMinStdUv: Float,
        val biteMinStdUv: Float,
        val sequenceMinSpanUv: Float
    )

    private data class TrialFeature(
        val activity: Float,
        val robustSpan: Float,
        val template: FloatArray
    )

    fun loadForActiveModel(context: Context, windowPoints: Int): AsyncCalibration {
        val session = UserModelManager.activeTrainingSession(context)
            ?: return fallbackFour(windowPoints, "缺少匹配训练 Session，使用四分类回退门槛")
        return loadForSession(context, session, windowPoints)
    }

    fun loadForBuiltInModel(windowPoints: Int): AsyncCalibration =
        fallbackFour(windowPoints, "内置四分类模型默认校准")

    fun loadForSession(
        context: Context,
        session: TrainingSession,
        windowPoints: Int = session.windowPoints
    ): AsyncCalibration {
        require(session.windowPoints == windowPoints) { "校准窗口与模型窗口不一致" }
        require(session.labelNames == session.protocol.labelNames) { "Session 协议与标签顺序不一致" }
        val repository = PersonalizationRepository(context)
        val directory = repository.createSessionDirectory(session.localUserId, session.sessionId)
        val file = File(directory, FILE_NAME)
        read(file, session, windowPoints)?.let { return it }
        val npz = File(directory, session.npzFile)
        return runCatching { generate(session, npz, file) }
            .onFailure { Log.w("AsyncCalibration", "生成校准失败", it) }
            .getOrElse { error ->
                if (session.protocol == ClassificationProtocol.FOUR_CLASS) {
                    fallbackFour(windowPoints, "Session校准生成失败，使用四分类回退门槛: ${error.message}")
                } else {
                    throw IllegalStateException("六动作 Session 缺少有效匹配校准，拒绝启动控制", error)
                }
            }
    }

    fun generateForSession(context: Context, session: TrainingSession): AsyncCalibration {
        val repository = PersonalizationRepository(context)
        val directory = repository.createSessionDirectory(session.localUserId, session.sessionId)
        return generate(session, File(directory, session.npzFile), File(directory, FILE_NAME))
    }

    private fun generate(session: TrainingSession, npz: File, output: File): AsyncCalibration {
        require(npz.isFile) { "Session NPZ不存在" }
        require(session.labelNames == session.protocol.labelNames) { "Session 协议与标签顺序不一致" }
        val arrays = readStereoNpz(npz)
        val points = session.windowPoints
        val grouped = mutableMapOf<String, MutableList<TrialFeature>>()
        fun addFeature(label: String, start: Int) {
            val end = start + points
            if (start < 0 || end > arrays.first.size || end > arrays.second.size) return
            val difference = FloatArray(points) { index -> arrays.first[start + index] - arrays.second[start + index] }
            require(difference.all(Float::isFinite)) { "Session 校准窗口包含 NaN/Inf" }
            grouped.getOrPut(label) { mutableListOf() }.add(
                TrialFeature(
                    activity = standardDeviation(difference),
                    robustSpan = robustSpan(difference),
                    template = normalizeTemplate(resample(difference, TEMPLATE_POINTS))
                )
            )
        }
        if (session.asyncTrials.isNotEmpty()) {
            session.asyncTrials.forEach { trial ->
                addFeature(trial.taskLabel, trial.taskStartSample)
                if (trial.taskStartSample - trial.trialStartSample >= points) {
                    addFeature("rest", trial.trialStartSample)
                }
                if (trial.trialEndSample - trial.taskEndSample >= points) {
                    addFeature("rest", trial.trialEndSample - points)
                }
            }
        } else {
            session.markers.forEach { marker ->
                addFeature(marker.label, maxOf(marker.leftSample, marker.rightSample))
            }
        }
        return when (session.protocol) {
            ClassificationProtocol.FOUR_CLASS -> generateFour(session, points, grouped, output)
            ClassificationProtocol.SIX_ACTION -> generateSix(session, points, grouped, output)
        }
    }

    private fun generateFour(
        session: TrainingSession,
        points: Int,
        grouped: Map<String, List<TrialFeature>>,
        output: File
    ): AsyncCalibration {
        val rest = grouped["rest"].orEmpty().map(TrialFeature::activity)
        val bite = grouped["jaw"].orEmpty().map(TrialFeature::activity)
        val directions = (grouped["look_left"].orEmpty() + grouped["look_right"].orEmpty())
            .map(TrialFeature::activity)
        require(rest.isNotEmpty() && bite.isNotEmpty() && directions.isNotEmpty()) {
            "Session四分类校准样本不完整"
        }
        val restP95 = percentile(rest, 0.95f)
        val biteP10 = percentile(bite, 0.10f)
        val directionP10 = percentile(directions, 0.10f)
        val thresholds = calculateThresholds(restP95, biteP10, directionP10, points)
        val calibration = AsyncCalibration(
            windowPoints = points,
            directionMinStdUv = thresholds.first,
            biteMinStdUv = thresholds.second,
            source = "Session ${session.sessionId} 自动校准",
            protocol = ClassificationProtocol.FOUR_CLASS,
            sessionId = session.sessionId
        )
        writeCalibration(
            output,
            session,
            calibration,
            JSONObject().apply {
                put("rest_diff_std_p95", restP95.toDouble())
                put("bite_diff_std_p10", biteP10.toDouble())
                put("direction_diff_std_p10", directionP10.toDouble())
            }
        )
        return calibration
    }

    private fun generateSix(
        session: TrainingSession,
        points: Int,
        grouped: Map<String, List<TrialFeature>>,
        output: File
    ): AsyncCalibration {
        val requiredMotionLabels = listOf("look_left", "look_right", "look_left_right", "look_right_left")
        require((listOf("rest", "jaw") + requiredMotionLabels).all { grouped[it].orEmpty().isNotEmpty() }) {
            "Session六动作校准样本不完整"
        }
        val restP95 = percentile(grouped.getValue("rest").map(TrialFeature::activity), 0.95f)
        val biteP10 = percentile(grouped.getValue("jaw").map(TrialFeature::activity), 0.10f)
        val directionTrials = grouped.getValue("look_left") + grouped.getValue("look_right")
        val sequenceTrials = grouped.getValue("look_left_right") + grouped.getValue("look_right_left")
        val directionP10 = percentile(directionTrials.map(TrialFeature::activity), 0.10f)
        val sequenceP10 = percentile(sequenceTrials.map(TrialFeature::activity), 0.10f)
        val directionSpanP95 = percentile(directionTrials.map(TrialFeature::robustSpan), 0.95f)
        val sequenceSpanP10 = percentile(sequenceTrials.map(TrialFeature::robustSpan), 0.10f)
        val thresholds = calculateSixThresholds(
            restP95,
            biteP10,
            directionP10,
            sequenceP10,
            directionSpanP95,
            sequenceSpanP10,
            points
        )
        val templates = mapOf(
            1 to medianTemplate(grouped.getValue("look_left").map(TrialFeature::template)),
            2 to medianTemplate(grouped.getValue("look_right").map(TrialFeature::template)),
            4 to medianTemplate(grouped.getValue("look_left_right").map(TrialFeature::template)),
            5 to medianTemplate(grouped.getValue("look_right_left").map(TrialFeature::template))
        )
        val calibration = AsyncCalibration(
            windowPoints = points,
            directionMinStdUv = thresholds.directionMinStdUv,
            biteMinStdUv = thresholds.biteMinStdUv,
            source = "Session ${session.sessionId} 六动作自动校准",
            protocol = ClassificationProtocol.SIX_ACTION,
            sequenceMinStdUv = thresholds.sequenceMinStdUv,
            sequenceMinSpanUv = thresholds.sequenceMinSpanUv,
            motionTemplates = templates,
            sessionId = session.sessionId
        )
        writeCalibration(
            output,
            session,
            calibration,
            JSONObject().apply {
                put("rest_diff_std_p95", restP95.toDouble())
                put("bite_diff_std_p10", biteP10.toDouble())
                put("direction_diff_std_p10", directionP10.toDouble())
                put("sequence_diff_std_p10", sequenceP10.toDouble())
                put("direction_span_p95", directionSpanP95.toDouble())
                put("sequence_span_p10", sequenceSpanP10.toDouble())
            }
        )
        return calibration
    }

    private fun writeCalibration(
        output: File,
        session: TrainingSession,
        calibration: AsyncCalibration,
        statistics: JSONObject
    ) {
        val parameters = JSONObject().apply {
            put("async_direction_min_std_uv", calibration.directionMinStdUv.toDouble())
            put("async_bite_min_std_uv", calibration.biteMinStdUv.toDouble())
            if (calibration.protocol == ClassificationProtocol.SIX_ACTION) {
                put("async_sequence_min_std_uv", calibration.sequenceMinStdUv.toDouble())
                put("async_sequence_min_span_uv", calibration.sequenceMinSpanUv.toDouble())
                put("async_motion_templates", JSONObject().apply {
                    calibration.motionTemplates.forEach { (classId, template) ->
                        put(classId.toString(), JSONArray().apply { template.forEach { put(it.toDouble()) } })
                    }
                })
            }
        }
        val content = JSONObject().apply {
                put("calibration_version", CALIBRATION_VERSION)
                put("protocol", session.protocol.wireName)
                put("session_id", session.sessionId)
                put("label_names", JSONArray(session.labelNames))
                put("sample_rate_hz", CollectionSettings.SAMPLE_RATE_HZ)
                put("window_points", calibration.windowPoints)
                put("window_seconds", calibration.windowPoints.toDouble() / CollectionSettings.SAMPLE_RATE_HZ)
                put("parameters", parameters)
                put("statistics", statistics)
            }.toString(2)
        val temporary = File(output.parentFile, "${output.name}.tmp")
        temporary.writeText(content, Charsets.UTF_8)
        if (!temporary.renameTo(output)) {
            output.writeText(content, Charsets.UTF_8)
            temporary.delete()
        }
    }

    private fun read(
        file: File,
        session: TrainingSession,
        expectedPoints: Int
    ): AsyncCalibration? = runCatching {
        if (!file.isFile) return@runCatching null
        val json = JSONObject(file.readText(Charsets.UTF_8))
        if (json.optInt("calibration_version", 1) != CALIBRATION_VERSION ||
            json.optString("protocol") != session.protocol.wireName ||
            json.optInt("window_points") != expectedPoints
        ) return@runCatching null
        val sessionId = json.optString("session_id")
        if (sessionId.isNotBlank() && sessionId != session.sessionId) return@runCatching null
        val persistedLabels = json.optJSONArray("label_names")?.let { labels ->
            List(labels.length()) { labels.getString(it) }
        }
        if (persistedLabels != null && persistedLabels != session.labelNames) return@runCatching null
        val parameters = json.getJSONObject("parameters")
        val templates = parameters.optJSONObject("async_motion_templates")?.let { templateJson ->
            listOf(1, 2, 4, 5).associateWith { classId ->
                val values = templateJson.getJSONArray(classId.toString())
                FloatArray(values.length()) { values.getDouble(it).toFloat() }
            }
        }.orEmpty()
        if (session.protocol == ClassificationProtocol.SIX_ACTION) {
            require(templates.keys == setOf(1, 2, 4, 5) && templates.values.all { it.size == TEMPLATE_POINTS })
        }
        AsyncCalibration(
            windowPoints = expectedPoints,
            directionMinStdUv = parameters.getDouble("async_direction_min_std_uv").toFloat(),
            biteMinStdUv = parameters.getDouble("async_bite_min_std_uv").toFloat(),
            source = "匹配 Session 校准文件 ${session.sessionId}",
            isSessionCalibrated = true,
            protocol = session.protocol,
            sequenceMinStdUv = parameters.optDouble("async_sequence_min_std_uv", Double.NaN)
                .takeIf(Double::isFinite)?.toFloat() ?: parameters.getDouble("async_direction_min_std_uv").toFloat(),
            sequenceMinSpanUv = parameters.optDouble("async_sequence_min_span_uv", Double.POSITIVE_INFINITY).toFloat(),
            motionTemplates = templates,
            sessionId = session.sessionId
        )
    }.getOrNull()

    private fun fallbackFour(points: Int, reason: String) = AsyncCalibration(
        points,
        FALLBACK_DIRECTION_STD_UV,
        FALLBACK_BITE_STD_UV,
        reason,
        false,
        ClassificationProtocol.FOUR_CLASS
    )

    internal fun calculateThresholds(
        restP95: Float,
        biteP10: Float,
        directionP10: Float,
        windowPoints: Int
    ): Pair<Float, Float> {
        val coverage = minOf(1f, CollectionSettings.SAMPLE_RATE_HZ.toFloat() / windowPoints)
        fun threshold(actionP10: Float, fraction: Float, restMultiplier: Float) =
            minOf(
                actionP10,
                maxOf(restP95 * restMultiplier, actionP10 * fraction * coverage)
            )
        // Full cued trials overestimate the activity present in an arbitrary
        // asynchronous sliding window. Keep a wide margin over rest while
        // allowing windows that contain only part of the eye movement.
        return threshold(directionP10, 0.60f, 2.5f) to
            threshold(biteP10, 0.95f, 1.5f)
    }

    internal fun calculateSixThresholds(
        restP95: Float,
        biteP10: Float,
        directionP10: Float,
        sequenceP10: Float,
        directionSpanP95: Float,
        sequenceSpanP10: Float,
        windowPoints: Int
    ): SixCalibrationThresholds {
        require(listOf(restP95, biteP10, directionP10, sequenceP10, directionSpanP95, sequenceSpanP10).all {
            it.isFinite() && it >= 0f
        })
        val coverage = minOf(1f, CollectionSettings.SAMPLE_RATE_HZ.toFloat() / windowPoints)
        fun threshold(actionP10: Float, fraction: Float) =
            minOf(actionP10, maxOf(restP95 * 1.5f, actionP10 * fraction * coverage))
        return SixCalibrationThresholds(
            directionMinStdUv = threshold(directionP10, 0.75f),
            sequenceMinStdUv = threshold(sequenceP10, 0.75f),
            biteMinStdUv = threshold(biteP10, 0.95f),
            sequenceMinSpanUv = (directionSpanP95 + sequenceSpanP10) / 2f
        )
    }

    private fun standardDeviation(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val mean = values.sumOf(Float::toDouble) / values.size
        val variance = values.sumOf { value ->
            val centered = value - mean
            centered * centered
        } / values.size
        return sqrt(variance).toFloat()
    }

    private fun robustSpan(values: FloatArray): Float =
        percentile(values.toList(), 0.95f) - percentile(values.toList(), 0.05f)

    private fun percentile(values: List<Float>, q: Float): Float {
        require(values.isNotEmpty())
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted.first()
        val position = (sorted.size - 1) * q
        val low = floor(position).toInt()
        val high = ceil(position).toInt()
        val fraction = position - low
        return sorted[low] * (1f - fraction) + sorted[high] * fraction
    }

    private fun resample(source: FloatArray, targetPoints: Int): FloatArray {
        if (source.size == targetPoints) return source.copyOf()
        if (source.size == 1) return FloatArray(targetPoints) { source[0] }
        return FloatArray(targetPoints) { index ->
            val position = index.toDouble() * (source.lastIndex.toDouble() / (targetPoints - 1))
            val lower = floor(position).toInt()
            val upper = ceil(position).toInt().coerceAtMost(source.lastIndex)
            val fraction = (position - lower).toFloat()
            source[lower] * (1f - fraction) + source[upper] * fraction
        }
    }

    private fun normalizeTemplate(values: FloatArray): FloatArray {
        val mean = values.sumOf(Float::toDouble) / values.size
        val centered = FloatArray(values.size) { values[it] - mean.toFloat() }
        val norm = sqrt(centered.sumOf { it.toDouble() * it }).toFloat()
        return if (norm > 1e-8f) FloatArray(values.size) { centered[it] / norm } else FloatArray(values.size)
    }

    private fun medianTemplate(trials: List<FloatArray>): FloatArray {
        require(trials.isNotEmpty() && trials.all { it.size == TEMPLATE_POINTS })
        val median = FloatArray(TEMPLATE_POINTS) { point ->
            percentile(trials.map { it[point] }, 0.5f)
        }
        return normalizeTemplate(median)
    }

    private fun readStereoNpz(file: File): Pair<FloatArray, FloatArray> = ZipFile(file).use { zip ->
        val left = zip.getEntry("left.npy") ?: error("NPZ缺少left.npy")
        val right = zip.getEntry("right.npy") ?: error("NPZ缺少right.npy")
        readNpy(zip.getInputStream(left).readBytes()) to readNpy(zip.getInputStream(right).readBytes())
    }

    private fun readNpy(bytes: ByteArray): FloatArray {
        require(bytes.size >= 10 && bytes[0] == 0x93.toByte()) { "无效NPY" }
        val major = bytes[6].toInt()
        val headerLength = if (major == 1) {
            ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        } else {
            ByteBuffer.wrap(bytes, 8, 4).order(ByteOrder.LITTLE_ENDIAN).int
        }
        val dataOffset = if (major == 1) 10 + headerLength else 12 + headerLength
        require(dataOffset <= bytes.size && (bytes.size - dataOffset) % Float.SIZE_BYTES == 0) { "NPY长度无效" }
        val buffer = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray((bytes.size - dataOffset) / Float.SIZE_BYTES) { buffer.float }
    }

    private val TrainingSession.windowPoints: Int
        get() = (actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()
}

package com.example.input_ds.bci

import android.content.Context
import com.example.input_ds.personalization.ClassificationProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class AsyncControlLogger(
    context: Context,
    windowPoints: Int,
    stridePoints: Int,
    calibration: AsyncCalibration,
    preprocessing: String,
    modelDescription: String,
    protocol: ClassificationProtocol = calibration.protocol,
    classOrder: List<String> = protocol.labelNames
) : AutoCloseable {
    private val directory = File(
        context.filesDir,
        "bci_async_runs/${SimpleDateFormat("yyyyMMdd-HHmmss.SSS", Locale.US).format(Date())}"
    ).apply { mkdirs() }
    private val windows = File(directory, "windows.jsonl").bufferedWriter(Charsets.UTF_8)
    private val events = File(directory, "events.jsonl").bufferedWriter(Charsets.UTF_8)
    private var windowId = 0L
    private var eventId = 0L

    init {
        File(directory, "run.json").writeText(
            JSONObject().apply {
                put("mode", "ASYNCHRONOUS")
                put(
                    "detector_contract",
                    if (protocol == ClassificationProtocol.FOUR_CLASS) {
                        "four-class-candidate-gate-support-temporal-v4"
                    } else {
                        "six-action-candidate-gate-support-temporal-v3"
                    }
                )
                put("sample_rate_hz", 500)
                put("window_points", windowPoints)
                put("stride_points", stridePoints)
                put("stride_seconds", AsyncWindowPolicy.STRIDE_SECONDS.toDouble())
                put("evidence_windows", AsyncWindowPolicy.EVIDENCE_WINDOWS)
                put("confidence_threshold", AsyncWindowPolicy.CONFIDENCE_THRESHOLD.toDouble())
                put("support_required", AsyncWindowPolicy.SUPPORT_REQUIRED)
                put("evidence_mode", "support_confidence")
                put("physical_support_required", AsyncWindowPolicy.PHYSICAL_SUPPORT_REQUIRED)
                put("rest_reset_required", AsyncWindowPolicy.REST_RESET_REQUIRED)
                put("protocol", protocol.wireName)
                put("session_id", calibration.sessionId ?: JSONObject.NULL)
                put("class_order", JSONArray(classOrder))
                put("direction_min_std_uv", calibration.directionMinStdUv.toDouble())
                put("bite_min_std_uv", calibration.biteMinStdUv.toDouble())
                if (protocol == ClassificationProtocol.SIX_ACTION) {
                    put("sequence_min_std_uv", calibration.sequenceMinStdUv.toDouble())
                    put("sequence_min_span_uv", calibration.sequenceMinSpanUv.toDouble())
                    put("template_shift_fraction", 0.25)
                }
                put("calibration_source", calibration.source)
                put("preprocessing", preprocessing)
                put("model", modelDescription)
            }.toString(2),
            Charsets.UTF_8
        )
    }

    @Synchronized
    fun logWindow(
        endSample: Int,
        probabilities: FloatArray,
        decision: DetectorResult,
        inferenceMs: Long,
        pointCount: Int,
        leftRaw: FloatArray,
        rightRaw: FloatArray
    ) {
        val id = ++windowId
        appendLine(windows, JSONObject().apply {
            put("window_id", id)
            put("start_sample", endSample - pointCount)
            put("end_sample", endSample)
            put("point_count", pointCount)
            put("probabilities", probabilities.toJson())
            put("control_probabilities", decision.controlProbabilities.toJson())
            put("activity_std_uv", decision.features.activityStdUv.toDouble())
            put("direction_score", decision.features.directionScore.toDouble())
            put("allowed_direction_class", decision.features.allowedDirectionClass ?: JSONObject.NULL)
            put("direction_evidence", decision.features.allowedDirectionClass != null)
            put("direction_class_source", "model")
            put("strong_bite", decision.strongBite)
            put("strong_direction", decision.strongDirection)
            put("detector_state", decision.state.name)
            put("event_class", decision.eventClass ?: JSONObject.NULL)
            put("inference_ms", inferenceMs)
        })
        decision.eventClass?.let {
            logEvent(id, it, decision.controlProbabilities.getOrElse(it) { 0f }, endSample, leftRaw, rightRaw, inferenceMs)
        }
    }

    @Synchronized
    fun logWindow(
        endSample: Int,
        probabilities: FloatArray,
        decision: SixDetectorResult,
        inferenceMs: Long,
        pointCount: Int,
        leftRaw: FloatArray,
        rightRaw: FloatArray
    ) {
        val id = ++windowId
        appendLine(windows, JSONObject().apply {
            put("window_id", id)
            put("start_sample", endSample - pointCount)
            put("end_sample", endSample)
            put("point_count", pointCount)
            put("predicted_class", probabilities.indices.maxByOrNull { probabilities[it] } ?: JSONObject.NULL)
            put("probabilities", probabilities.toJson())
            put("control_probabilities", decision.controlProbabilities.toJson())
            put("direction_score", decision.features.directionScore.toDouble())
            put("direction_differential_std", decision.features.activityStdUv.toDouble())
            put("robust_span_uv", decision.features.robustSpanUv.toDouble())
            put("motion_allowed_class", decision.features.motionAllowedClass ?: JSONObject.NULL)
            put("sequence_phase_class", decision.features.sequencePhaseClass ?: JSONObject.NULL)
            put("motion_scores", JSONObject().apply {
                decision.features.motionScores.forEach { (classId, score) -> put(classId.toString(), score.toDouble()) }
            })
            put("motion_fused_probability", decision.features.motionFusedProbability.toDouble())
            put("single_window_classes", JSONArray(decision.singleWindowClasses.toList()))
            put("strong_bite", decision.strongBite)
            put("detector_state", decision.snapshot.state.name)
            put("active_class", decision.snapshot.activeClass ?: JSONObject.NULL)
            put("candidate_class", decision.snapshot.candidateClass ?: JSONObject.NULL)
            put("candidate_hits", decision.snapshot.candidateHits)
            put("candidate_age", decision.snapshot.candidateAge)
            put("release_hits", decision.snapshot.releaseHits)
            put("refractory_until_ms", decision.snapshot.refractoryUntilMs)
            put("sequence_endpoint_seen", decision.snapshot.sequenceEndpointSeen)
            put("event_class", decision.eventClass ?: JSONObject.NULL)
            put("inference_ms", inferenceMs)
        })
        decision.eventClass?.let {
            logEvent(id, it, decision.controlProbabilities.getOrElse(it) { 0f }, endSample, leftRaw, rightRaw, inferenceMs)
        }
    }

    private fun logEvent(
        windowId: Long,
        classId: Int,
        probability: Float,
        endSample: Int,
        leftRaw: FloatArray,
        rightRaw: FloatArray,
        inferenceMs: Long
    ) {
        val eventNumber = ++eventId
        val rawFile = File(directory, "event-${eventNumber.toString().padStart(4, '0')}.npy")
        writeStereoNpy(rawFile, leftRaw, rightRaw)
        appendLine(events, JSONObject().apply {
            put("event_id", eventNumber)
            put("window_id", windowId)
            put("class_id", classId)
            put("probability", probability.toDouble())
            put("end_sample", endSample)
            put("processing_ms", inferenceMs)
            put("raw_window_file", rawFile.name)
        })
    }

    private fun appendLine(writer: BufferedWriter, json: JSONObject) {
        writer.append(json.toString()).append('\n')
        writer.flush()
    }

    override fun close() {
        runCatching { windows.close() }
        runCatching { events.close() }
    }

    private fun FloatArray.toJson() = JSONArray().apply { forEach { put(it.toDouble()) } }

    private fun writeStereoNpy(file: File, left: FloatArray, right: FloatArray) {
        val count = minOf(left.size, right.size)
        val dictionary = "{'descr': '<f4', 'fortran_order': False, 'shape': (2, $count), }"
        val baseLength = 10 + dictionary.toByteArray(Charsets.US_ASCII).size + 1
        val padding = (16 - baseLength % 16) % 16
        val header = (dictionary + " ".repeat(padding) + "\n").toByteArray(Charsets.US_ASCII)
        file.outputStream().buffered().use { output ->
            output.write(byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte(), 1, 0))
            output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.size.toShort()).array())
            output.write(header)
            val buffer = ByteBuffer.allocate(8 * 1024).order(ByteOrder.LITTLE_ENDIAN)
            listOf(left, right).forEach { channel ->
                for (index in 0 until count) {
                    if (buffer.remaining() < Float.SIZE_BYTES) {
                        output.write(buffer.array(), 0, buffer.position())
                        buffer.clear()
                    }
                    buffer.putFloat(channel[index])
                }
            }
            if (buffer.position() > 0) output.write(buffer.array(), 0, buffer.position())
        }
    }
}

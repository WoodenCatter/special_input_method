package com.example.input_ds.personalization

import com.example.input_ds.bci.EegPreprocessStep
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import kotlin.math.roundToInt

enum class ClassificationProtocol(
    val wireName: String,
    val labelNames: List<String>
) {
    FOUR_CLASS("four_class", listOf("rest", "jaw", "look_left", "look_right")),
    SIX_ACTION("six_action", listOf("rest", "look_left", "look_right", "jaw", "look_left_right", "look_right_left"));

    val classCount: Int get() = labelNames.size
    val serverWireName: String
        get() = if (this == SIX_ACTION) "six_class" else wireName

    companion object {
        fun fromWireName(value: String?): ClassificationProtocol? =
            if (value == "six_class") SIX_ACTION else entries.firstOrNull { it.wireName == value }

        fun inferLegacy(labelNames: List<String>): ClassificationProtocol? =
            entries.firstOrNull { it.labelNames == labelNames }
    }
}

enum class PreprocessingPreset(val wireName: String, val displayName: String) {
    UNIFIED_1_45("unified-bandpass-1-45", "统一 · 1–45 Hz 带通");

    fun contract(windowPoints: Int): JSONObject = JSONObject().apply {
        put("version", "1")
        put("window_points", windowPoints)
        put("stream_steps", JSONArray())
        put("window_steps", JSONArray().apply {
            put(bandpass(1.0, 45.0, 2))
        })
    }

    fun displaySteps(): Set<EegPreprocessStep> = EegPreprocessStep.DEFAULT

    companion object {
        private fun bandpass(lowHz: Double, highHz: Double, order: Int) = JSONObject().apply {
            put("operation", "bandpass")
            put("implementation", "butterworth_filtfilt")
            put("low_hz", lowHz)
            put("high_hz", highHz)
            put("order", order)
        }

        /** Old preset names deliberately migrate to the one supported contract. */
        fun fromPersisted(@Suppress("UNUSED_PARAMETER") wireName: String?): PreprocessingPreset =
            UNIFIED_1_45

    }
}

data class LocalUser(
    val localId: String = UUID.randomUUID().toString(),
    val displayName: String,
    /** Stable phone-generated identity. It must never change across retries or app restarts. */
    val clientProfileId: String = localId,
    /** Server identity returned by /api/v1/me/profiles after the first successful sync. */
    val profileId: String? = null,
    val profileEnabled: Boolean? = null,
    val profileSyncError: String? = null,
    val serverUserId: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("local_id", localId)
        put("display_name", displayName)
        put("client_profile_id", clientProfileId)
        put("profile_id", profileId ?: JSONObject.NULL)
        put("profile_enabled", profileEnabled ?: JSONObject.NULL)
        put("profile_sync_error", profileSyncError ?: JSONObject.NULL)
        put("server_user_id", serverUserId ?: JSONObject.NULL)
        put("created_at_epoch_ms", createdAtEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject): LocalUser {
            val localId = json.getString("local_id")
            return LocalUser(
                localId = localId,
                displayName = json.getString("display_name"),
                clientProfileId = json.optNullableString("client_profile_id") ?: localId,
                profileId = json.optNullableString("profile_id"),
                profileEnabled = if (json.isNull("profile_enabled")) null else json.optBoolean("profile_enabled"),
                profileSyncError = json.optNullableString("profile_sync_error"),
                serverUserId = json.optString("server_user_id")
                    .takeIf { it.isNotBlank() && it != "null" },
                createdAtEpochMs = json.optLong("created_at_epoch_ms", 0L)
            )
        }
    }
}

data class CollectionSettings(
    val rounds: Int = 20,
    val trainingEpochs: Int = 50,
    val actionSeconds: Float = 1f,
    val protocol: ClassificationProtocol = ClassificationProtocol.FOUR_CLASS,
    val selectedModels: Set<String> = setOf("csanet")
) {
    val windowPoints: Int get() = (actionSeconds * SAMPLE_RATE_HZ).roundToInt()
    val phaseDurationMs: Long get() = (actionSeconds * 1000).toLong()
    val labels: List<String> get() = protocol.labelNames

    init {
        require(rounds in 4..100)
        require(trainingEpochs in 1..50)
        require(actionSeconds in 0.8f..5f)
        require(selectedModels.isNotEmpty())
    }

    companion object {
        const val SAMPLE_RATE_HZ = 500
        /** Legacy four-class alias. New code must use the selected protocol. */
        val LABELS = ClassificationProtocol.FOUR_CLASS.labelNames
        val LABEL_DISPLAY = mapOf(
            "rest" to "静息",
            "jaw" to "咬牙",
            "look_left" to "左看",
            "look_right" to "右看",
            "look_left_right" to "左右",
            "look_right_left" to "右左"
        )
    }
}

data class SessionMarker(
    val label: String,
    val leftSample: Int,
    val rightSample: Int,
    val round: Int
) {
    fun toJson() = JSONObject().apply {
        put("label", label)
        put("left_sample", leftSample)
        put("right_sample", rightSample)
        put("round", round)
    }

    companion object {
        fun fromJson(json: JSONObject) = SessionMarker(
            label = json.getString("label"),
            leftSample = json.getInt("left_sample"),
            rightSample = json.getInt("right_sample"),
            round = json.optInt("round", 0)
        )
    }
}

data class SampleInterval(
    val startSample: Int,
    val endSample: Int
) {
    init {
        require(startSample >= 0)
        require(endSample > startSample)
    }

    fun toJson() = JSONObject().apply {
        put("start_sample", startSample)
        put("end_sample", endSample)
    }

    companion object {
        fun fromJson(json: JSONObject) = SampleInterval(
            startSample = json.getInt("start_sample"),
            endSample = json.getInt("end_sample")
        )
    }
}

/** One complete asynchronous trial on the compact, aligned session timeline. */
data class AsyncTrialAnnotation(
    val trialId: Int,
    val taskLabel: String,
    val trialStartSample: Int,
    val taskStartSample: Int,
    val taskEndSample: Int,
    val trialEndSample: Int,
    val ignoreIntervals: List<SampleInterval>,
    val preRestSeconds: Float,
    val postRestSeconds: Float,
    val alignmentQuality: Float
) {
    init {
        require(trialId > 0)
        require(trialStartSample >= 0)
        require(trialStartSample <= taskStartSample)
        require(taskStartSample < taskEndSample)
        require(taskEndSample <= trialEndSample)
        require(preRestSeconds >= 0f && postRestSeconds >= 0f)
        require(alignmentQuality in 0f..1f)
        require(ignoreIntervals.all {
            it.startSample >= trialStartSample && it.endSample <= trialEndSample
        })
    }

    fun toJson() = JSONObject().apply {
        put("trial_id", trialId)
        put("task_label", taskLabel)
        put("trial_start_sample", trialStartSample)
        put("task_start_sample", taskStartSample)
        put("task_end_sample", taskEndSample)
        put("trial_end_sample", trialEndSample)
        put("ignore_intervals", JSONArray().apply {
            ignoreIntervals.forEach { put(it.toJson()) }
        })
        put("pre_rest_seconds", preRestSeconds.toDouble())
        put("post_rest_seconds", postRestSeconds.toDouble())
        put("alignment_quality", alignmentQuality.toDouble())
    }

    companion object {
        fun fromJson(json: JSONObject) = AsyncTrialAnnotation(
            trialId = json.getInt("trial_id"),
            taskLabel = json.getString("task_label"),
            trialStartSample = json.getInt("trial_start_sample"),
            taskStartSample = json.getInt("task_start_sample"),
            taskEndSample = json.getInt("task_end_sample"),
            trialEndSample = json.getInt("trial_end_sample"),
            ignoreIntervals = json.optJSONArray("ignore_intervals")?.let { intervals ->
                List(intervals.length()) { SampleInterval.fromJson(intervals.getJSONObject(it)) }
            }.orEmpty(),
            preRestSeconds = json.optDouble("pre_rest_seconds", 0.0).toFloat(),
            postRestSeconds = json.optDouble("post_rest_seconds", 0.0).toFloat(),
            alignmentQuality = json.optDouble("alignment_quality", 1.0).toFloat()
        )
    }
}

enum class WorkflowStatus {
    COLLECTING, PACKAGED, UPLOADING, TRAINING, DOWNLOADING, SUCCEEDED, PARTIAL, FAILED
}

data class ModelRun(
    val modelKey: String,
    val preset: PreprocessingPreset,
    val datasetId: String? = null,
    val datasetVersion: Int? = null,
    val preprocessingSha256: String? = null,
    val jobId: String? = null,
    val status: String = "pending",
    val progress: Double = 0.0,
    val accuracy: Double? = null,
    val error: String? = null,
    val modelFile: String? = null,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("model_key", modelKey)
        put("preset", preset.wireName)
        put("dataset_id", datasetId ?: JSONObject.NULL)
        put("dataset_version", datasetVersion ?: JSONObject.NULL)
        put("preprocessing_sha256", preprocessingSha256 ?: JSONObject.NULL)
        put("job_id", jobId ?: JSONObject.NULL)
        put("status", status)
        put("progress", progress)
        put("accuracy", accuracy ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
        put("model_file", modelFile ?: JSONObject.NULL)
        put("updated_at_epoch_ms", updatedAtEpochMs)
    }

    companion object {
        fun fromJson(json: JSONObject) = ModelRun(
            modelKey = json.getString("model_key"),
            preset = PreprocessingPreset.fromPersisted(json.optString("preset")),
            datasetId = json.optNullableString("dataset_id"),
            datasetVersion = json.optInt("dataset_version").takeIf { it > 0 },
            preprocessingSha256 = json.optNullableString("preprocessing_sha256"),
            jobId = json.optNullableString("job_id"),
            status = json.optString("status", "pending"),
            progress = json.optDouble("progress", 0.0),
            accuracy = json.optDouble("accuracy", Double.NaN).takeIf { it.isFinite() },
            error = json.optNullableString("error"),
            modelFile = json.optNullableString("model_file"),
            updatedAtEpochMs = json.optLong("updated_at_epoch_ms", 0L)
        )
    }
}

data class TrainingSession(
    val sessionId: String,
    val localUserId: String,
    /** Profile identities are snapshotted so delayed background work cannot follow a UI user switch. */
    val clientProfileId: String? = null,
    val profileId: String? = null,
    val profileDisplayName: String? = null,
    val collectedAtIso: String,
    val rounds: Int,
    val actionSeconds: Float,
    val trainingEpochs: Int = 20,
    val sampleCount: Int,
    val markers: List<SessionMarker>,
    val asyncTrials: List<AsyncTrialAnnotation> = emptyList(),
    val npzFile: String,
    val protocol: ClassificationProtocol = ClassificationProtocol.FOUR_CLASS,
    val labelNames: List<String> = protocol.labelNames,
    val status: WorkflowStatus = WorkflowStatus.PACKAGED,
    val modelRuns: List<ModelRun>,
    val activeModelKey: String? = null,
    val error: String? = null
) {
    init {
        require(labelNames == protocol.labelNames) { "Session protocol and label order do not match" }
        require(markers.all { it.label in labelNames }) { "Session marker contains a label outside its protocol" }
        require(asyncTrials.all { it.taskLabel in labelNames && it.taskLabel != "rest" }) {
            "Async trial contains an invalid task label"
        }
        require(asyncTrials.zipWithNext().all { (first, second) ->
            first.trialEndSample <= second.trialStartSample
        }) { "Async trials overlap or are out of order" }
    }

    fun toJson() = JSONObject().apply {
        put("session_id", sessionId)
        put("local_user_id", localUserId)
        put("client_profile_id", clientProfileId ?: JSONObject.NULL)
        put("profile_id", profileId ?: JSONObject.NULL)
        put("profile_display_name", profileDisplayName ?: JSONObject.NULL)
        put("collected_at", collectedAtIso)
        put("rounds", rounds)
        put("action_seconds", actionSeconds.toDouble())
        put("training_epochs", trainingEpochs)
        put("sample_count", sampleCount)
        put("markers", JSONArray().apply { markers.forEach { put(it.toJson()) } })
        put("async_trials", JSONArray().apply { asyncTrials.forEach { put(it.toJson()) } })
        put("npz_file", npzFile)
        put("protocol", protocol.wireName)
        put("label_names", JSONArray(labelNames))
        put("status", status.name)
        put("model_runs", JSONArray().apply { modelRuns.forEach { put(it.toJson()) } })
        put("active_model_key", activeModelKey ?: JSONObject.NULL)
        put("error", error ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): TrainingSession {
            val persistedLabels = json.optJSONArray("label_names")?.let { labels ->
                List(labels.length()) { labels.getString(it) }
            }
            val protocol = ClassificationProtocol.fromWireName(json.optString("protocol"))
                ?: persistedLabels?.let(ClassificationProtocol::inferLegacy)
                ?: ClassificationProtocol.FOUR_CLASS
            val labelNames = persistedLabels ?: protocol.labelNames
            require(labelNames == protocol.labelNames) { "Session protocol and label order do not match" }
            return TrainingSession(
                sessionId = json.getString("session_id"),
                localUserId = json.getString("local_user_id"),
                clientProfileId = json.optNullableString("client_profile_id"),
                profileId = json.optNullableString("profile_id"),
                profileDisplayName = json.optNullableString("profile_display_name"),
                collectedAtIso = json.getString("collected_at"),
                rounds = json.getInt("rounds"),
                actionSeconds = json.getDouble("action_seconds").toFloat(),
                trainingEpochs = json.optInt("training_epochs", 20).takeIf { it > 0 } ?: 20,
                sampleCount = json.getInt("sample_count"),
                markers = json.getJSONArray("markers").toObjectList(SessionMarker::fromJson),
                asyncTrials = json.optJSONArray("async_trials")?.let { trials ->
                    List(trials.length()) { AsyncTrialAnnotation.fromJson(trials.getJSONObject(it)) }
                }.orEmpty(),
                npzFile = json.getString("npz_file"),
                protocol = protocol,
                labelNames = labelNames,
                status = runCatching {
                    WorkflowStatus.valueOf(json.getString("status"))
                }.getOrDefault(WorkflowStatus.FAILED),
                modelRuns = json.getJSONArray("model_runs").toObjectList(ModelRun::fromJson),
                activeModelKey = json.optNullableString("active_model_key"),
                error = json.optNullableString("error")
            )
        }
    }
}

internal fun JSONObject.optNullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

internal fun <T> JSONArray.toObjectList(transform: (JSONObject) -> T): List<T> =
    List(length()) { transform(getJSONObject(it)) }

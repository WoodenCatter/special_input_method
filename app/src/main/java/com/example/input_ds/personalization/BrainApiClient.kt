package com.example.input_ds.personalization

import com.example.input_ds.bci.AsyncWindowPolicy
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.math.roundToInt

class BrainApiClient(
    private val serverUserId: String,
    private val apiKey: String,
    private val baseUrl: String = BASE_URL,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS
) {
    data class ServerModel(
        val key: String,
        val name: String,
        val enabled: Boolean,
        /**
         * API v0.3.0 models are class-count agnostic and do not publish a
         * supported_protocols field. An absent field therefore means all
         * protocols understood by this client, not legacy four-class only.
         */
        val supportedProtocols: Set<ClassificationProtocol> = ClassificationProtocol.entries.toSet()
    )
    data class ServerProfile(
        val profileId: String,
        val clientProfileId: String?,
        val displayName: String,
        val enabled: Boolean,
        val isLegacy: Boolean,
        val idempotentReplay: Boolean,
        val datasetVersions: Int,
        val totalTrainingJobs: Int
    )
    data class UploadResult(val datasetId: String, val version: Int, val preprocessingSha256: String?)
    data class StreamingUploadSnapshot(val uploadId: String, val uploadedChunks: Set<Int>)
    data class DatasetVersionRef(val datasetId: String, val version: Int)
    data class ServerDataset(
        val datasetId: String,
        val version: Int,
        val profileId: String,
        val originalFilename: String,
        val collectedAt: String,
        val uploadedAt: String,
        val sampleCount: Int,
        val protocol: ClassificationProtocol,
        val labelNames: List<String>,
        val markers: List<SessionMarker>,
        val asyncTrials: List<AsyncTrialAnnotation>,
        val sessionId: String,
        val rounds: Int,
        val trainingEpochs: Int,
        val windowPoints: Int,
        val preprocessingSha256: String?
    )
    data class ServerTrainingJob(
        val jobId: String,
        val profileId: String,
        val modelKey: String,
        val datasets: List<DatasetVersionRef>,
        val status: String,
        val progress: Double,
        val accuracy: Double?,
        val error: String?,
        val artifactAvailable: Boolean,
        val updatedAt: String?
    )
    data class JobSnapshot(
        val jobId: String,
        val status: String,
        val stage: String,
        val progress: Double,
        val accuracy: Double?,
        val error: String?,
        val updatedAt: String?
    )

    class ApiException(
        val statusCode: Int,
        message: String,
        val errorCode: String? = null,
        val retryAfterSeconds: Long? = null
    ) : Exception(message) {
        val isTransient: Boolean get() = statusCode == 429 || statusCode == 502 || statusCode == 503 || statusCode < 0
        val isStreamingUnsupported: Boolean
            get() = statusCode == 404 || statusCode == 405 || statusCode == 501
    }

    private val tokenCacheKey = UUID.nameUUIDFromBytes(
        "$baseUrl\u0000$serverUserId\u0000$apiKey".toByteArray(StandardCharsets.UTF_8)
    ).toString()
    @Volatile private var accessToken: String? = null

    fun authenticate() {
        accessToken = AUTH_TOKENS.getOrLoad(tokenCacheKey) {
            val response = requestJson(
                method = "POST",
                path = "/api/v1/auth/token",
                body = JSONObject().put("user_id", serverUserId).put("api_key", apiKey),
                authenticated = false
            )
            val expiresIn = response.optLong("expires_in", 900L)
            SharedAuthTokenCache.Token(
                value = response.getString("access_token"),
                expiresAtMs = System.currentTimeMillis() +
                    (expiresIn - 30L).coerceAtLeast(30L) * 1000L
            )
        }
    }

    fun listModels(): List<ServerModel> {
        val result = execute("GET", "/api/v1/models", authenticated = false)
        val array = when {
            result.body.trimStart().startsWith("[") -> JSONArray(result.body)
            else -> JSONObject(result.body).let { it.optJSONArray("models") ?: it.optJSONArray("items") ?: JSONArray() }
        }
        return List(array.length()) { index ->
            val model = array.getJSONObject(index)
            val protocolsJson = model.optJSONArray("supported_protocols") ?: model.optJSONArray("protocols")
            val supportedProtocols = supportedProtocolsFromWireNames(
                protocolsJson?.let { protocols ->
                    (0 until protocols.length()).map(protocols::optString)
                }
            )
            ServerModel(
                key = model.optString("model_key", model.optString("key")),
                name = model.optString("name", model.optString("display_name", model.optString("model_key"))),
                enabled = model.optBoolean("enabled", true),
                supportedProtocols = supportedProtocols
            )
        }.filter { it.key.isNotBlank() && it.enabled }
    }

    /** Idempotently creates or returns the profile belonging to this local phone identity. */
    fun syncProfile(clientProfileId: String, displayName: String): ServerProfile {
        require(clientProfileId.matches(PROFILE_ID_REGEX)) { "Invalid client_profile_id" }
        val body = JSONObject()
            .put("client_profile_id", clientProfileId)
            .put("display_name", displayName.trim())
        return parseProfile(requestJson("POST", "/api/v1/me/profiles", body))
    }

    fun getProfileByClient(clientProfileId: String): ServerProfile? {
        require(clientProfileId.matches(PROFILE_ID_REGEX)) { "Invalid client_profile_id" }
        return try {
            parseProfile(requestJson("GET", "/api/v1/me/profiles/by-client/${encodePath(clientProfileId)}"))
        } catch (error: ApiException) {
            if (error.statusCode == HttpURLConnection.HTTP_NOT_FOUND) null else throw error
        }
    }

    fun listProfiles(): List<ServerProfile> {
        val response = requestArray("GET", "/api/v1/me/profiles")
        return List(response.length()) { parseProfile(response.getJSONObject(it)) }
    }

    fun listDatasets(profileId: String): List<ServerDataset> {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
        val response = requestArray(
            "GET",
            "/api/v1/me/datasets?profile_id=${encodePath(profileId)}"
        )
        return List(response.length()) { parseDataset(response.getJSONObject(it)) }
    }

    fun listTrainingJobs(profileId: String): List<ServerTrainingJob> {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
        val response = requestArray(
            "GET",
            "/api/v1/me/training-jobs?profile_id=${encodePath(profileId)}"
        )
        return List(response.length()) { parseTrainingJob(response.getJSONObject(it)) }
    }

    fun downloadDataset(
        profileId: String,
        datasetId: String,
        version: Int,
        destination: File
    ) {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
        require(datasetId.matches(PROFILE_ID_REGEX)) { "Invalid dataset_id" }
        require(version > 0) { "Invalid dataset version" }
        downloadBinary(
            path = "/api/v1/me/datasets/${encodePath(datasetId)}/file" +
                "?profile_id=${encodePath(profileId)}&version=$version",
            destination = destination,
            emptyMessage = "服务器返回的采集文件为空"
        )
    }

    fun associateLegacyProfile(
        profileId: String,
        clientProfileId: String,
        displayName: String
    ): ServerProfile {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
        require(clientProfileId.matches(PROFILE_ID_REGEX)) { "Invalid client_profile_id" }
        val body = JSONObject()
            .put("client_profile_id", clientProfileId)
            .put("display_name", displayName.trim())
        return parseProfile(
            requestJson(
                "POST",
                "/api/v1/me/profiles/${encodePath(profileId)}/associate-client",
                body
            )
        )
    }

    fun predictInitialSentence(request: InitialSentenceRequest): InitialSentenceResponse {
        require(request.blocksKey.matches(Regex("[2-9]{1,20}"))) { "blocksKey必须是1到20位的2-9数字" }
        require(request.limit in 1..32) { "首字母候选数量必须在1到32之间" }
        val body = JSONObject().apply {
            put("request_id", request.requestId)
            put("blocks_key", request.blocksKey)
            put("current_text", request.currentText)
            put("conversation", request.conversation.toJson())
            put("local_candidates", JSONArray(request.localCandidates))
            put("limit", request.limit)
        }
        val json = requestJson("POST", "/api/v1/input/initial-sentence", body)
        // OpenAPI marks candidates as required. A missing or wrongly typed field is a
        // contract failure, not a successful response with zero predictions.
        val candidatesJson = json.getJSONArray("candidates")
        return InitialSentenceResponse(
            requestId = json.getString("request_id"),
            blocksKey = json.getString("blocks_key"),
            candidates = List(candidatesJson.length()) { index ->
                val candidate = candidatesJson.getJSONObject(index)
                InitialSentenceCandidate(
                    id = candidate.getString("id"),
                    text = candidate.getString("text"),
                    source = candidate.getString("source")
                )
            },
            hasMore = json.optBoolean("has_more", false),
            timings = json.optJSONObject("timings").toPredictionTimings()
        )
    }

    fun predictContextCompletion(request: ContextCompletionRequest): ContextCompletionResponse {
        require(request.currentText.isNotBlank()) { "上下文预测文本不能为空" }
        require(request.limit in 1..20) { "上下文候选数量必须在1到20之间" }
        val body = JSONObject().apply {
            put("request_id", request.requestId)
            put("current_text", request.currentText)
            put("conversation", request.conversation.toJson())
            put("local_candidates", JSONArray(request.localCandidates))
            put("known_entities", JSONArray(request.knownEntities))
            request.clientTime?.let { put("client_time", it) }
            put("limit", request.limit)
        }
        val json = requestJson("POST", "/api/v1/input/context-completion", body)
        // OpenAPI marks candidates as required. Do not silently reinterpret a
        // missing or wrongly typed field as a successful empty prediction.
        val candidatesJson = json.getJSONArray("candidates")
        return ContextCompletionResponse(
            requestId = json.getString("request_id"),
            currentText = json.getString("current_text"),
            candidates = List(candidatesJson.length()) { index ->
                val candidate = candidatesJson.getJSONObject(index)
                ContextCompletionCandidate(
                    id = candidate.getString("id"),
                    fullText = candidate.getString("full_text"),
                    appendText = candidate.getString("append_text"),
                    source = candidate.optString("source", "llm")
                )
            },
            timings = json.optJSONObject("timings").toPredictionTimings()
        )
    }

    fun uploadContinuousDataset(
        session: TrainingSession,
        npzFile: File
    ): UploadResult {
        require(npzFile.isFile && npzFile.length() > 0) { "连续 NPZ 文件不存在或为空" }
        ensureToken()
        var refreshed = false
        while (true) {
            val response = uploadMultipart(
                file = npzFile,
                metadata = buildDatasetMetadata(session),
                profileId = requireNotNull(session.profileId) { "Session has no server profile_id" }
            )
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                refreshTokenAfterUnauthorized()
                refreshed = true
                continue
            }
            checkResponse(response)
            val json = JSONObject(response.body)
            return UploadResult(
                datasetId = json.getString("dataset_id"),
                version = json.getInt("version"),
                preprocessingSha256 = json.optNullableString("inference_preprocessing_sha256")
            )
        }
    }

    /** Uploads every locally sealed chunk. It is safe to call repeatedly after process death. */
    internal fun uploadAvailableStreamingChunks(store: StreamingUploadStore) {
        var manifest = requireNotNull(store.load()) { "本地流式上传清单不存在" }
        if (manifest.unsupported || manifest.finalized) return
        val profileId = requireNotNull(manifest.profileId) { "流式上传缺少 profile_id" }
        val snapshot = if (manifest.uploadId == null) {
            createStreamingUpload(manifest, profileId)
        } else {
            getStreamingUpload(manifest.uploadId)
        }
        store.setRemote(snapshot.uploadId, snapshot.uploadedChunks)
        manifest = requireNotNull(store.load())
        for (chunk in manifest.chunks.filterNot { it.uploaded }.sortedBy { it.index }) {
            val file = store.chunkFile(chunk)
            require(file.isFile && file.length() > 0) { "流式分块 ${chunk.index} 丢失" }
            uploadStreamingChunk(snapshot.uploadId, chunk, file)
            store.markUploaded(chunk.index)
        }
    }

    /** Finalizes the already uploaded stream and returns a normal trainable dataset version. */
    internal fun finalizeStreamingDataset(
        session: TrainingSession,
        store: StreamingUploadStore
    ): UploadResult {
        store.finalizedResult()?.let { return it }
        val profileId = requireNotNull(session.profileId) { "Session has no server profile_id" }
        store.setProfileId(profileId)
        uploadAvailableStreamingChunks(store)
        val manifest = requireNotNull(store.load())
        require(manifest.chunks.isNotEmpty()) { "没有可提交的流式分块" }
        require(manifest.chunks.all { it.uploaded }) { "仍有流式分块未上传" }
        require(manifest.chunks.sumOf { it.sampleCount.toLong() } == session.sampleCount.toLong()) {
            "流式分块总点数与 Session 不一致"
        }
        val uploadId = requireNotNull(manifest.uploadId)
        val body = JSONObject().apply {
            put("profile_id", profileId)
            put("client_session_id", session.sessionId)
            put("total_sample_count", session.sampleCount)
            put("metadata", buildDatasetMetadata(session))
            put("chunks", JSONArray().apply {
                manifest.chunks.sortedBy { it.index }.forEach { chunk ->
                    put(JSONObject().apply {
                        put("index", chunk.index)
                        put("start_sample", chunk.startSample)
                        put("sample_count", chunk.sampleCount)
                        put("sha256", chunk.sha256)
                    })
                }
            })
        }
        val json = requestJson(
            "POST",
            "/api/v1/me/streaming-datasets/${encodePath(uploadId)}/finalize",
            body
        )
        return UploadResult(
            datasetId = json.getString("dataset_id"),
            version = json.getInt("version"),
            preprocessingSha256 = json.optNullableString("inference_preprocessing_sha256")
        ).also(store::markFinalized)
    }

    private fun createStreamingUpload(
        manifest: StreamingUploadManifest,
        profileId: String
    ): StreamingUploadSnapshot {
        val body = JSONObject().apply {
            put("profile_id", profileId)
            put("client_session_id", manifest.sessionId)
            put("protocol_version", StreamingUploadStore.STREAM_PROTOCOL_VERSION)
            put("chunk_format_version", StreamingUploadStore.CHUNK_FORMAT_VERSION)
            put("chunk_points", manifest.chunkPoints)
            put("sampling_rate_hz", CollectionSettings.SAMPLE_RATE_HZ)
            put("channels", JSONArray(listOf("left_ear", "right_ear")))
            put("value_unit", "uV")
            put("classification_protocol", manifest.protocol.serverWireName)
            put("label_names", JSONArray(manifest.labelNames))
            put(
                "inference_preprocessing",
                PreprocessingPreset.UNIFIED_1_45.contract(
                    (manifest.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()
                )
            )
        }
        return parseStreamingSnapshot(requestJson("POST", "/api/v1/me/streaming-datasets", body))
    }

    private fun getStreamingUpload(uploadId: String): StreamingUploadSnapshot =
        parseStreamingSnapshot(
            requestJson("GET", "/api/v1/me/streaming-datasets/${encodePath(uploadId)}")
        )

    private fun parseStreamingSnapshot(json: JSONObject): StreamingUploadSnapshot {
        val uploaded = json.optJSONArray("uploaded_chunks") ?: JSONArray()
        return StreamingUploadSnapshot(
            uploadId = json.getString("upload_id"),
            uploadedChunks = buildSet {
                for (index in 0 until uploaded.length()) {
                    when (val item = uploaded.opt(index)) {
                        is Number -> add(item.toInt())
                        is JSONObject -> add(item.getInt("index"))
                    }
                }
            }
        )
    }

    private fun uploadStreamingChunk(uploadId: String, chunk: StreamingChunk, file: File) {
        ensureToken()
        var refreshed = false
        while (true) {
            val connection = openConnection(
                "PUT",
                "/api/v1/me/streaming-datasets/${encodePath(uploadId)}/chunks/${chunk.index}",
                authenticated = true
            ).apply {
                doOutput = true
                setRequestProperty("Content-Type", "application/vnd.inputds.eeg-chunk")
                setRequestProperty("X-Chunk-SHA256", chunk.sha256)
                setRequestProperty("X-Start-Sample", chunk.startSample.toString())
                setRequestProperty("X-Sample-Count", chunk.sampleCount.toString())
                setFixedLengthStreamingMode(file.length())
            }
            val response = try {
                BufferedOutputStream(connection.outputStream).use { output ->
                    file.inputStream().use { it.copyTo(output) }
                }
                Response(connection.responseCode, readBody(connection), connection.getHeaderField("Retry-After"))
            } catch (error: Exception) {
                connection.disconnect()
                throw ApiException(-1, error.message ?: "流式分块上传失败")
            } finally {
                connection.disconnect()
            }
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                refreshTokenAfterUnauthorized()
                refreshed = true
                continue
            }
            checkResponse(response)
            return
        }
    }

    fun createTrainingJob(session: TrainingSession, run: ModelRun): JobSnapshot {
        val datasetId = requireNotNull(run.datasetId)
        val version = requireNotNull(run.datasetVersion)
        val body = JSONObject().apply {
            put("profile_id", requireNotNull(session.profileId) { "Session has no server profile_id" })
            put("model_key", run.modelKey)
            put("datasets", JSONArray().put(JSONObject().put("dataset_id", datasetId).put("version", version)))
            put("validation_split", if (session.asyncTrials.isEmpty()) 0.25 else 0.15)
            put("hyperparameters", JSONObject().apply {
                put("label_names", JSONArray(session.labelNames))
                put("epochs", session.trainingEpochs)
                put("batch_size", 8)
                put("seed", 42)
                put("device", "auto")
            })
        }
        return parseJob(requestJson("POST", "/api/v1/me/training-jobs", body))
    }

    /** Recovers a job when the create response was lost, avoiding duplicate training. */
    fun findTrainingJob(session: TrainingSession, run: ModelRun): JobSnapshot? {
        val datasetId = run.datasetId ?: return null
        val version = run.datasetVersion ?: return null
        val profileId = session.profileId ?: return null
        val response = requestArray("GET", "/api/v1/me/training-jobs?profile_id=${encodePath(profileId)}")
        for (index in response.length() - 1 downTo 0) {
            val json = response.optJSONObject(index) ?: continue
            val request = json.optJSONObject("request") ?: continue
            if (request.optString("profile_id") != profileId) continue
            if (request.optString("model_key") != run.modelKey) continue
            val requestedProtocol = request.optString("protocol")
            if (
                requestedProtocol.isNotBlank() &&
                requestedProtocol !in setOf(session.protocol.wireName, session.protocol.serverWireName)
            ) continue
            val requestedEpochs = request.optJSONObject("hyperparameters")
                ?.optInt("epochs", 20) ?: 20
            if (requestedEpochs != session.trainingEpochs) continue
            val requestedLabels = request.optJSONObject("hyperparameters")
                ?.optJSONArray("label_names")
                ?.let { labels -> List(labels.length()) { labels.getString(it) } }
            if (requestedLabels != null && requestedLabels != session.labelNames) continue
            val datasets = request.optJSONArray("datasets") ?: continue
            val matches = (0 until datasets.length()).any { datasetIndex ->
                datasets.optJSONObject(datasetIndex)?.let {
                    it.optString("dataset_id") == datasetId && it.optInt("version") == version
                } == true
            }
            if (matches) return parseJob(json)
        }
        return null
    }

    fun getTrainingJob(jobId: String): JobSnapshot =
        parseJob(requestJson("GET", "/api/v1/me/training-jobs/$jobId"))

    fun downloadModel(jobId: String, destination: File) {
        downloadBinary(
            path = "/api/v1/me/training-jobs/$jobId/model",
            destination = destination,
            emptyMessage = "服务器返回的 ONNX 文件为空"
        )
    }

    private fun downloadBinary(path: String, destination: File, emptyMessage: String) {
        ensureToken()
        var refreshed = false
        while (true) {
            val connection = openConnection("GET", path, authenticated = true)
            val code = runCatching { connection.responseCode }.getOrElse {
                connection.disconnect()
                throw ApiException(-1, it.message ?: "网络连接失败")
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                connection.disconnect()
                refreshTokenAfterUnauthorized()
                refreshed = true
                continue
            }
            if (code !in 200..299) {
                val response = Response(code, readBody(connection), connection.getHeaderField("Retry-After"))
                connection.disconnect()
                checkResponse(response)
            }
            destination.parentFile?.mkdirs()
            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(destination.outputStream()).use { output -> input.copyTo(output) }
            }
            connection.disconnect()
            require(destination.length() > 0) { emptyMessage }
            return
        }
    }

    private fun buildDatasetMetadata(session: TrainingSession): JSONObject = JSONObject().apply {
        val windowPoints = (session.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()
        put("collected_at", session.collectedAtIso)
        put("data_mode", "continuous_marked")
        put("data_format", "npz")
        put("input", JSONObject().apply {
            put("adapter", "numpy-npz")
            put("organization", "continuous")
            put("layout", "separate_arrays")
            put("left_key", "left")
            put("right_key", "right")
        })
        put("sampling_rate_hz", CollectionSettings.SAMPLE_RATE_HZ)
        put("classification_protocol", session.protocol.serverWireName)
        put("label_names", JSONArray(session.labelNames))
        put("channels", JSONArray(listOf("left_ear", "right_ear")))
        put("channel_order", JSONArray(listOf("left_ear", "right_ear")))
        put("value_unit", "uV")
        put("sample_count", session.sampleCount)
        put("target_points", windowPoints)
        put("markers", JSONArray().apply {
            session.markers.forEach { marker ->
                put(marker.toJson().apply {
                    if (markerRoundForUpload(marker.round) == null) remove("round")
                })
            }
        })
        if (session.asyncTrials.isNotEmpty()) {
            put("async_trials", JSONArray().apply { session.asyncTrials.forEach { put(it.toJson()) } })
            put("async_windowing", JSONObject().apply {
                put("version", "2")
                put("window_points", windowPoints)
                put("stride_points", AsyncWindowPolicy.stridePoints(windowPoints))
                put("task_overlap_samples", AsyncWindowPolicy.taskOverlapPoints(windowPoints))
                put("evidence_windows", AsyncWindowPolicy.EVIDENCE_WINDOWS)
                put("confidence_threshold", AsyncWindowPolicy.CONFIDENCE_THRESHOLD.toDouble())
                put("support_required", AsyncWindowPolicy.SUPPORT_REQUIRED)
                put("evidence_mode", "support_confidence")
                put("physical_support_required", AsyncWindowPolicy.PHYSICAL_SUPPORT_REQUIRED)
                put("rest_reset_required", AsyncWindowPolicy.REST_RESET_REQUIRED)
            })
        }
        put(
            "inference_preprocessing",
            PreprocessingPreset.UNIFIED_1_45.contract(
                windowPoints
            )
        )
        put("training_windows", JSONObject().apply {
            put("start_offset_samples", 0)
            put("augmentation_count", 1)
            put("augmentation_max_offset_samples", 0)
        })
        put("device", "android-ear-eeg-v1")
        put("parameters", JSONObject().apply {
            put("protocol_version", if (session.asyncTrials.isEmpty()) "input-adapter-v2" else "async-trial-v1")
            put("classification_protocol", session.protocol.serverWireName)
            put("session_id", session.sessionId)
            put("rounds", session.rounds)
            put("training_epochs", session.trainingEpochs)
        })
    }

    private fun parseJob(json: JSONObject): JobSnapshot {
        val metrics = json.optJSONObject("metrics")
        return JobSnapshot(
            jobId = json.getString("job_id"),
            status = json.optString("status", "queued"),
            stage = json.optString("stage", "queued"),
            progress = json.optDouble("progress", 0.0),
            accuracy = metrics?.optDouble("accuracy", Double.NaN)?.takeIf { it.isFinite() },
            error = json.optNullableString("error"),
            updatedAt = json.optNullableString("updated_at")
        )
    }

    private fun parseDataset(json: JSONObject): ServerDataset {
        val labelsJson = json.optJSONArray("label_names") ?: JSONArray()
        val labels = List(labelsJson.length()) { labelsJson.getString(it) }
        val protocol = ClassificationProtocol.fromWireName(
            json.optString("classification_protocol")
        ) ?: ClassificationProtocol.inferLegacy(labels)
            ?: error("服务器数据缺少可识别的分类协议")
        require(labels == protocol.labelNames) { "服务器数据标签顺序与分类协议不一致" }
        val markersJson = json.optJSONArray("markers") ?: JSONArray()
        val trialsJson = json.optJSONArray("async_trials") ?: JSONArray()
        val parameters = json.optJSONObject("parameters") ?: JSONObject()
        val windowPoints = json.optJSONObject("inference_preprocessing")
            ?.optInt("window_points", 0)
            ?.takeIf { it > 0 }
            ?: json.optInt("target_points", 0).takeIf { it > 0 }
            ?: CollectionSettings.SAMPLE_RATE_HZ
        return ServerDataset(
            datasetId = json.getString("dataset_id"),
            version = json.getInt("version"),
            profileId = json.getString("profile_id"),
            originalFilename = json.optString("original_filename", "recording.npz"),
            collectedAt = json.getString("collected_at"),
            uploadedAt = json.getString("uploaded_at"),
            sampleCount = json.optInt("sample_count", windowPoints).coerceAtLeast(1),
            protocol = protocol,
            labelNames = labels,
            markers = List(markersJson.length()) {
                SessionMarker.fromJson(markersJson.getJSONObject(it))
            },
            asyncTrials = List(trialsJson.length()) {
                AsyncTrialAnnotation.fromJson(trialsJson.getJSONObject(it))
            },
            sessionId = parameters.optString("session_id")
                .takeIf { it.isNotBlank() }
                ?: json.getString("dataset_id"),
            rounds = parameters.optInt("rounds", 1).coerceAtLeast(1),
            trainingEpochs = parameters.optInt("training_epochs", 20).coerceAtLeast(1),
            windowPoints = windowPoints,
            preprocessingSha256 = json.optNullableString("inference_preprocessing_sha256")
        )
    }

    private fun parseTrainingJob(json: JSONObject): ServerTrainingJob {
        val request = json.getJSONObject("request")
        val refs = request.getJSONArray("datasets")
        val metrics = json.optJSONObject("metrics")
        return ServerTrainingJob(
            jobId = json.getString("job_id"),
            profileId = json.getString("profile_id"),
            modelKey = request.getString("model_key"),
            datasets = List(refs.length()) { index ->
                refs.getJSONObject(index).let {
                    DatasetVersionRef(it.getString("dataset_id"), it.getInt("version"))
                }
            },
            status = json.optString("status", "queued"),
            progress = json.optDouble("progress", 0.0),
            accuracy = metrics?.optDouble("accuracy", Double.NaN)?.takeIf { it.isFinite() },
            error = json.optNullableString("error"),
            artifactAvailable = json.optNullableString("artifact_filename") != null,
            updatedAt = json.optNullableString("updated_at")
        )
    }

    private fun parseProfile(json: JSONObject) = ServerProfile(
        profileId = json.getString("profile_id"),
        clientProfileId = json.optNullableString("client_profile_id"),
        displayName = json.getString("display_name"),
        enabled = json.optBoolean("enabled", true),
        isLegacy = json.optBoolean("is_legacy", false),
        idempotentReplay = json.optBoolean("idempotent_replay", false),
        datasetVersions = json.optInt("dataset_versions", 0),
        totalTrainingJobs = json.optInt("total_training_jobs", 0)
    )

    private fun requestJson(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true
    ): JSONObject {
        if (authenticated) ensureToken()
        var refreshed = false
        while (true) {
            val response = execute(method, path, body?.toString()?.toByteArray(StandardCharsets.UTF_8), authenticated)
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && authenticated && !refreshed) {
                refreshTokenAfterUnauthorized()
                refreshed = true
                continue
            }
            checkResponse(response)
            return if (response.body.isBlank()) JSONObject() else JSONObject(response.body)
        }
    }

    private fun requestArray(method: String, path: String): JSONArray {
        ensureToken()
        var refreshed = false
        while (true) {
            val response = execute(method, path, authenticated = true)
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                refreshTokenAfterUnauthorized()
                refreshed = true
                continue
            }
            checkResponse(response)
            return JSONArray(response.body)
        }
    }

    private fun ensureToken() {
        authenticate()
    }

    private fun refreshTokenAfterUnauthorized() {
        val rejectedToken = accessToken
        AUTH_TOKENS.invalidate(tokenCacheKey, rejectedToken)
        accessToken = null
        authenticate()
    }

    private fun execute(
        method: String,
        path: String,
        body: ByteArray? = null,
        authenticated: Boolean = true
    ): Response {
        val connection = openConnection(method, path, authenticated)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
        }
        return try {
            val code = connection.responseCode
            Response(code, readBody(connection), connection.getHeaderField("Retry-After"))
        } catch (error: Exception) {
            throw ApiException(-1, error.message ?: "网络连接失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun uploadMultipart(file: File, metadata: JSONObject, profileId: String): Response {
        require(profileId.matches(PROFILE_ID_REGEX)) { "Invalid profile_id" }
        val boundary = "----InputDs${UUID.randomUUID()}"
        val connection = openConnection("POST", "/api/v1/me/datasets", authenticated = true).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(64 * 1024)
        }
        return try {
            BufferedOutputStream(connection.outputStream).use { output ->
                output.writeUtf8("--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"profile_id\"\r\n\r\n")
                output.writeUtf8(profileId)
                output.writeUtf8("\r\n--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"metadata\"\r\n")
                output.writeUtf8("Content-Type: application/json; charset=utf-8\r\n\r\n")
                output.write(metadata.toString().toByteArray(StandardCharsets.UTF_8))
                output.writeUtf8("\r\n--$boundary\r\n")
                output.writeUtf8("Content-Disposition: form-data; name=\"file\"; filename=\"continuous.npz\"\r\n")
                output.writeUtf8("Content-Type: application/octet-stream\r\n\r\n")
                file.inputStream().use { it.copyTo(output) }
                output.writeUtf8("\r\n--$boundary--\r\n")
            }
            Response(connection.responseCode, readBody(connection), connection.getHeaderField("Retry-After"))
        } catch (error: Exception) {
            throw ApiException(-1, error.message ?: "上传连接失败")
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(method: String, path: String, authenticated: Boolean): HttpURLConnection =
        (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            useCaches = false
            setRequestProperty("Accept", "application/json, application/octet-stream")
            setRequestProperty("User-Agent", "InputDS-Android/1.0")
            if (authenticated) setRequestProperty("Authorization", "Bearer ${requireNotNull(accessToken)}")
        }

    private fun checkResponse(response: Response) {
        if (response.code in 200..299) return
        var errorCode: String? = null
        val detail = runCatching {
            val json = JSONObject(response.body)
            when (val value = json.opt("detail")) {
                is String -> value
                null -> response.body
                is JSONObject -> {
                    errorCode = value.optString("code").takeIf(String::isNotBlank)
                    value.optString("message").ifBlank { value.toString() }
                }
                else -> value.toString()
            }
        }.getOrDefault(response.body).ifBlank { "HTTP ${response.code}" }
        throw ApiException(response.code, detail, errorCode, response.retryAfter?.toLongOrNull())
    }

    private fun readBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun BufferedOutputStream.writeUtf8(value: String) {
        write(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun List<ConversationTurn>.toJson(): JSONArray = JSONArray().also { array ->
        forEach { turn ->
            array.put(JSONObject().put("role", turn.role).put("text", turn.text))
        }
    }

    private fun JSONObject?.toPredictionTimings(): PredictionTimings = PredictionTimings(
        indexMs = this?.optDouble("index_ms", 0.0) ?: 0.0,
        queueMs = this?.optDouble("queue_ms", 0.0) ?: 0.0,
        llmMs = this?.optDouble("llm_ms", 0.0) ?: 0.0,
        validateMs = this?.optDouble("validate_ms", 0.0) ?: 0.0,
        postprocessMs = this?.optDouble("postprocess_ms", 0.0) ?: 0.0,
        totalMs = this?.optDouble("total_ms", 0.0) ?: 0.0
    )

    private data class Response(val code: Int, val body: String, val retryAfter: String?)

    companion object {
        private val AUTH_TOKENS = SharedAuthTokenCache()

        internal fun supportedProtocolsFromWireNames(
            wireNames: List<String>?
        ): Set<ClassificationProtocol> = wireNames?.mapNotNull(
            ClassificationProtocol::fromWireName
        )?.toSet() ?: ClassificationProtocol.entries.toSet()

        const val BASE_URL = "https://smu-brain.online"
        private val PROFILE_ID_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 120_000

        private fun encodePath(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    }
}

/** Legacy server records used zero as "round not recorded"; the current API represents it by omission. */
internal fun markerRoundForUpload(round: Int): Int? = round.takeIf { it >= 1 }

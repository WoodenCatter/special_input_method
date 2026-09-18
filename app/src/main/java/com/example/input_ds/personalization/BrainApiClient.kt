package com.example.input_ds.personalization

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
    }

    @Volatile private var accessToken: String? = null
    @Volatile private var tokenExpiresAtMs: Long = 0

    fun authenticate() {
        val response = requestJson(
            method = "POST",
            path = "/api/v1/auth/token",
            body = JSONObject().put("user_id", serverUserId).put("api_key", apiKey),
            authenticated = false
        )
        accessToken = response.getString("access_token")
        val expiresIn = response.optLong("expires_in", 900L)
        tokenExpiresAtMs = System.currentTimeMillis() + (expiresIn - 30L).coerceAtLeast(30L) * 1000L
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
        run: ModelRun,
        npzFile: File
    ): UploadResult {
        require(npzFile.isFile && npzFile.length() > 0) { "连续 NPZ 文件不存在或为空" }
        ensureToken()
        var refreshed = false
        while (true) {
            val response = uploadMultipart(
                file = npzFile,
                metadata = buildDatasetMetadata(session, run),
                profileId = requireNotNull(session.profileId) { "Session has no server profile_id" }
            )
            if (response.code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                accessToken = null
                authenticate()
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

    fun createTrainingJob(session: TrainingSession, run: ModelRun): JobSnapshot {
        val datasetId = requireNotNull(run.datasetId)
        val version = requireNotNull(run.datasetVersion)
        val body = JSONObject().apply {
            put("profile_id", requireNotNull(session.profileId) { "Session has no server profile_id" })
            put("model_key", run.modelKey)
            put("datasets", JSONArray().put(JSONObject().put("dataset_id", datasetId).put("version", version)))
            put("validation_split", 0.25)
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
            if (requestedProtocol.isNotBlank() && requestedProtocol != session.protocol.wireName) continue
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
        ensureToken()
        var refreshed = false
        while (true) {
            val connection = openConnection("GET", "/api/v1/me/training-jobs/$jobId/model", authenticated = true)
            val code = runCatching { connection.responseCode }.getOrElse {
                connection.disconnect()
                throw ApiException(-1, it.message ?: "网络连接失败")
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED && !refreshed) {
                connection.disconnect()
                accessToken = null
                authenticate()
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
            require(destination.length() > 0) { "服务器返回的 ONNX 文件为空" }
            return
        }
    }

    private fun buildDatasetMetadata(session: TrainingSession, run: ModelRun): JSONObject = JSONObject().apply {
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
        put("channels", JSONArray(listOf("left_ear", "right_ear")))
        put("channel_order", JSONArray(listOf("left_ear", "right_ear")))
        put("value_unit", "uV")
        put("sample_count", session.sampleCount)
        put("target_points", (session.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt())
        put("markers", JSONArray().apply { session.markers.forEach { put(it.toJson()) } })
        put("inference_preprocessing", run.preset.contract((session.actionSeconds * CollectionSettings.SAMPLE_RATE_HZ).roundToInt()))
        put("training_windows", JSONObject().apply {
            put("start_offset_samples", 0)
            put("augmentation_count", 1)
            put("augmentation_max_offset_samples", 0)
        })
        put("device", "android-ear-eeg-v1")
        put("parameters", JSONObject().apply {
            put("protocol_version", "input-adapter-v2")
            put("classification_protocol", session.protocol.wireName)
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
                accessToken = null
                authenticate()
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
                accessToken = null
                authenticate()
                refreshed = true
                continue
            }
            checkResponse(response)
            return JSONArray(response.body)
        }
    }

    private fun ensureToken() {
        if (accessToken == null || System.currentTimeMillis() >= tokenExpiresAtMs) authenticate()
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

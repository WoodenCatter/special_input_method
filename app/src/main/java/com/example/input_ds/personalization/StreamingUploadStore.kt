package com.example.input_ds.personalization

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

internal data class StreamingChunk(
    val index: Int,
    val startSample: Long,
    val sampleCount: Int,
    val fileName: String,
    val sha256: String,
    val uploaded: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("index", index)
        put("start_sample", startSample)
        put("sample_count", sampleCount)
        put("file_name", fileName)
        put("sha256", sha256)
        put("uploaded", uploaded)
    }

    companion object {
        fun fromJson(json: JSONObject) = StreamingChunk(
            index = json.getInt("index"),
            startSample = json.getLong("start_sample"),
            sampleCount = json.getInt("sample_count"),
            fileName = json.getString("file_name"),
            sha256 = json.getString("sha256"),
            uploaded = json.optBoolean("uploaded", false)
        )
    }
}

internal data class StreamingUploadManifest(
    val sessionId: String,
    val localUserId: String,
    val profileId: String?,
    val protocol: ClassificationProtocol,
    val labelNames: List<String>,
    val rounds: Int,
    val actionSeconds: Float,
    val trainingEpochs: Int,
    val chunkPoints: Int,
    val uploadId: String? = null,
    val unsupported: Boolean = false,
    val finalized: Boolean = false,
    val datasetId: String? = null,
    val datasetVersion: Int? = null,
    val preprocessingSha256: String? = null,
    val lastError: String? = null,
    val chunks: List<StreamingChunk> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("protocol_version", StreamingUploadStore.STREAM_PROTOCOL_VERSION)
        put("session_id", sessionId)
        put("local_user_id", localUserId)
        put("profile_id", profileId ?: JSONObject.NULL)
        put("classification_protocol", protocol.wireName)
        put("label_names", JSONArray(labelNames))
        put("rounds", rounds)
        put("action_seconds", actionSeconds.toDouble())
        put("training_epochs", trainingEpochs)
        put("sample_rate_hz", CollectionSettings.SAMPLE_RATE_HZ)
        put("chunk_points", chunkPoints)
        put("upload_id", uploadId ?: JSONObject.NULL)
        put("unsupported", unsupported)
        put("finalized", finalized)
        put("dataset_id", datasetId ?: JSONObject.NULL)
        put("dataset_version", datasetVersion ?: JSONObject.NULL)
        put("preprocessing_sha256", preprocessingSha256 ?: JSONObject.NULL)
        put("last_error", lastError ?: JSONObject.NULL)
        put("chunks", JSONArray().apply { chunks.forEach { put(it.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): StreamingUploadManifest {
            require(json.optString("protocol_version") == StreamingUploadStore.STREAM_PROTOCOL_VERSION) {
                "不支持的流式上传清单版本"
            }
            val protocol = ClassificationProtocol.fromWireName(
                json.getString("classification_protocol")
            ) ?: error("未知采集协议")
            val labels = json.getJSONArray("label_names").let { array ->
                List(array.length()) { array.getString(it) }
            }
            require(labels == protocol.labelNames) { "流式上传标签顺序错误" }
            return StreamingUploadManifest(
                sessionId = json.getString("session_id"),
                localUserId = json.getString("local_user_id"),
                profileId = json.optNullableString("profile_id"),
                protocol = protocol,
                labelNames = labels,
                rounds = json.getInt("rounds"),
                actionSeconds = json.getDouble("action_seconds").toFloat(),
                trainingEpochs = json.getInt("training_epochs"),
                chunkPoints = json.getInt("chunk_points"),
                uploadId = json.optNullableString("upload_id"),
                unsupported = json.optBoolean("unsupported", false),
                finalized = json.optBoolean("finalized", false),
                datasetId = json.optNullableString("dataset_id"),
                datasetVersion = json.optInt("dataset_version").takeIf { it > 0 },
                preprocessingSha256 = json.optNullableString("preprocessing_sha256"),
                lastError = json.optNullableString("last_error"),
                chunks = json.getJSONArray("chunks").let { chunks ->
                    List(chunks.length()) { StreamingChunk.fromJson(chunks.getJSONObject(it)) }
                }
            )
        }
    }
}

/** Durable local ledger for five-second, two-ear upload chunks. */
internal class StreamingUploadStore(private val sessionDirectory: File) {
    private val chunkDirectory = File(sessionDirectory, CHUNK_DIRECTORY)
    private val manifestFile = File(sessionDirectory, MANIFEST_FILE)

    @Synchronized
    fun initialize(
        sessionId: String,
        localUserId: String,
        profileId: String?,
        settings: CollectionSettings
    ): StreamingUploadManifest {
        load()?.let { return it }
        chunkDirectory.mkdirs()
        return StreamingUploadManifest(
            sessionId = sessionId,
            localUserId = localUserId,
            profileId = profileId,
            protocol = settings.protocol,
            labelNames = settings.labels,
            rounds = settings.rounds,
            actionSeconds = settings.actionSeconds,
            trainingEpochs = settings.trainingEpochs,
            chunkPoints = CHUNK_POINTS
        ).also(::save)
    }

    @Synchronized
    fun load(): StreamingUploadManifest? =
        if (!manifestFile.isFile) null else StreamingUploadManifest.fromJson(
            JSONObject(manifestFile.readText(Charsets.UTF_8))
        )

    @Synchronized
    fun sealChunk(
        index: Int,
        startSample: Long,
        left: FloatArray,
        right: FloatArray,
        sampleCount: Int
    ): StreamingChunk {
        require(sampleCount in 1..minOf(left.size, right.size))
        val current = requireNotNull(load()) { "流式上传清单尚未初始化" }
        current.chunks.firstOrNull { it.index == index }?.let { return it }
        require(index == current.chunks.size) { "流式分块序号不连续" }
        require(startSample == current.chunks.sumOf { it.sampleCount.toLong() }) {
            "流式分块采样位置不连续"
        }
        chunkDirectory.mkdirs()
        val fileName = "chunk-${index.toString().padStart(6, '0')}.eegchunk"
        val target = File(chunkDirectory, fileName)
        val temporary = File(chunkDirectory, "$fileName.tmp")
        StreamingChunkCodec.write(temporary, index, startSample, left, right, sampleCount)
        check(temporary.renameTo(target)) { "无法完成流式分块原子写入" }
        val chunk = StreamingChunk(
            index = index,
            startSample = startSample,
            sampleCount = sampleCount,
            fileName = "$CHUNK_DIRECTORY/$fileName",
            sha256 = sha256(target)
        )
        save(current.copy(chunks = current.chunks + chunk, lastError = null))
        return chunk
    }

    fun chunkFile(chunk: StreamingChunk): File = File(sessionDirectory, chunk.fileName)

    @Synchronized
    fun setProfileId(profileId: String) = update { it.copy(profileId = profileId) }

    @Synchronized
    fun setRemote(uploadId: String, uploadedIndices: Set<Int>) = update { current ->
        current.copy(
            uploadId = uploadId,
            lastError = null,
            chunks = current.chunks.map { it.copy(uploaded = it.index in uploadedIndices) }
        )
    }

    @Synchronized
    fun markUploaded(index: Int) = update { current ->
        current.copy(
            lastError = null,
            chunks = current.chunks.map { if (it.index == index) it.copy(uploaded = true) else it }
        )
    }

    @Synchronized
    fun markUnsupported(message: String) = update {
        it.copy(unsupported = true, lastError = message)
    }

    @Synchronized
    fun markError(message: String) = update { it.copy(lastError = message) }

    @Synchronized
    fun markFinalized(result: BrainApiClient.UploadResult) = update {
        it.copy(
            finalized = true,
            datasetId = result.datasetId,
            datasetVersion = result.version,
            preprocessingSha256 = result.preprocessingSha256,
            lastError = null
        )
    }

    @Synchronized
    fun finalizedResult(): BrainApiClient.UploadResult? = load()?.let { manifest ->
        val datasetId = manifest.datasetId ?: return@let null
        val version = manifest.datasetVersion ?: return@let null
        BrainApiClient.UploadResult(datasetId, version, manifest.preprocessingSha256)
    }

    @Synchronized
    fun deleteChunks() {
        chunkDirectory.deleteRecursively()
        load()?.let { save(it.copy(chunks = emptyList(), finalized = true)) }
    }

    @Synchronized
    private fun update(transform: (StreamingUploadManifest) -> StreamingUploadManifest) {
        load()?.let { save(transform(it)) }
    }

    private fun save(manifest: StreamingUploadManifest) {
        manifestFile.parentFile?.mkdirs()
        val temporary = File(manifestFile.parentFile, "${manifestFile.name}.tmp")
        temporary.writeText(manifest.toJson().toString(2), Charsets.UTF_8)
        if (!temporary.renameTo(manifestFile)) {
            manifestFile.writeText(temporary.readText(Charsets.UTF_8), Charsets.UTF_8)
            temporary.delete()
        }
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

    companion object {
        const val CHUNK_POINTS = CollectionSettings.SAMPLE_RATE_HZ * 5
        const val STREAM_PROTOCOL_VERSION = "inputds-eeg-stream-v1"
        const val CHUNK_FORMAT_VERSION = 1
        const val CHANNEL_COUNT = 2
        val CHUNK_MAGIC = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte())
        private const val CHUNK_DIRECTORY = "stream_chunks"
        private const val MANIFEST_FILE = "stream_upload.json"
    }
}

internal object StreamingChunkCodec {
    fun write(
        file: File,
        index: Int,
        startSample: Long,
        left: FloatArray,
        right: FloatArray,
        sampleCount: Int
    ) {
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0)
            output.write(StreamingUploadStore.CHUNK_MAGIC)
            output.writeIntLe(StreamingUploadStore.CHUNK_FORMAT_VERSION)
            output.writeIntLe(index)
            output.writeLongLe(startSample)
            output.writeIntLe(sampleCount)
            output.writeIntLe(StreamingUploadStore.CHANNEL_COUNT)
            repeat(sampleCount) { output.writeIntLe(left[it].toRawBits()) }
            repeat(sampleCount) { output.writeIntLe(right[it].toRawBits()) }
        }
    }

    private fun RandomAccessFile.writeIntLe(value: Int) = writeInt(Integer.reverseBytes(value))

    private fun RandomAccessFile.writeLongLe(value: Long) = writeLong(java.lang.Long.reverseBytes(value))
}

/** Splits the same continuous sample clock used by NPZ into durable chunks. */
internal class StreamingChunkAccumulator(
    private val store: StreamingUploadStore,
    private val onChunkReady: (StreamingChunk) -> Unit = {}
) {
    private val chunkPoints = store.load()?.chunkPoints ?: StreamingUploadStore.CHUNK_POINTS
    private val left = FloatArray(chunkPoints)
    private val right = FloatArray(chunkPoints)
    private var filled = 0
    private var nextIndex = store.load()?.chunks?.size ?: 0
    private var totalSamples = store.load()?.chunks?.sumOf { it.sampleCount.toLong() } ?: 0L

    fun append(leftSamples: FloatArray, rightSamples: FloatArray, size: Int) {
        require(size in 0..minOf(leftSamples.size, rightSamples.size))
        var source = 0
        while (source < size) {
            val count = minOf(size - source, chunkPoints - filled)
            leftSamples.copyInto(left, filled, source, source + count)
            rightSamples.copyInto(right, filled, source, source + count)
            filled += count
            source += count
            if (filled == chunkPoints) seal()
        }
    }

    fun flush() {
        if (filled > 0) seal()
    }

    private fun seal() {
        val chunk = store.sealChunk(nextIndex, totalSamples, left, right, filled)
        totalSamples += filled
        nextIndex++
        filled = 0
        onChunkReady(chunk)
    }
}

/** Best-effort live uploader; local chunks remain authoritative until finalization. */
internal class StreamingUploadCoordinator(
    private val client: BrainApiClient,
    private val store: StreamingUploadStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            wakeups.trySend(Unit)
            while (isActive) {
                wakeups.receive()
                var retry = true
                while (retry && isActive) {
                    retry = false
                    try {
                        client.uploadAvailableStreamingChunks(store)
                    } catch (error: BrainApiClient.ApiException) {
                        when {
                            error.isStreamingUnsupported -> store.markUnsupported(error.message.orEmpty())
                            error.isTransient -> {
                                store.markError(error.message.orEmpty())
                                delay(LIVE_RETRY_DELAY_MS)
                                retry = true
                            }
                            else -> store.markError(error.message.orEmpty())
                        }
                    } catch (error: Throwable) {
                        store.markError(error.message ?: "流式上传失败")
                    }
                }
            }
        }
    }

    fun notifyChunkReady(@Suppress("UNUSED_PARAMETER") chunk: StreamingChunk) {
        wakeups.trySend(Unit)
    }

    fun close() {
        scope.cancel()
    }

    private companion object {
        const val LIVE_RETRY_DELAY_MS = 5_000L
    }
}

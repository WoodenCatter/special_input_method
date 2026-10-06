package com.example.input_ds.personalization

import android.os.SystemClock
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.bci.AsyncCalibrationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.random.Random

class ContinuousSessionCollector(
    private val bleManager: NaoyunBleManager,
    private val repository: PersonalizationRepository
) {
    data class Progress(
        val completedActions: Int,
        val totalActions: Int,
        val round: Int,
        val phase: String,
        val label: String,
        val detail: String? = null
    )

    var onStateChange: ((String) -> Unit)? = null
    var onProgress: ((Progress) -> Unit)? = null
    var onCompleted: ((TrainingSession) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var collectionJob: Job? = null
    private var writerChannel: Channel<NaoyunBleManager.StereoSamples>? = null
    private var writerJob: Job? = null
    private val writerFailure = AtomicReference<Throwable?>(null)
    private val writerOverflow = AtomicBoolean(false)
    private val sampleCount = AtomicInteger(0)
    private val markers = Collections.synchronizedList(mutableListOf<SessionMarker>())
    private val asyncTrials = Collections.synchronizedList(mutableListOf<AsyncTrialAnnotation>())

    fun start(localUserId: String, settings: CollectionSettings) {
        if (collectionJob?.isCompleted == false) return
        collectionJob = scope.launch {
            val cuePlayer = CollectionCuePlayer()
            val sessionId = repository.newSessionId()
            val sessionDirectory = repository.createSessionDirectory(localUserId, sessionId)
            val localUser = requireNotNull(repository.findUser(localUserId)) {
                "Local collection user no longer exists"
            }
            val streamingStore = StreamingUploadStore(sessionDirectory)
            streamingStore.initialize(
                sessionId = sessionId,
                localUserId = localUserId,
                profileId = localUser.profileId,
                settings = settings
            )
            val streamingCoordinator = repository.deviceServerBinding()
                ?.takeIf { localUser.profileId != null }
                ?.let { binding ->
                    StreamingUploadCoordinator(
                        BrainApiClient(binding.serverUserId, binding.apiKey),
                        streamingStore
                    )
                }
            val leftRaw = File(sessionDirectory, "left.f32le")
            val rightRaw = File(sessionDirectory, "right.f32le")
            val npz = File(sessionDirectory, "continuous.npz")
            markers.clear()
            asyncTrials.clear()
            sampleCount.set(0)
            writerFailure.set(null)
            writerOverflow.set(false)
            try {
                streamingCoordinator?.start()
                startWriter(leftRaw, rightRaw, streamingStore) { chunk ->
                    streamingCoordinator?.notifyChunkReady(chunk)
                }
                val taskLabels = settings.labels.filterNot { it == "rest" }
                val total = settings.rounds * taskLabels.size
                var completed = 0
                var trialId = 0
                val random = Random(sessionId.hashCode())
                verifyStableStereoStream(total)
                notifyState("双耳数据流稳定，连续异步采集开始，共 ${settings.rounds} 轮 / $total 个 Trial")

                repeat(settings.rounds) { roundIndex ->
                    val labels = taskLabels.shuffled(random)
                    labels.forEach { label ->
                        trialId++
                        collectTrialWithRetries(
                            settings = settings,
                            completed = completed,
                            total = total,
                            round = roundIndex + 1,
                            trialId = trialId,
                            label = label,
                            cuePlayer = cuePlayer,
                            random = random
                        )
                        completed++
                    }
                }

                stopWriter()
                streamingCoordinator?.close()
                writerFailure.get()?.let { throw it }
                val finalCount = currentSampleCount()
                require(finalCount > 0) { "未收到有效的双耳同步数据" }
                val invalidMarker = markers.firstOrNull { marker ->
                    marker.leftSample + settings.windowPoints > finalCount ||
                        marker.rightSample + settings.windowPoints > finalCount
                }
                require(invalidMarker == null) {
                    "本地数据完整性检查失败：${invalidMarker?.let { displayLabel(it.label) }}窗口不足 ${settings.windowPoints} 点"
                }
                require(asyncTrials.size == total) { "完整 Trial 数量不一致：${asyncTrials.size}/$total" }
                require(asyncTrials.all { it.trialEndSample <= finalCount }) {
                    "异步 Trial 标注超出连续信号边界"
                }
                NpzWriter.packFloatArrays(
                    npz,
                    mapOf("left" to (leftRaw to finalCount), "right" to (rightRaw to finalCount))
                )
                leftRaw.delete()
                rightRaw.delete()

                val session = TrainingSession(
                    sessionId = sessionId,
                    localUserId = localUserId,
                    clientProfileId = localUser.clientProfileId,
                    profileId = localUser.profileId,
                    profileDisplayName = localUser.displayName,
                    collectedAtIso = utcTimestamp(),
                    rounds = settings.rounds,
                    actionSeconds = settings.actionSeconds,
                    trainingEpochs = settings.trainingEpochs,
                    sampleCount = finalCount,
                    markers = markers.toList(),
                    asyncTrials = asyncTrials.toList(),
                    npzFile = npz.name,
                    protocol = settings.protocol,
                    labelNames = settings.labels,
                    modelRuns = settings.selectedModels.map { modelKey ->
                        ModelRun(
                            modelKey = modelKey,
                            preset = PreprocessingPreset.UNIFIED_1_45
                        )
                    }
                )
                repository.saveSession(session)
                runCatching { AsyncCalibrationManager.generateForSession(bleManager.context, session) }
                notifyState("采集完成，已打包 ${npz.name}（双耳各 $finalCount 点）")
                withContext(Dispatchers.Main.immediate) { onCompleted?.invoke(session) }
            } catch (cancelled: CancellationException) {
                notifyState("采集已停止，未完成数据不会上传")
                throw cancelled
            } catch (error: Throwable) {
                val message = error.message ?: "采集失败"
                withContext(Dispatchers.Main.immediate) { onError?.invoke(message) }
            } finally {
                cuePlayer.close()
                streamingCoordinator?.close()
                withContext(NonCancellable) { stopWriter() }
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
    }

    fun isRunning(): Boolean = collectionJob?.isActive == true

    private suspend fun verifyStableStereoStream(totalActions: Int) {
        notifyProgress(0, totalActions, 0, "检测", "", "正在检测双耳配对速率")
        notifyState("正在验证双耳配对数据流，恢复前会保持等待，不会终止本次采集")
        var stableWindows = 0
        var nextConnectionRecoveryAt = 0L
        while (stableWindows < CollectionSignalPolicy.REQUIRED_STABLE_WINDOWS) {
            currentCoroutineContext().ensureActive()
            if (bleManager.state.value != NaoyunBleManager.State.READY) {
                stableWindows = 0
                val now = SystemClock.elapsedRealtime()
                if (
                    bleManager.state.value in setOf(
                        NaoyunBleManager.State.ERROR,
                        NaoyunBleManager.State.DISCONNECTED
                    ) && now >= nextConnectionRecoveryAt
                ) {
                    bleManager.requestCurrentDeviceRecovery()
                    nextConnectionRecoveryAt = now + CONNECTION_RECOVERY_COOLDOWN_MS
                }
                notifyProgress(0, totalActions, 0, "等待", "", "耳机断开，等待自动重连")
                notifyState("耳机连接中断，正在等待自动重连；恢复后继续本次采集")
                delay(CollectionSignalPolicy.RATE_CHECK_WINDOW_MS)
                continue
            }
            val (startLeft, startRight) = bleManager.rawSampleCounts()
            val startedAt = SystemClock.elapsedRealtime()
            delay(CollectionSignalPolicy.RATE_CHECK_WINDOW_MS)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val (endLeft, endRight) = bleManager.rawSampleCounts()
            val received = minOf(endLeft - startLeft, endRight - startRight)
                .coerceAtLeast(0L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
            val rateHz = CollectionSignalPolicy.rateHz(received, elapsedMs)
            val hasAlignedCoverage = bleManager.latestAlignedWindowAtMost(
                maxPoints = CollectionSettings.SAMPLE_RATE_HZ,
                minPoints = CollectionSettings.SAMPLE_RATE_HZ / 2
            ) != null
            stableWindows = if (
                bleManager.state.value == NaoyunBleManager.State.READY &&
                CollectionSignalPolicy.isStableRate(rateHz) &&
                hasAlignedCoverage
            ) {
                stableWindows + 1
            } else {
                0
            }
            val rateText = rateHz.roundToInt()
            val detail = "$rateText Hz · 稳定 $stableWindows/${CollectionSignalPolicy.REQUIRED_STABLE_WINDOWS}"
            notifyProgress(0, totalActions, 0, "检测", "", detail)
            notifyState("双耳配对速率 $rateText Hz，稳定检测 $stableWindows/${CollectionSignalPolicy.REQUIRED_STABLE_WINDOWS}")
        }
    }

    private suspend fun collectTrialWithRetries(
        settings: CollectionSettings,
        completed: Int,
        total: Int,
        round: Int,
        trialId: Int,
        label: String,
        cuePlayer: CollectionCuePlayer,
        random: Random
    ) {
        val preRestSeconds = randomRestSeconds(settings.actionSeconds, random)
        val postRestSeconds = randomRestSeconds(settings.actionSeconds, random)
        var attempt = 0
        while (true) {
            attempt++
            writerOverflow.set(false)
            currentCoroutineContext().ensureActive()
            if (bleManager.state.value != NaoyunBleManager.State.READY) {
                verifyStableStereoStream(total)
            }
            val attemptDetail = if (attempt == 1) null else "自动重采 ${attempt - 1}"
            notifyProgress(
                completed = completed,
                total = total,
                round = round,
                phase = "前静息",
                label = label,
                detail = "${"%.2f".format(preRestSeconds)} 秒${attemptDetail?.let { " · $it" }.orEmpty()}"
            )
            notifyState("Trial $trialId：保持自然静息 ${"%.2f".format(preRestSeconds)} 秒")
            val trialEpoch = bleManager.synchronizedDataEpoch.value
            val trialStartUs = bleManager.commonAlignedTimeUs()
            if (trialStartUs == null) {
                verifyStableStereoStream(total)
                continue
            }
            delay((preRestSeconds * 1_000f).roundToInt().toLong())

            if (!trialIsCurrent(trialEpoch)) continue
            val cueStartUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            beginCuedPhase(
                completed = completed,
                total = total,
                round = round,
                phase = "提示",
                label = label,
                stateMessage = "准备执行：${displayLabel(label)}",
                cuePlayer = cuePlayer,
                detail = "提示区间不进入训练"
            )
            delay(CUE_IGNORE_DURATION_MS)
            val taskStartUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            notifyProgress(completed, total, round, "执行", label, "${settings.actionSeconds} 秒")
            notifyState("现在执行：${displayLabel(label)}（${settings.actionSeconds} 秒）")
            delay(settings.phaseDurationMs)
            val taskEndUs = SystemClock.elapsedRealtimeNanos() / 1_000L

            notifyProgress(
                completed, total, round, "后静息", label,
                "${"%.2f".format(postRestSeconds)} 秒"
            )
            notifyState("动作结束，恢复自然静息 ${"%.2f".format(postRestSeconds)} 秒")
            delay((postRestSeconds * 1_000f).roundToInt().toLong())
            val trialEndUs = SystemClock.elapsedRealtimeNanos() / 1_000L
            val requiredPoints = (((trialEndUs - trialStartUs).toDouble() / SAMPLE_PERIOD_US)
                .roundToInt() + 1).coerceAtLeast(settings.windowPoints)
            val actionDeadline = SystemClock.elapsedRealtime() +
                CollectionSignalPolicy.actionWindowTimeoutMs(
                    requiredPoints,
                    CollectionSettings.SAMPLE_RATE_HZ
                )
            var alignedWindow: NaoyunBleManager.AlignedWindow? = null
            while (
                alignedWindow == null &&
                bleManager.synchronizedDataEpoch.value == trialEpoch &&
                bleManager.state.value == NaoyunBleManager.State.READY &&
                SystemClock.elapsedRealtime() < actionDeadline
            ) {
                currentCoroutineContext().ensureActive()
                alignedWindow = bleManager.alignedWindow(
                    trialStartUs,
                    trialEndUs,
                    requiredPoints
                )
                if (alignedWindow != null) break
                delay(ACTION_WINDOW_POLL_MS)
            }

            val epochUnchanged = bleManager.synchronizedDataEpoch.value == trialEpoch
            if (
                epochUnchanged &&
                alignedWindow == null &&
                SystemClock.elapsedRealtime() >= actionDeadline
            ) {
                bleManager.markConsumerDiscontinuity("完整 Trial 等待超时: $label")
            }
            val trialStartSample = if (epochUnchanged && alignedWindow != null) {
                enqueueAlignedWindow(alignedWindow)
            } else {
                null
            }
            if (trialStartSample != null && alignedWindow != null) {
                fun mappedSample(timeUs: Long): Int {
                    val fraction = (timeUs - trialStartUs).toDouble() /
                        (trialEndUs - trialStartUs).toDouble()
                    return trialStartSample + (fraction.coerceIn(0.0, 1.0) *
                        (alignedWindow.left.size - 1)).roundToInt()
                }
                val cueStartSample = mappedSample(cueStartUs)
                val taskStartSample = mappedSample(taskStartUs)
                val taskEndSample = mappedSample(taskEndUs).coerceAtLeast(taskStartSample + 1)
                val trialEndSample = trialStartSample + alignedWindow.left.size
                markers += SessionMarker(label, taskStartSample, taskStartSample, round)
                asyncTrials += AsyncTrialAnnotation(
                    trialId = trialId,
                    taskLabel = label,
                    trialStartSample = trialStartSample,
                    taskStartSample = taskStartSample,
                    taskEndSample = taskEndSample.coerceAtMost(trialEndSample),
                    trialEndSample = trialEndSample,
                    ignoreIntervals = listOf(
                        SampleInterval(
                            cueStartSample.coerceAtLeast(trialStartSample),
                            taskStartSample.coerceAtMost(trialEndSample)
                        )
                    ),
                    preRestSeconds = preRestSeconds,
                    postRestSeconds = postRestSeconds,
                    alignmentQuality = alignedWindow.quality
                )
                return
            }

            val detail = if (!epochUnchanged) {
                "Trial 内检测到双耳丢包，整段不保存"
            } else if (writerOverflow.get()) {
                "采集文件写入队列繁忙，当前动作不保存"
            } else {
                "完整 Trial 没有双耳共同覆盖，整段不保存"
            }
            notifyProgress(
                completed,
                total,
                round,
                "重采",
                label,
                detail
            )
            notifyState("${displayLabel(label)}本次无效：$detail；信号恢复后自动重采，不终止本次采集")
            verifyStableStereoStream(total)
            notifyState("信号恢复后自动重采完整 Trial")
        }
    }

    private fun trialIsCurrent(epoch: Long): Boolean =
        bleManager.state.value == NaoyunBleManager.State.READY &&
            bleManager.synchronizedDataEpoch.value == epoch

    private fun randomRestSeconds(windowSeconds: Float, random: Random): Float {
        val minimum = maxOf(1f, windowSeconds)
        val maximum = maxOf(2f, minimum + 0.5f)
        return minimum + random.nextFloat() * (maximum - minimum)
    }

    private fun currentSampleCount(): Int {
        writerFailure.get()?.let { throw IllegalStateException("采集文件写入失败", it) }
        return sampleCount.get()
    }

    /**
     * Commits only a completed, wall-clock-aligned action window. The session
     * sample index is therefore a compact file index, never either ear's raw
     * hardware index.
     */
    private fun enqueueAlignedWindow(window: NaoyunBleManager.AlignedWindow): Int? {
        val size = minOf(window.left.size, window.right.size)
        if (size == 0) return null
        val channel = writerChannel ?: return null
        val startSample = sampleCount.get()
        val payload = NaoyunBleManager.StereoSamples(
            left = window.left.copyOf(size),
            right = window.right.copyOf(size),
            startSample = startSample
        )
        if (channel.trySend(payload).isSuccess) {
            sampleCount.addAndGet(size)
            return startSample
        }
        writerOverflow.set(true)
        bleManager.markConsumerDiscontinuity("采集写入队列溢出")
        return null
    }

    private fun startWriter(
        leftRaw: File,
        rightRaw: File,
        streamingStore: StreamingUploadStore,
        onChunkReady: (StreamingChunk) -> Unit
    ) {
        val channel = Channel<NaoyunBleManager.StereoSamples>(WRITER_QUEUE_PACKETS)
        writerChannel = channel
        writerJob = scope.launch(Dispatchers.IO) {
            val chunkAccumulator = StreamingChunkAccumulator(streamingStore, onChunkReady)
            try {
                DataOutputStream(BufferedOutputStream(leftRaw.outputStream())).use { left ->
                    DataOutputStream(BufferedOutputStream(rightRaw.outputStream())).use { right ->
                        for (samples in channel) {
                            val size = minOf(samples.left.size, samples.right.size)
                            left.writeFloatArrayLittleEndian(samples.left, size)
                            right.writeFloatArrayLittleEndian(samples.right, size)
                            chunkAccumulator.append(samples.left, samples.right, size)
                        }
                        chunkAccumulator.flush()
                    }
                }
            } catch (error: Throwable) {
                writerFailure.compareAndSet(null, error)
                bleManager.markConsumerDiscontinuity("采集文件写入失败: ${error.message}")
            }
        }
    }

    private suspend fun stopWriter() {
        val channel = writerChannel
        val job = writerJob
        writerChannel = null
        writerJob = null
        channel?.close()
        if (job != null) {
            val drained = withTimeoutOrNull(WRITER_DRAIN_TIMEOUT_MS) { job.join() } != null
            if (!drained) {
                job.cancel()
                writerFailure.compareAndSet(
                    null,
                    IllegalStateException("采集文件写入队列未能及时清空")
                )
            }
        }
    }

    private suspend fun notifyState(message: String) =
        withContext(Dispatchers.Main.immediate) { onStateChange?.invoke(message) }

    private suspend fun notifyProgress(
        completed: Int,
        total: Int,
        round: Int,
        phase: String,
        label: String,
        detail: String? = null
    ) =
        withContext(Dispatchers.Main.immediate) {
            onProgress?.invoke(Progress(completed, total, round, phase, label, detail))
        }

    /** Starts the visible phase and its cue in one main-thread event cycle. */
    private suspend fun beginCuedPhase(
        completed: Int,
        total: Int,
        round: Int,
        phase: String,
        label: String,
        stateMessage: String,
        cuePlayer: CollectionCuePlayer,
        detail: String? = null
    ) = withContext(Dispatchers.Main.immediate) {
        onProgress?.invoke(Progress(completed, total, round, phase, label, detail))
        onStateChange?.invoke(stateMessage)
        require(cuePlayer.play()) { "播放采集提示音失败" }
    }

    private fun displayLabel(label: String): String = CollectionSettings.LABEL_DISPLAY[label] ?: label

    private fun DataOutputStream.writeFloatArrayLittleEndian(values: FloatArray, size: Int) {
        val bytes = ByteArray(size * Float.SIZE_BYTES)
        for (index in 0 until size) {
            val bits = values[index].toRawBits()
            val offset = index * Float.SIZE_BYTES
            bytes[offset] = bits.toByte()
            bytes[offset + 1] = (bits ushr 8).toByte()
            bytes[offset + 2] = (bits ushr 16).toByte()
            bytes[offset + 3] = (bits ushr 24).toByte()
        }
        write(bytes)
    }

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    companion object {
        private const val CUE_IGNORE_DURATION_MS = 250L
        private const val SAMPLE_PERIOD_US = 2_000L
        private const val ACTION_WINDOW_POLL_MS = 20L
        private const val WRITER_QUEUE_PACKETS = 20
        private const val WRITER_DRAIN_TIMEOUT_MS = 5_000L
        private const val CONNECTION_RECOVERY_COOLDOWN_MS = 5_000L
    }
}

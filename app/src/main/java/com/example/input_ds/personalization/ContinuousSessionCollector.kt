package com.example.input_ds.personalization

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import com.example.input_ds.bci.NaoyunBleManager
import com.example.input_ds.bci.AsyncCalibrationManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.Collections
import kotlin.math.roundToInt

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
    private val fileLock = Any()
    private var leftOutput: DataOutputStream? = null
    private var rightOutput: DataOutputStream? = null
    private var sampleCount = 0
    private val markers = Collections.synchronizedList(mutableListOf<SessionMarker>())

    private val stereoListener: (NaoyunBleManager.StereoSamples) -> Unit = listener@{ samples ->
        val size = minOf(samples.left.size, samples.right.size)
        if (size == 0) return@listener
        synchronized(fileLock) {
            val left = leftOutput ?: return@synchronized
            val right = rightOutput ?: return@synchronized
            for (index in 0 until size) {
                left.writeFloatLittleEndian(samples.left[index])
                right.writeFloatLittleEndian(samples.right[index])
            }
            sampleCount += size
        }
    }

    fun start(localUserId: String, settings: CollectionSettings) {
        if (collectionJob?.isActive == true) return
        collectionJob = scope.launch {
            val sessionId = repository.newSessionId()
            val sessionDirectory = repository.createSessionDirectory(localUserId, sessionId)
            val leftRaw = File(sessionDirectory, "left.f32le")
            val rightRaw = File(sessionDirectory, "right.f32le")
            val npz = File(sessionDirectory, "continuous.npz")
            markers.clear()
            sampleCount = 0
            try {
                synchronized(fileLock) {
                    leftOutput = DataOutputStream(BufferedOutputStream(leftRaw.outputStream()))
                    rightOutput = DataOutputStream(BufferedOutputStream(rightRaw.outputStream()))
                }
                bleManager.addStereoListener(stereoListener)
                val total = settings.rounds * settings.labels.size
                var completed = 0
                verifyStableStereoStream(total)
                notifyState("双耳数据流稳定，连续采集开始，共 ${settings.rounds} 轮 / $total 个动作")

                repeat(settings.rounds) { roundIndex ->
                    val labels = settings.labels.shuffled()
                    labels.forEach { label ->
                        collectActionWithRetries(
                            settings = settings,
                            completed = completed,
                            total = total,
                            round = roundIndex + 1,
                            label = label
                        )

                        completed++
                        notifyProgress(completed, total, roundIndex + 1, "休息", label)
                        notifyState("休息 ${settings.restSeconds} 秒")
                        delay((settings.restSeconds * 1000).toLong())
                    }
                }

                closeOutputs()
                bleManager.removeStereoListener(stereoListener)
                val finalCount = currentSampleCount()
                require(finalCount > 0) { "未收到有效的双耳同步数据" }
                val invalidMarker = markers.firstOrNull { marker ->
                    marker.leftSample + settings.windowPoints > finalCount ||
                        marker.rightSample + settings.windowPoints > finalCount
                }
                require(invalidMarker == null) {
                    "本地数据完整性检查失败：${invalidMarker?.let { displayLabel(it.label) }}窗口不足 ${settings.windowPoints} 点"
                }
                NpzWriter.packFloatArrays(
                    npz,
                    mapOf("left" to (leftRaw to finalCount), "right" to (rightRaw to finalCount))
                )
                leftRaw.delete()
                rightRaw.delete()

                val localUser = requireNotNull(repository.findUser(localUserId)) {
                    "Local collection user no longer exists"
                }
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
                    npzFile = npz.name,
                    protocol = settings.protocol,
                    labelNames = settings.labels,
                    modelRuns = settings.selectedModels.map { modelKey ->
                        ModelRun(
                            modelKey = modelKey,
                            preset = settings.presetByModel[modelKey] ?: PreprocessingPreset.EEGNET
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
                bleManager.removeStereoListener(stereoListener)
                closeOutputs()
            }
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    fun isRunning(): Boolean = collectionJob?.isActive == true

    private suspend fun verifyStableStereoStream(totalActions: Int) {
        notifyProgress(0, totalActions, 0, "检测", "", "正在检测双耳配对速率")
        notifyState("正在验证双耳配对数据流，恢复前会保持等待，不会终止本次采集")
        var stableWindows = 0
        while (stableWindows < CollectionSignalPolicy.REQUIRED_STABLE_WINDOWS) {
            currentCoroutineContext().ensureActive()
            if (bleManager.state.value != NaoyunBleManager.State.READY) {
                stableWindows = 0
                notifyProgress(0, totalActions, 0, "等待", "", "耳机断开，等待自动重连")
                notifyState("耳机连接中断，正在等待自动重连；恢复后继续本次采集")
                delay(CollectionSignalPolicy.RATE_CHECK_WINDOW_MS)
                continue
            }
            val startCount = currentSampleCount()
            val startedAt = SystemClock.elapsedRealtime()
            delay(CollectionSignalPolicy.RATE_CHECK_WINDOW_MS)
            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
            val received = currentSampleCount() - startCount
            val rateHz = CollectionSignalPolicy.rateHz(received, elapsedMs)
            stableWindows = if (
                bleManager.state.value == NaoyunBleManager.State.READY &&
                CollectionSignalPolicy.isStableRate(rateHz)
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

    private suspend fun collectActionWithRetries(
        settings: CollectionSettings,
        completed: Int,
        total: Int,
        round: Int,
        label: String
    ) {
        val requiredPoints = settings.windowPoints
        var attempt = 0
        while (true) {
            attempt++
            currentCoroutineContext().ensureActive()
            if (bleManager.state.value != NaoyunBleManager.State.READY) {
                verifyStableStereoStream(total)
            }
            val attemptDetail = if (attempt == 1) null else "自动重采 ${attempt - 1}"
            notifyProgress(completed, total, round, "准备", label, attemptDetail)
            notifyState("第 $round 轮：准备 ${displayLabel(label)}${attemptDetail?.let { "（$it）" }.orEmpty()}")
            playCue()
            delay((settings.preparationSeconds * 1000).toLong())

            if (bleManager.state.value != NaoyunBleManager.State.READY) {
                notifyState("动作开始前连接中断，等待恢复后重新提示当前动作")
                verifyStableStereoStream(total)
                continue
            }
            notifyProgress(completed, total, round, "执行", label, "目标 $requiredPoints 点")
            notifyState("现在执行：${displayLabel(label)}（${settings.actionSeconds} 秒，目标 $requiredPoints 点）")
            // State mutation alone does not mean Compose has drawn the next
            // frame. Leave several display frames before using the audio cue
            // and marker as the shared formal-action start.
            delay(EXECUTION_PROMPT_LEAD_MS)
            val markerEpoch = bleManager.synchronizedDataEpoch.value
            val markerSample = markActionStartAndPlayCue() + SAFE_MARKER_LEAD_POINTS
            val targetEndSample = markerSample + requiredPoints
            while (
                currentSampleCount() < targetEndSample &&
                bleManager.synchronizedDataEpoch.value == markerEpoch &&
                bleManager.state.value == NaoyunBleManager.State.READY
            ) {
                currentCoroutineContext().ensureActive()
                delay(ACTION_WINDOW_POLL_MS)
            }

            val capturedPoints = currentSampleCount() - markerSample
            val epochUnchanged = bleManager.synchronizedDataEpoch.value == markerEpoch
            if (CollectionSignalPolicy.hasValidActionWindow(
                    markerSample,
                    markerSample + capturedPoints,
                    requiredPoints,
                    markerEpoch,
                    bleManager.synchronizedDataEpoch.value
                )
            ) {
                markers += SessionMarker(label, markerSample, markerSample, round)
                return
            }

            val detail = if (!epochUnchanged) {
                "动作窗口内检测到双耳丢包，当前动作不保存"
            } else {
                "仅收到 ${capturedPoints.coerceAtLeast(0)}/$requiredPoints 点，当前动作不保存"
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
            delay((settings.restSeconds * 1000).toLong())
        }
    }

    private fun currentSampleCount(): Int {
        return synchronized(fileLock) { sampleCount }
    }

    private fun closeOutputs() = synchronized(fileLock) {
        runCatching { leftOutput?.flush() }
        runCatching { rightOutput?.flush() }
        runCatching { leftOutput?.close() }
        runCatching { rightOutput?.close() }
        leftOutput = null
        rightOutput = null
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

    private fun displayLabel(label: String): String = CollectionSettings.LABEL_DISPLAY[label] ?: label

    private fun playCue() {
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 75).also { tone ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                scope.launch {
                    delay(180)
                    tone.release()
                }
            }
        }
    }

    private fun markActionStartAndPlayCue(): Int {
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 75) }.getOrNull()
        val markerSample = currentSampleCount()
        tone?.let {
            runCatching { it.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
            scope.launch {
                delay(180)
                it.release()
            }
        }
        return markerSample
    }

    private fun DataOutputStream.writeFloatLittleEndian(value: Float) {
        writeInt(Integer.reverseBytes(value.toRawBits()))
    }

    private fun utcTimestamp(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())

    companion object {
        /** Allows the execution prompt to become visible before the cue/marker. */
        private const val EXECUTION_PROMPT_LEAD_MS = 50L
        /** Skips at most one 100 ms packet that may have been acquired before the cue. */
        private const val SAFE_MARKER_LEAD_POINTS = CollectionSettings.SAMPLE_RATE_HZ / 10
        private const val ACTION_WINDOW_POLL_MS = 20L
    }
}

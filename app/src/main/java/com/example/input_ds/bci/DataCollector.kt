package com.example.input_ds.bci

import android.content.Context
import android.media.ToneGenerator
import android.media.AudioManager
import kotlinx.coroutines.*
import java.io.File
import java.io.DataOutputStream
import java.io.FileOutputStream
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * 数据采集器（对照 Python collect.py）
 *
 * 范式：提示音 → 用户执行动作 → 记录1.1秒 → 取前500点保存
 * 四分类每类 TRIALS_PER_CLASS 试次，共 4×20=80 个 trial
 */
class DataCollector(
    private val bleManager: NaoyunBleManager,
    private val context: Context
) {
    companion object {
        const val TRIALS_PER_CLASS = 20
        val CLASS_NAMES = listOf("休息", "咬牙", "左看", "右看")
        const val CAPTURE_SECONDS = 1.1f
        const val TARGET_POINTS = 500
        const val SAMPLE_RATE = 500
        private const val SAMPLE_PERIOD_US = 2_000L
        private const val MAX_RETRIES_PER_TRIAL = 3
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectionJob: Job? = null

    /** 采集状态回调 */
    var onStateChange: ((String) -> Unit)? = null
    var onProgress: ((Int, Int, String) -> Unit)? = null  // current, total, classname
    var onTrialSaved: ((String) -> Unit)? = null

    private var totalTrials = 0
    private var savedTrials = 0
    private var saveDir: File? = null

    fun startCollection() {
        if (collectionJob?.isActive == true) return
        saveDir = File(context.filesDir, "eeg_data_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}")
        check(saveDir!!.mkdirs() || saveDir!!.isDirectory) { "无法创建采集目录" }
        totalTrials = CLASS_NAMES.size * TRIALS_PER_CLASS
        savedTrials = 0

        collectionJob = scope.launch {
            notifyState("开始采集，共 ${totalTrials} 个试次")
            for (round in 0 until TRIALS_PER_CLASS) {
                for ((classIdx, className) in CLASS_NAMES.withIndex()) {
                    ensureActive()
                    val trialIndex = round * CLASS_NAMES.size + classIdx + 1
                    var saved = false
                    var attempt = 0
                    while (!saved && attempt < MAX_RETRIES_PER_TRIAL) {
                        ensureActive()
                        attempt++
                        notifyProgress(trialIndex, totalTrials, className)
                        notifyState("第 ${trialIndex}/${totalTrials} 试次: $className")
                        bleManager.resetSynchronizedData()

                        playCueBeep()
                        delay(1200)

                        val captureStartUs = android.os.SystemClock.elapsedRealtimeNanos() / 1_000L
                        val captureEndUs = captureStartUs +
                            (TARGET_POINTS - 1L) * SAMPLE_PERIOD_US
                        notifyState("$className — 请执行动作 (${CAPTURE_SECONDS}s)")

                        delay((CAPTURE_SECONDS * 1000).toLong())
                        delay(300)

                        val aligned = bleManager.alignedWindow(
                            captureStartUs,
                            captureEndUs,
                            TARGET_POINTS
                        )
                        val leftData = aligned?.left ?: FloatArray(0)
                        val rightData = aligned?.right ?: FloatArray(0)

                        if (leftData.size >= TARGET_POINTS && rightData.size >= TARGET_POINTS) {
                            saveTrial(
                                trialIndex,
                                leftData.copyOfRange(0, TARGET_POINTS),
                                rightData.copyOfRange(0, TARGET_POINTS),
                                className,
                                classIdx
                            )
                            savedTrials++
                            saved = true
                            notifyTrial("✅ 已保存 trial_${trialIndex} ($className)")
                        } else {
                            notifyTrial(
                                "⚠️ trial_${trialIndex} 数据不足 " +
                                        "(L:${leftData.size} R:${rightData.size})，重试 $attempt/$MAX_RETRIES_PER_TRIAL"
                            )
                        }
                    }
                    if (!saved) {
                        notifyTrial("❌ trial_${trialIndex} ($className) 连续失败，已跳过")
                    }
                }
            }
            saveManifest()
            notifyState("采集完成：$savedTrials/$totalTrials，保存在 ${saveDir!!.absolutePath}")
        }
    }

    fun stop() {
        collectionJob?.cancel()
        collectionJob = null
    }

    private suspend fun notifyState(message: String) =
        withContext(Dispatchers.Main.immediate) { onStateChange?.invoke(message) }

    private suspend fun notifyProgress(current: Int, total: Int, className: String) =
        withContext(Dispatchers.Main.immediate) { onProgress?.invoke(current, total, className) }

    private suspend fun notifyTrial(message: String) =
        withContext(Dispatchers.Main.immediate) { onTrialSaved?.invoke(message) }

    private fun saveTrial(index: Int, left: FloatArray, right: FloatArray, className: String, classIdx: Int) {
        val file = File(saveDir, "${index}_data.bin")
        DataOutputStream(FileOutputStream(file)).use { dos ->
            // 写入 2×500 float 数组（先左后右）
            left.forEach { dos.writeFloat(it) }
            right.forEach { dos.writeFloat(it) }
        }
        // 同时保存元信息
        val meta = JSONObject().apply {
            put("trial", index)
            put("class_name", className)
            put("class_idx", classIdx)
            put("points", TARGET_POINTS)
            put("channels", 2)
        }
        File(saveDir, "${index}_meta.json").writeText(meta.toString(2))
    }

    private fun saveManifest() {
        val manifest = JSONObject().apply {
            put("class_names", CLASS_NAMES.joinToString(","))
            put("trials_per_class", TRIALS_PER_CLASS)
            put("total_trials", totalTrials)
            put("saved_trials", savedTrials)
            put("sample_rate", SAMPLE_RATE)
            put("target_points", TARGET_POINTS)
            put("capture_seconds", CAPTURE_SECONDS.toDouble())
            put("created_at", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
        }
        File(saveDir, "collection_manifest.json").writeText(manifest.toString(2))
    }

    /** 双滴滴提示音 */
    private fun playCueBeep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tg.startTone(ToneGenerator.TONE_DTMF_S, 100)
            Thread.sleep(100)
            tg.startTone(ToneGenerator.TONE_DTMF_S, 100)
            Thread.sleep(120)
            tg.release()
        } catch (_: Exception) {}
    }
}

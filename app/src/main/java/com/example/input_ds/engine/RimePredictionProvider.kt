package com.example.input_ds.engine

import android.content.Context
import android.os.SystemClock
import com.example.input_ds.rime.RimeDataDeployer
import com.osfans.trime.core.Rime
import com.osfans.trime.data.opencc.OpenCCDictManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Prediction-only librime adapter; it never participates in the app's pinyin UI. */
class RimePredictionProvider(
    context: Context,
    private val queryTimeoutMs: Long = 300L
) : PredictionProvider, AutoCloseable {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "rime-prediction").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val initializationFuture: Future<*>
    private val pinyinByCharacter by lazy { loadPinyinIndex() }

    @Volatile private var initialized = false
    @Volatile private var nativeStarted = false
    @Volatile private var t2sConfigPath = ""
    @Volatile private var s2tConfigPath = ""
    @Volatile private var runtimeState = RuntimeState.INITIALIZING
    @Volatile private var initializationMessage: String? = null

    init {
        initializationFuture = executor.submit { initializeSafely() }
    }

    override suspend fun predict(context: String, limit: Int): List<PredictionCandidate> {
        return predictWithDebug(context, limit).candidates
    }

    internal suspend fun predictWithDebug(context: String, limit: Int): RimePredictionBatch {
        val startedAt = SystemClock.elapsedRealtime()
        val boundedContext = PredictionText.takeLastCodePoints(context, MAX_CONTEXT_CODE_POINTS)
        if (context.isEmpty() || limit <= 0 || closed.get()) {
            return emptyList<PredictionCandidate>().asRimeBatch(
                boundedContext,
                startedAt,
                if (closed.get()) PredictionDebugStatus.RIME_QUERY_FAILED else PredictionDebugStatus.RIME_NO_MATCH,
                if (closed.get()) "Rime provider已关闭" else null
            )
        }

        runtimeFailureStatus()?.let { status ->
            return emptyList<PredictionCandidate>().asRimeBatch(
                boundedContext,
                startedAt,
                status,
                initializationMessage
            )
        }

        return try {
            val outcome = withTimeoutOrNull(queryTimeoutMs) {
                submit { query(boundedContext, limit) }
            }
            if (outcome == null) {
                val status = if (
                    runtimeState == RuntimeState.INITIALIZING ||
                    runtimeState == RuntimeState.SCHEMA_DEPLOYING
                ) {
                    PredictionDebugStatus.RIME_INITIALIZING
                } else {
                    PredictionDebugStatus.RIME_QUERY_TIMEOUT
                }
                emptyList<PredictionCandidate>().asRimeBatch(
                    boundedContext,
                    startedAt,
                    status,
                    if (status == PredictionDebugStatus.RIME_INITIALIZING) {
                        "初始化未在${queryTimeoutMs}ms查询窗口内完成"
                    } else {
                        "查询超过${queryTimeoutMs}ms"
                    }
                )
            } else {
                val runtimeFailure = runtimeFailureStatus()
                RimePredictionBatch(
                    candidates = outcome.candidates,
                    diagnostics = RimePredictionDiagnostics(
                        submittedContext = outcome.submittedContext,
                        rawCount = outcome.rawCount,
                        convertedCount = outcome.convertedCount,
                        filteredCount = outcome.filteredCount,
                        elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                        status = when {
                            runtimeFailure != null -> runtimeFailure
                            outcome.candidates.isNotEmpty() -> PredictionDebugStatus.RIME_MIXED_SUCCESS
                            outcome.rawCount > 0 -> PredictionDebugStatus.RIME_RESULTS_FILTERED
                            else -> PredictionDebugStatus.RIME_NO_MATCH
                        },
                        message = if (runtimeFailure != null) initializationMessage else outcome.message
                    )
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            val status = runtimeFailureStatus() ?: when {
                hasNativeLoadCause(error) -> PredictionDebugStatus.RIME_NATIVE_LOAD_FAILED
                else -> PredictionDebugStatus.RIME_QUERY_FAILED
            }
            emptyList<PredictionCandidate>().asRimeBatch(
                boundedContext,
                startedAt,
                status,
                conciseError(error)
            )
        }
    }

    private fun initializeSafely() {
        if (closed.get()) return
        runCatching {
            val directories = RimeDataDeployer(appContext).prepare()
            val predictDatabase = File(directories.shared, PREDICT_DATABASE_NAME)
            if (!isReadablePredictDatabase(predictDatabase)) {
                runtimeState = RuntimeState.DATABASE_UNAVAILABLE
                error("$PREDICT_DATABASE_NAME missing or unreadable")
            }
            t2sConfigPath = File(directories.shared, "opencc/t2s.json").absolutePath
            s2tConfigPath = File(directories.shared, "opencc/s2t.json").absolutePath
            Rime.startupRime(
                directories.shared.absolutePath,
                directories.user.absolutePath,
                DISTRIBUTION_VERSION,
                true
            )
            nativeStarted = true
            runtimeState = RuntimeState.SCHEMA_DEPLOYING

            var selected = false
            for (attempt in 0 until DEPLOY_RETRIES) {
                if (closed.get()) return@runCatching
                val schemas = runCatching { Rime.getRimeSchemaList().map { it.schemaId } }.getOrDefault(emptyList())
                if (SCHEMA_ID in schemas && Rime.selectRimeSchema(SCHEMA_ID)) {
                    selected = true
                    break
                }
                if (attempt < DEPLOY_RETRIES - 1) Thread.sleep(DEPLOY_RETRY_DELAY_MS)
            }
            if (!selected) {
                runtimeState = RuntimeState.SCHEMA_DEPLOY_FAILED
                error("Rime prediction schema deployment failed")
            }
            Rime.setRimeOption("prediction", true)
            initialized = true
            runtimeState = RuntimeState.READY
            initializationMessage = null
        }.onFailure { error ->
            initialized = false
            runtimeState = when {
                hasNativeLoadCause(error) -> RuntimeState.NATIVE_LOAD_FAILED
                runtimeState == RuntimeState.SCHEMA_DEPLOYING -> RuntimeState.SCHEMA_DEPLOY_FAILED
                runtimeState == RuntimeState.INITIALIZING -> RuntimeState.FAILED
                else -> runtimeState
            }
            initializationMessage = conciseError(error)
            if (nativeStarted) runCatching { Rime.exitRime() }
            nativeStarted = false
        }
    }

    private fun query(context: String, limit: Int): RimeQueryOutcome {
        if (!initialized || context.isEmpty() || Thread.currentThread().isInterrupted) {
            return RimeQueryOutcome(emptyList(), 0, 0, 0, context)
        }

        val contextCodePoints = PredictionText.codePointStrings(context)
        val maxQueryLength = minOf(MAX_RIME_QUERY_CODE_POINTS, contextCodePoints.size)
        var rawCount = 0
        var convertedCount = 0
        var filteredCount = 0
        var submittedContext = context
        for (length in maxQueryLength downTo 1) {
            if (Thread.currentThread().isInterrupted) {
                return RimeQueryOutcome(emptyList(), rawCount, convertedCount, filteredCount, submittedContext)
            }
            val simplifiedQueryParts = contextCodePoints.takeLast(length)
            val pinyin = simplifiedQueryParts.map { pinyinByCharacter[it]?.firstOrNull() ?: return@map "" }
            if (pinyin.any { it.isEmpty() }) continue

            val simplifiedQuery = simplifiedQueryParts.joinToString("")
            val rimeKeySequence = pinyin.joinToString("'")
            submittedContext = "$simplifiedQuery / $rimeKeySequence"
            val traditionalQuery = runCatching {
                OpenCCDictManager.openCCLineConv(simplifiedQuery, s2tConfigPath)
            }.getOrNull()?.takeIf { it.isNotEmpty() } ?: continue

            Rime.clearRimeComposition()
            rimeKeySequence.forEach { Rime.processRimeKey(it.code, 0) }
            val candidates = Rime.getRimeCandidates(0, CONTEXT_CANDIDATE_LIMIT)
            val candidateIndex = candidates.indexOfFirst { it.text == traditionalQuery || it.text == simplifiedQuery }
            if (candidateIndex < 0 || !Rime.selectRimeCandidate(candidateIndex, true)) continue

            val rawPredictions = Rime.getRimeCandidates(0, limit * RIME_CANDIDATE_MULTIPLIER)
            rawCount += rawPredictions.size
            val converted = rawPredictions.mapNotNull { candidate ->
                val simplified = runCatching {
                    OpenCCDictManager.openCCLineConv(candidate.text, t2sConfigPath)
                }.getOrNull() ?: return@mapNotNull null
                convertedCount++
                val suffix = PredictionText.removeCompleteContextPrefix(context, simplified)
                if (!PredictionText.isDisplayableSuffix(suffix)) return@mapNotNull null
                PredictionCandidate(suffix, PredictionSource.RIME, score = 1.0)
            }.distinctBy { it.text }
            filteredCount += converted.size
            Rime.clearRimeComposition()
            if (converted.isNotEmpty()) {
                return RimeQueryOutcome(
                    converted.take(limit),
                    rawCount,
                    convertedCount,
                    filteredCount,
                    submittedContext,
                    "native已加载；schema已部署；predict.db可读；" +
                        "OpenCC后$convertedCount；长度/标点/去重后$filteredCount"
                )
            }
        }
        Rime.clearRimeComposition()
        return RimeQueryOutcome(
            emptyList(),
            rawCount,
            convertedCount,
            filteredCount,
            submittedContext,
            "native已加载；schema已部署；predict.db可读；" +
                "OpenCC后$convertedCount；长度/标点/去重后$filteredCount"
        )
    }

    private fun runtimeFailureStatus(): PredictionDebugStatus? = when (runtimeState) {
        RuntimeState.NATIVE_LOAD_FAILED -> PredictionDebugStatus.RIME_NATIVE_LOAD_FAILED
        RuntimeState.SCHEMA_DEPLOY_FAILED -> PredictionDebugStatus.RIME_SCHEMA_DEPLOY_FAILED
        RuntimeState.DATABASE_UNAVAILABLE -> PredictionDebugStatus.RIME_DATABASE_UNAVAILABLE
        RuntimeState.FAILED, RuntimeState.CLOSED -> PredictionDebugStatus.RIME_QUERY_FAILED
        RuntimeState.INITIALIZING, RuntimeState.SCHEMA_DEPLOYING, RuntimeState.READY -> null
    }

    private fun isReadablePredictDatabase(file: File): Boolean = runCatching {
        file.isFile && file.length() > 0L && file.inputStream().use { it.read() >= 0 }
    }.getOrDefault(false)

    private fun hasNativeLoadCause(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is UnsatisfiedLinkError ||
                current is ExceptionInInitializerError ||
                current is NoClassDefFoundError
            ) return true
            current = current.cause
        }
        return false
    }

    private fun conciseError(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val detail = root.message?.take(120)?.takeIf { it.isNotBlank() }
        return if (detail == null) root.javaClass.simpleName else "${root.javaClass.simpleName}: $detail"
    }

    private fun List<PredictionCandidate>.asRimeBatch(
        submittedContext: String,
        startedAt: Long,
        status: PredictionDebugStatus,
        message: String?
    ) = RimePredictionBatch(
        candidates = this,
        diagnostics = RimePredictionDiagnostics(
            submittedContext = submittedContext,
            rawCount = status.knownEmptyCount,
            convertedCount = status.knownEmptyCount,
            filteredCount = status.knownEmptyCount,
            elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            status = status,
            message = message
        )
    )

    private val PredictionDebugStatus.knownEmptyCount: Int
        get() = if (this == PredictionDebugStatus.RIME_NO_MATCH) 0 else -1

    private fun loadPinyinIndex(): Map<String, List<String>> {
        val result = LinkedHashMap<String, MutableList<String>>()
        appContext.assets.open("pinyin_map.txt").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size != 2) return@forEach
                val pinyin = parts[0].trim()
                if (pinyin.isEmpty()) return@forEach
                parts[1].split(',').asSequence().map(String::trim)
                    .filter { PredictionText.isDisplayableSuffix(it, maxCodePoints = 1) }
                    .forEach { text ->
                        result.getOrPut(text) { mutableListOf() }.apply {
                            if (pinyin !in this) add(pinyin)
                        }
                    }
            }
        }
        return result
    }

    private suspend fun <T> submit(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        lateinit var future: Future<*>
        future = executor.submit {
            try {
                val result = block()
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
        continuation.invokeOnCancellation { future.cancel(true) }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runtimeState = RuntimeState.CLOSED
        initializationFuture.cancel(true)
        executor.execute {
            if (nativeStarted) runCatching { Rime.exitRime() }
            initialized = false
            nativeStarted = false
        }
        executor.shutdown()
    }

    private companion object {
        const val SCHEMA_ID = "prediction_context"
        const val DISTRIBUTION_VERSION = "1.0-trime-3.3.11-predict-data-1.0"
        const val MAX_CONTEXT_CODE_POINTS = 32
        const val MAX_RIME_QUERY_CODE_POINTS = 8
        const val CONTEXT_CANDIDATE_LIMIT = 512
        const val RIME_CANDIDATE_MULTIPLIER = 4
        const val DEPLOY_RETRIES = 120
        const val DEPLOY_RETRY_DELAY_MS = 250L
        const val PREDICT_DATABASE_NAME = "predict.db"
    }

    private enum class RuntimeState {
        INITIALIZING,
        SCHEMA_DEPLOYING,
        READY,
        NATIVE_LOAD_FAILED,
        SCHEMA_DEPLOY_FAILED,
        DATABASE_UNAVAILABLE,
        FAILED,
        CLOSED
    }

    private data class RimeQueryOutcome(
        val candidates: List<PredictionCandidate>,
        val rawCount: Int,
        val convertedCount: Int,
        val filteredCount: Int,
        val submittedContext: String,
        val message: String? = null
    )
}

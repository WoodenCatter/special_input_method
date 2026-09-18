package com.example.input_ds.engine

/** Temporary, non-persistent diagnostics for one completed prediction request. */
data class PredictionDebugInfo(
    val contextPreview: String,
    val submittedRimeContext: String,
    val localCount: Int,
    val rimeRawCount: Int,
    val rimeConvertedCount: Int,
    val rimeFilteredCount: Int,
    val rimeMergedCount: Int,
    val mergedCount: Int,
    val elapsedMs: Long,
    val status: PredictionDebugStatus,
    val message: String? = null,
    val eventId: Long = 0L,
    val requestId: String? = null,
    val llmRawCount: Int = 0,
    val llmAcceptedCount: Int = 0,
    val llmMergedCount: Int = 0,
    val llmElapsedMs: Long = 0L
)

enum class PredictionDebugStatus(val displayName: String) {
    RIME_MIXED_SUCCESS("Rime混合成功"),
    RIME_NO_MATCH("Rime无匹配"),
    RIME_INITIALIZING("Rime初始化未完成"),
    RIME_NATIVE_LOAD_FAILED("Rime native加载失败"),
    RIME_SCHEMA_DEPLOY_FAILED("Rime schema部署失败"),
    RIME_DATABASE_UNAVAILABLE("Rime predict.db不可用"),
    RIME_QUERY_TIMEOUT("Rime查询超时"),
    RIME_RESULTS_FILTERED("Rime结果全部被过滤"),
    RIME_QUERY_FAILED("Rime查询失败"),
    LOCAL_ONLY("仅使用本地预测"),
    LLM_MIXED_SUCCESS("LLM异步补充成功"),
    LLM_EMPTY("LLM成功但无候选"),
    LLM_RESULTS_FILTERED("LLM候选全部被过滤"),
    LLM_NO_NEW_CANDIDATES("LLM候选无新增"),
    LLM_UNAVAILABLE("LLM不可用，已回退本地"),
    NO_PREDICTIONS("无任何预测结果")
}

internal fun contextLlmDebugStatus(
    responseAvailable: Boolean,
    rawCount: Int,
    acceptedCount: Int,
    addedCount: Int
): PredictionDebugStatus = when {
    !responseAvailable -> PredictionDebugStatus.LLM_UNAVAILABLE
    rawCount == 0 -> PredictionDebugStatus.LLM_EMPTY
    acceptedCount == 0 -> PredictionDebugStatus.LLM_RESULTS_FILTERED
    addedCount == 0 -> PredictionDebugStatus.LLM_NO_NEW_CANDIDATES
    else -> PredictionDebugStatus.LLM_MIXED_SUCCESS
}

internal data class RimePredictionDiagnostics(
    val submittedContext: String,
    val rawCount: Int,
    val convertedCount: Int,
    val filteredCount: Int,
    val elapsedMs: Long,
    val status: PredictionDebugStatus,
    val message: String? = null
)

internal data class RimePredictionBatch(
    val candidates: List<PredictionCandidate>,
    val diagnostics: RimePredictionDiagnostics
)

data class HybridPredictionBatch(
    val candidates: List<PredictionCandidate>,
    val debugInfo: PredictionDebugInfo
)

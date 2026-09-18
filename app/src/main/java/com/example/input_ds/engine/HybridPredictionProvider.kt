package com.example.input_ds.engine

import android.os.SystemClock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

/**
 * Produces one stable candidate list before scanning starts.
 * User history is ranked first, then the existing local model, then Rime fills gaps.
 */
class HybridPredictionProvider(
    private val local: PredictionProvider,
    private val rime: RimePredictionProvider,
    private val rimeTimeoutMs: Long = 350L
) : PredictionProvider, AutoCloseable {
    override suspend fun predict(context: String, limit: Int): List<PredictionCandidate> {
        return predictWithDebug(context, limit).candidates
    }

    suspend fun predictWithDebug(context: String, limit: Int): HybridPredictionBatch {
        if (context.isEmpty() || limit <= 0) {
            return HybridPredictionBatch(
                candidates = emptyList(),
                debugInfo = PredictionDebugInfo(
                    contextPreview = PredictionText.takeLastCodePoints(context, CONTEXT_PREVIEW_CODE_POINTS),
                    submittedRimeContext = "",
                    localCount = 0,
                    rimeRawCount = 0,
                    rimeConvertedCount = 0,
                    rimeFilteredCount = 0,
                    rimeMergedCount = 0,
                    mergedCount = 0,
                    elapsedMs = 0,
                    status = PredictionDebugStatus.NO_PREDICTIONS
                )
            )
        }

        val localCandidates = safePredict(local, context, limit * 2)
        val rimeStartedAt = SystemClock.elapsedRealtime()
        val rimeBatch = withTimeoutOrNull(rimeTimeoutMs) {
            rime.predictWithDebug(context, limit * 2)
        } ?: RimePredictionBatch(
            candidates = emptyList(),
            diagnostics = RimePredictionDiagnostics(
                submittedContext = PredictionText.takeLastCodePoints(context, 32),
                rawCount = -1,
                convertedCount = -1,
                filteredCount = -1,
                elapsedMs = SystemClock.elapsedRealtime() - rimeStartedAt,
                status = PredictionDebugStatus.RIME_QUERY_TIMEOUT,
                message = "混合层查询超过${rimeTimeoutMs}ms"
            )
        )
        val rimeCandidates = rimeBatch.candidates

        val sourcePriority = mapOf(
            PredictionSource.USER to 0,
            PredictionSource.LOCAL to 1,
            PredictionSource.RIME to 2
        )
        val seen = LinkedHashSet<String>()
        val merged = (localCandidates + rimeCandidates)
            .mapNotNull { candidate ->
                val suffix = PredictionText.removeCompleteContextPrefix(context, candidate.text)
                if (!PredictionText.isDisplayableSuffix(suffix)) return@mapNotNull null
                if (suffix == context || !seen.add(suffix)) return@mapNotNull null
                candidate.copy(text = suffix)
            }
            .sortedWith(
                compareBy<PredictionCandidate> { sourcePriority.getValue(it.source) }
                    .thenByDescending { it.score }
            )
            .take(limit)

        val rimeMergedCount = merged.count { it.source == PredictionSource.RIME }
        val diagnostics = rimeBatch.diagnostics
        val status = when {
            rimeMergedCount > 0 -> PredictionDebugStatus.RIME_MIXED_SUCCESS
            merged.isEmpty() && diagnostics.status == PredictionDebugStatus.RIME_NO_MATCH ->
                PredictionDebugStatus.NO_PREDICTIONS
            rimeCandidates.isNotEmpty() -> PredictionDebugStatus.LOCAL_ONLY
            else -> diagnostics.status
        }
        val resultMessage = buildList {
            diagnostics.message?.takeIf { it.isNotBlank() }?.let { add(it) }
            if (status == PredictionDebugStatus.NO_PREDICTIONS &&
                diagnostics.status == PredictionDebugStatus.RIME_NO_MATCH
            ) {
                add("Rime无匹配")
            }
            when {
                rimeCandidates.isNotEmpty() && rimeMergedCount == 0 ->
                    add("Rime候选与本地重复或未进入前${limit}项")
                localCandidates.isNotEmpty() && rimeMergedCount == 0 ->
                    add("本次仅使用本地预测")
                merged.isEmpty() -> add("本次无任何预测结果")
            }
        }.distinct().joinToString("；").takeIf { it.isNotEmpty() }

        return HybridPredictionBatch(
            candidates = merged,
            debugInfo = PredictionDebugInfo(
                contextPreview = PredictionText.takeLastCodePoints(context, CONTEXT_PREVIEW_CODE_POINTS),
                submittedRimeContext = diagnostics.submittedContext,
                localCount = localCandidates.size,
                rimeRawCount = diagnostics.rawCount,
                rimeConvertedCount = diagnostics.convertedCount,
                rimeFilteredCount = diagnostics.filteredCount,
                rimeMergedCount = rimeMergedCount,
                mergedCount = merged.size,
                elapsedMs = diagnostics.elapsedMs,
                status = status,
                message = resultMessage
            )
        )
    }

    private suspend fun safePredict(
        provider: PredictionProvider,
        context: String,
        limit: Int
    ): List<PredictionCandidate> = try {
        provider.predict(context, limit)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Throwable) {
        emptyList()
    }

    override fun close() {
        rime.close()
    }

    private companion object {
        const val CONTEXT_PREVIEW_CODE_POINTS = 12
    }
}

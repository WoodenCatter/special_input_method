package com.example.input_ds.engine

import com.example.input_ds.data.UserDictionary

/** Adapts the existing local prediction engine to the suffix-only contract. */
class LocalPredictionProvider(
    private val engine: PredictionEngine = PredictionEngine()
) : PredictionProvider {
    override suspend fun predict(context: String, limit: Int): List<PredictionCandidate> {
        if (context.isEmpty() || limit <= 0) return emptyList()

        val lastCodePoint = PredictionText.takeLastCodePoints(context, 1)
        if (!PredictionText.isDisplayableSuffix(lastCodePoint, maxCodePoints = 1)) return emptyList()
        val lookupContext = PredictionText.takeLastCodePoints(context, 2)
        return engine.predictNext(lookupContext)
            .mapIndexedNotNull { index, raw ->
                val suffix = PredictionText.removeLocalContextPrefix(context, raw)
                if (!PredictionText.isDisplayableSuffix(suffix)) return@mapIndexedNotNull null
                PredictionCandidate(
                    text = suffix,
                    source = if (UserDictionary.getScore(raw) > 0) {
                        PredictionSource.USER
                    } else {
                        PredictionSource.LOCAL
                    },
                    score = (limit - index).toDouble()
                )
            }
            .distinctBy { it.text }
            .take(limit)
    }
}

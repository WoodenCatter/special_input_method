package com.example.input_ds.engine

data class StablePredictionMergeResult(
    val candidates: List<PredictionCandidate>,
    val addedCount: Int
)

/** Keeps every published candidate in place and only appends new IDs/text at the tail. */
object StablePredictionMerge {
    fun append(
        existing: List<PredictionCandidate>,
        incoming: List<PredictionCandidate>,
        limit: Int,
        isValid: (PredictionCandidate) -> Boolean = { true }
    ): StablePredictionMergeResult {
        if (limit <= existing.size) {
            return StablePredictionMergeResult(existing.take(limit.coerceAtLeast(0)), 0)
        }
        val seenText = existing.mapTo(LinkedHashSet()) { it.text }
        val seenIds = existing.mapTo(LinkedHashSet()) { it.id }
        val additions = incoming.filter { candidate ->
            if (!isValid(candidate) || candidate.text in seenText || candidate.id in seenIds) {
                false
            } else {
                seenText.add(candidate.text)
                seenIds.add(candidate.id)
                true
            }
        }.take(limit - existing.size)
        return StablePredictionMergeResult(existing + additions, additions.size)
    }
}

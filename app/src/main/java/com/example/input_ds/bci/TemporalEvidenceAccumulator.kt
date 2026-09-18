package com.example.input_ds.bci

/** Support-window confidence, command lock and per-window Rest re-arm. */
class TemporalEvidenceAccumulator(
    private val classCount: Int,
    private val restClass: Int = 0,
    private val evidenceWindows: Int = AsyncWindowPolicy.EVIDENCE_WINDOWS,
    private val confidenceThreshold: Float = AsyncWindowPolicy.CONFIDENCE_THRESHOLD,
    private val supportRequired: Int = AsyncWindowPolicy.SUPPORT_REQUIRED,
    private val restResetRequired: Int = AsyncWindowPolicy.REST_RESET_REQUIRED
) {
    enum class Type { NO_COMMAND, COMMAND, LOCKED, REARMED }

    data class Result(
        val type: Type,
        val eventClass: Int?,
        val evidence: FloatArray,
        val candidateClass: Int,
        val support: Int,
        val queuedWindows: Int,
        val lockedClass: Int?,
        val restStreak: Int
    )

    private val probabilities = ArrayDeque<FloatArray>()
    private var lockedClass: Int? = null
    private var restStreak = 0

    init {
        require(classCount >= 2)
        require(restClass in 0 until classCount)
        require(evidenceWindows >= 1)
        require(confidenceThreshold in 0.5f..1f)
        require(supportRequired in 1..evidenceWindows)
        require(restResetRequired in 1..evidenceWindows)
    }

    val isLocked: Boolean get() = lockedClass != null

    fun reset() {
        probabilities.clear()
        lockedClass = null
        restStreak = 0
    }

    fun update(
        input: FloatArray,
        candidateValidator: (Int) -> Boolean = { true }
    ): Result {
        require(input.size == classCount && input.all { it.isFinite() && it >= 0f })
        val sum = input.sumOf(Float::toDouble)
        val normalized = if (sum > 0.0) {
            FloatArray(classCount) { (input[it] / sum).toFloat() }
        } else {
            FloatArray(classCount).also { it[restClass] = 1f }
        }
        probabilities.addLast(normalized)
        while (probabilities.size > evidenceWindows) probabilities.removeFirst()

        val labels = probabilities.map { values ->
            values.indices.maxByOrNull { values[it] } ?: restClass
        }
        val supportByClass = IntArray(classCount)
        val evidence = FloatArray(classCount)
        probabilities.forEachIndexed { index, values ->
            val label = labels[index]
            supportByClass[label]++
            evidence[label] += values[label]
        }
        evidence.indices.forEach { index ->
            if (supportByClass[index] > 0) evidence[index] /= supportByClass[index]
        }
        var candidate = restClass
        for (index in 0 until classCount) {
            if (
                supportByClass[index] > supportByClass[candidate] ||
                supportByClass[index] == supportByClass[candidate] &&
                evidence[index] > evidence[candidate]
            ) {
                candidate = index
            }
        }
        val support = supportByClass[candidate]
        val currentWinner = labels.last()
        val currentRestConfident = currentWinner == restClass &&
            normalized[restClass] >= confidenceThreshold

        if (lockedClass != null) {
            if (currentRestConfident) {
                restStreak++
            } else {
                restStreak = 0
            }
            if (restStreak >= restResetRequired) {
                lockedClass = null
                restStreak = 0
                return result(Type.REARMED, null, evidence, candidate, support)
            }
            return result(Type.LOCKED, null, evidence, candidate, support)
        }

        if (
            probabilities.size >= supportRequired &&
            candidate != restClass &&
            evidence[candidate] >= confidenceThreshold &&
            support >= supportRequired &&
            candidateValidator(candidate)
        ) {
            lockedClass = candidate
            return result(Type.COMMAND, candidate, evidence, candidate, support)
        }
        return result(Type.NO_COMMAND, null, evidence, candidate, support)
    }

    private fun result(
        type: Type,
        eventClass: Int?,
        evidence: FloatArray,
        candidate: Int,
        support: Int
    ) = Result(
        type = type,
        eventClass = eventClass,
        evidence = evidence,
        candidateClass = candidate,
        support = support,
        queuedWindows = probabilities.size,
        lockedClass = lockedClass,
        restStreak = restStreak
    )
}

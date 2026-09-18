package com.example.input_ds.bci

import com.example.input_ds.personalization.ClassificationProtocol
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

data class SixMotionFeatures(
    val activityStdUv: Float,
    val robustSpanUv: Float,
    val directionScore: Float,
    val motionScores: Map<Int, Float>,
    val motionAllowedClass: Int?,
    val motionFusedProbability: Float,
    val sequencePhaseClass: Int?
)

data class SixDetectorSnapshot(
    val state: SixActionDetector.State,
    val activeClass: Int?,
    val candidateClass: Int?,
    val candidateHits: Int,
    val candidateAge: Int,
    val releaseHits: Int,
    val refractoryUntilMs: Long,
    val sequenceEndpointSeen: Boolean
)

data class SixDetectorResult(
    val eventClass: Int?,
    val controlProbabilities: FloatArray,
    val features: SixMotionFeatures,
    val singleWindowClasses: Set<Int>,
    val strongBite: Boolean,
    val snapshot: SixDetectorSnapshot
)

/**
 * Six-action asynchronous detector from the validated six_action protocol.
 * Model probabilities and the four motion templates are fused into one motion
 * candidate before the event state machine is evaluated.
 */
class SixActionDetector(
    private val calibration: AsyncCalibration,
    private val sampleRateHz: Int = 500,
    private val refractoryMs: Long = 250L,
    private val sameActionIntervalMs: Long = 750L
) {
    enum class State { REST, POSSIBLE_ACTION, IN_ACTION, REFRACTORY }

    private var state = State.REST
    private var candidateClass = -1
    private var candidateAge = 0
    private var candidateHits = 0
    private var activeClass = -1
    private var releaseHits = 0
    private var refractoryUntil = 0L
    private var sequenceEndpointSeen = false
    private val lastEventAt = LongArray(CLASS_COUNT) { Long.MIN_VALUE }
    private val returnedSinceLastEvent = BooleanArray(CLASS_COUNT)

    init {
        require(calibration.protocol == ClassificationProtocol.SIX_ACTION) {
            "SixActionDetector requires six_action calibration"
        }
        require(calibration.isSessionCalibrated) { "Six-action control requires Session calibration" }
        require(calibration.motionTemplates.keys == MOTION_CLASSES.toSet()) { "Six-action calibration templates are incomplete" }
        require(calibration.motionTemplates.values.all { it.size == TEMPLATE_POINTS && it.all(Float::isFinite) }) {
            "Six-action calibration templates are invalid"
        }
        require(calibration.sequenceMinSpanUv.isFinite()) { "Six-action span threshold is missing" }
    }

    fun reset() {
        state = State.REST
        candidateClass = -1
        candidateAge = 0
        candidateHits = 0
        activeClass = -1
        releaseHits = 0
        refractoryUntil = 0L
        sequenceEndpointSeen = false
        lastEventAt.fill(Long.MIN_VALUE)
        returnedSinceLastEvent.fill(false)
    }

    fun update(
        probabilities: FloatArray,
        leftRaw: FloatArray,
        rightRaw: FloatArray,
        timestampMs: Long
    ): SixDetectorResult {
        require(probabilities.size == CLASS_COUNT && probabilities.all { it.isFinite() && it >= 0f })
        require(leftRaw.isNotEmpty() && leftRaw.size == rightRaw.size)
        val gated = gateMotion(probabilities, leftRaw, rightRaw, calibration)
        val control = gated.first
        val features = gated.second
        val immediate = mutableSetOf<Int>()
        features.motionAllowedClass?.let { allowed ->
            if (control[allowed] >= MOTION_ENTER_THRESHOLD &&
                (allowed in SEQUENCE_CLASSES || probabilities.indices.maxByOrNull { probabilities[it] } == allowed)
            ) {
                immediate += allowed
            }
        }
        val strongBite = probabilities[BITE_CLASS] >= STRONG_BITE_PROBABILITY &&
            features.activityStdUv >= calibration.biteMinStdUv
        if (strongBite) {
            immediate.removeAll(MOTION_CLASSES.toSet())
            immediate += BITE_CLASS
        }

        if (state == State.REFRACTORY && timestampMs >= refractoryUntil) state = State.REST
        var event: Int? = null
        when (state) {
            State.REFRACTORY -> Unit
            State.IN_ACTION -> updateActive(control, features, timestampMs)
            State.REST, State.POSSIBLE_ACTION -> {
                val instantClass = when {
                    strongBite -> BITE_CLASS
                    else -> immediate.maxByOrNull { priority(it) }
                }
                if (instantClass != null && canEmit(instantClass, timestampMs)) {
                    event = emit(instantClass, timestampMs)
                } else {
                    val possible = bestEnteringClass(control)
                    if (possible == null) {
                        if (state == State.POSSIBLE_ACTION && ++candidateAge >= CONFIRMATION_WINDOWS) clearCandidate()
                    } else if (candidateClass != possible) {
                        candidateClass = possible
                        candidateAge = 1
                        candidateHits = 1
                        state = State.POSSIBLE_ACTION
                    } else {
                        candidateAge++
                        candidateHits++
                        if (candidateHits >= REQUIRED_HITS && canEmit(possible, timestampMs)) {
                            event = emit(possible, timestampMs)
                        } else if (candidateAge >= CONFIRMATION_WINDOWS) {
                            clearCandidate()
                        }
                    }
                }
            }
        }
        return SixDetectorResult(
            eventClass = event,
            controlProbabilities = control,
            features = features,
            singleWindowClasses = immediate,
            strongBite = strongBite,
            snapshot = snapshot()
        )
    }

    fun snapshot() = SixDetectorSnapshot(
        state = state,
        activeClass = activeClass.takeIf { it >= 0 },
        candidateClass = candidateClass.takeIf { it >= 0 },
        candidateHits = candidateHits,
        candidateAge = candidateAge,
        releaseHits = releaseHits,
        refractoryUntilMs = refractoryUntil,
        sequenceEndpointSeen = sequenceEndpointSeen
    )

    private fun updateActive(
        control: FloatArray,
        features: SixMotionFeatures,
        timestampMs: Long
    ) {
        when (activeClass) {
            LEFT_RIGHT_CLASS, RIGHT_LEFT_CLASS -> {
                val endpointClass = if (activeClass == LEFT_RIGHT_CLASS) RIGHT_CLASS else LEFT_CLASS
                val returnClass = if (activeClass == LEFT_RIGHT_CLASS) LEFT_CLASS else RIGHT_CLASS
                if (!sequenceEndpointSeen && features.sequencePhaseClass == endpointClass) {
                    sequenceEndpointSeen = true
                } else if (sequenceEndpointSeen && features.sequencePhaseClass == returnClass) {
                    enterRefractory(timestampMs, sequenceResidualRefractoryMs())
                } else if (control[activeClass] <= EXIT_THRESHOLD && control[REST_CLASS] >= REST_RELEASE_THRESHOLD) {
                    enterRefractory(timestampMs, refractoryMs)
                }
            }
            LEFT_CLASS, RIGHT_CLASS -> {
                val returned = activeClass == LEFT_CLASS && features.sequencePhaseClass == RIGHT_CLASS ||
                    activeClass == RIGHT_CLASS && features.sequencePhaseClass == LEFT_CLASS
                if (returned) {
                    returnedSinceLastEvent[activeClass] = true
                    enterRefractory(timestampMs, refractoryMs)
                } else {
                    if (control[activeClass] <= EXIT_THRESHOLD) releaseHits++ else releaseHits = 0
                    if (releaseHits >= RELEASE_WINDOWS || control[REST_CLASS] >= REST_RELEASE_THRESHOLD) {
                        enterRefractory(timestampMs, refractoryMs)
                    }
                }
            }
            else -> {
                if (control.getOrElse(activeClass) { 0f } <= EXIT_THRESHOLD) releaseHits++ else releaseHits = 0
                if (releaseHits >= RELEASE_WINDOWS || control[REST_CLASS] >= REST_RELEASE_THRESHOLD) {
                    enterRefractory(timestampMs, refractoryMs)
                }
            }
        }
    }

    private fun bestEnteringClass(control: FloatArray): Int? {
        if (control[BITE_CLASS] >= BITE_ENTER_THRESHOLD) return BITE_CLASS
        return MOTION_CLASSES
            .filter { control[it] >= MOTION_ENTER_THRESHOLD }
            .maxByOrNull { control[it] }
    }

    private fun canEmit(classId: Int, timestampMs: Long): Boolean {
        val previous = lastEventAt[classId]
        return returnedSinceLastEvent[classId] || previous == Long.MIN_VALUE || timestampMs - previous >= sameActionIntervalMs
    }

    private fun emit(classId: Int, timestampMs: Long): Int {
        activeClass = classId
        state = State.IN_ACTION
        releaseHits = 0
        clearCandidate(keepState = true)
        lastEventAt[classId] = timestampMs
        returnedSinceLastEvent[classId] = false
        // Endpoint/return ordering starts after the event window. Treating the
        // event window itself as an endpoint makes overlap position decide the
        // lock state and can release a sequence on its first tail window.
        sequenceEndpointSeen = false
        return classId
    }

    private fun enterRefractory(timestampMs: Long, durationMs: Long) {
        state = State.REFRACTORY
        refractoryUntil = timestampMs + durationMs
        activeClass = -1
        releaseHits = 0
        sequenceEndpointSeen = false
        clearCandidate(keepState = true)
    }

    private fun clearCandidate(keepState: Boolean = false) {
        candidateClass = -1
        candidateAge = 0
        candidateHits = 0
        if (!keepState) state = State.REST
    }

    private fun sequenceResidualRefractoryMs(): Long {
        val strideMs = calibration.windowPoints * 250L / sampleRateHz
        return max(refractoryMs, ceil(strideMs * 1.25).toLong())
    }

    private fun priority(classId: Int): Int = when (classId) {
        BITE_CLASS -> 3
        LEFT_RIGHT_CLASS, RIGHT_LEFT_CLASS -> 2
        else -> 1
    }

    companion object {
        const val REST_CLASS = 0
        const val LEFT_CLASS = 1
        const val RIGHT_CLASS = 2
        const val BITE_CLASS = 3
        const val LEFT_RIGHT_CLASS = 4
        const val RIGHT_LEFT_CLASS = 5
        private const val CLASS_COUNT = 6
        private const val TEMPLATE_POINTS = 64
        private val MOTION_CLASSES = intArrayOf(LEFT_CLASS, RIGHT_CLASS, LEFT_RIGHT_CLASS, RIGHT_LEFT_CLASS)
        private val SEQUENCE_CLASSES = setOf(LEFT_RIGHT_CLASS, RIGHT_LEFT_CLASS)
        private const val TEMPLATE_MIN_CORRELATION = 0.55f
        private const val TEMPLATE_MIN_MARGIN = 0.05f
        private const val TEMPLATE_MAX_SHIFT_FRACTION = 0.25f
        private const val FUSION_WEIGHT = 0.5f
        private const val STRONG_BITE_PROBABILITY = 0.50f
        private const val BITE_ENTER_THRESHOLD = 0.75f
        private const val MOTION_ENTER_THRESHOLD = 0.60f
        private const val EXIT_THRESHOLD = 0.30f
        private const val REST_RELEASE_THRESHOLD = 0.70f
        private const val CONFIRMATION_WINDOWS = 3
        private const val REQUIRED_HITS = 2
        private const val RELEASE_WINDOWS = 1
        private const val DIRECTION_PHASE_SCORE = 0.50f

        fun gateMotion(
            probabilities: FloatArray,
            left: FloatArray,
            right: FloatArray,
            calibration: AsyncCalibration
        ): Pair<FloatArray, SixMotionFeatures> {
            require(probabilities.size == CLASS_COUNT && probabilities.all { it.isFinite() && it >= 0f })
            val size = minOf(left.size, right.size)
            require(size > 1)
            val difference = FloatArray(size) { left[it] - right[it] }
            require(difference.all(Float::isFinite)) { "Six-action window contains NaN/Inf" }
            val activity = standardDeviation(difference)
            val span = robustSpan(difference)
            val normalized = normalize(resample(difference, TEMPLATE_POINTS))
            val scores = MOTION_CLASSES.associateWith { classId ->
                maxShiftedCorrelation(normalized, requireNotNull(calibration.motionTemplates[classId]))
            }
            var ranked = MOTION_CLASSES.sortedByDescending { scores.getValue(it) }
            if (ranked.first() in SEQUENCE_CLASSES && span < calibration.sequenceMinSpanUv) {
                ranked = listOf(LEFT_CLASS, RIGHT_CLASS).sortedByDescending { scores.getValue(it) }
            }
            val winner = ranked.first()
            val winnerScore = scores.getValue(winner)
            val runnerUpScore = ranked.getOrNull(1)?.let(scores::getValue) ?: Float.NEGATIVE_INFINITY
            val amplitudeThreshold = if (winner in SEQUENCE_CLASSES) {
                calibration.sequenceMinStdUv
            } else {
                calibration.directionMinStdUv
            }
            val allowed = winner.takeIf {
                activity >= amplitudeThreshold && winnerScore >= TEMPLATE_MIN_CORRELATION &&
                    winnerScore - runnerUpScore >= TEMPLATE_MIN_MARGIN
            }
            val control = probabilities.copyOf()
            MOTION_CLASSES.forEach { control[it] = 0f }
            val fused = allowed?.let { classId ->
                FUSION_WEIGHT * probabilities[classId] + FUSION_WEIGHT * max(0f, scores.getValue(classId))
            } ?: 0f
            if (allowed != null) control[allowed] = fused
            val directionScore = directionScore(difference, activity)
            val phase = when {
                activity < calibration.directionMinStdUv -> null
                directionScore <= -DIRECTION_PHASE_SCORE -> LEFT_CLASS
                directionScore >= DIRECTION_PHASE_SCORE -> RIGHT_CLASS
                else -> null
            }
            return control to SixMotionFeatures(
                activityStdUv = activity,
                robustSpanUv = span,
                directionScore = directionScore,
                motionScores = scores,
                motionAllowedClass = allowed,
                motionFusedProbability = fused,
                sequencePhaseClass = phase
            )
        }

        internal fun maxShiftedCorrelation(window: FloatArray, template: FloatArray): Float {
            require(window.size == template.size && window.isNotEmpty())
            val maxShift = floor(window.size * TEMPLATE_MAX_SHIFT_FRACTION).toInt()
            var best = -1f
            for (shift in -maxShift..maxShift) {
                val windowStart = max(0, shift)
                val templateStart = max(0, -shift)
                val count = window.size - abs(shift)
                var windowMean = 0.0
                var templateMean = 0.0
                for (offset in 0 until count) {
                    windowMean += window[windowStart + offset]
                    templateMean += template[templateStart + offset]
                }
                windowMean /= count
                templateMean /= count
                var covariance = 0.0
                var windowNorm = 0.0
                var templateNorm = 0.0
                for (offset in 0 until count) {
                    val a = window[windowStart + offset] - windowMean
                    val b = template[templateStart + offset] - templateMean
                    covariance += a * b
                    windowNorm += a * a
                    templateNorm += b * b
                }
                val denominator = sqrt(windowNorm * templateNorm)
                if (denominator > 1e-12) best = max(best, (covariance / denominator).toFloat())
            }
            return best
        }

        private fun directionScore(difference: FloatArray, activity: Float): Float {
            if (activity <= 1e-6f || difference.size < 20) return 0f
            fun segmentMean(fromFraction: Float, toFraction: Float): Double {
                val from = (difference.size * fromFraction).toInt().coerceIn(0, difference.lastIndex)
                val to = (difference.size * toFraction).toInt().coerceIn(from + 1, difference.size)
                return (from until to).sumOf { difference[it].toDouble() } / (to - from)
            }
            return ((segmentMean(0.40f, 0.70f) - segmentMean(0.05f, 0.30f)) / activity).toFloat()
        }

        private fun standardDeviation(values: FloatArray): Float {
            val mean = values.sumOf(Float::toDouble) / values.size
            val variance = values.sumOf { value ->
                val centered = value - mean
                centered * centered
            } / values.size
            return sqrt(variance).toFloat()
        }

        private fun robustSpan(values: FloatArray): Float {
            val sorted = values.sorted()
            fun percentile(q: Float): Float {
                val position = (sorted.size - 1) * q
                val low = floor(position).toInt()
                val high = ceil(position).toInt()
                val fraction = position - low
                return sorted[low] * (1f - fraction) + sorted[high] * fraction
            }
            return percentile(0.95f) - percentile(0.05f)
        }

        private fun resample(source: FloatArray, targetPoints: Int): FloatArray = FloatArray(targetPoints) { index ->
            val position = index.toDouble() * source.lastIndex / (targetPoints - 1)
            val lower = floor(position).toInt()
            val upper = ceil(position).toInt().coerceAtMost(source.lastIndex)
            val fraction = (position - lower).toFloat()
            source[lower] * (1f - fraction) + source[upper] * fraction
        }

        private fun normalize(values: FloatArray): FloatArray {
            val mean = values.sumOf(Float::toDouble) / values.size
            val centered = FloatArray(values.size) { values[it] - mean.toFloat() }
            val norm = sqrt(centered.sumOf { it.toDouble() * it }).toFloat()
            return if (norm > 1e-8f) FloatArray(values.size) { centered[it] / norm } else centered
        }
    }
}

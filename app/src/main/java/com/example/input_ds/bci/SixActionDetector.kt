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
 * Six-action asynchronous detector. The model owns the semantic class; physical
 * amplitude/span checks only reject implausible motion, while the shared V1.0
 * temporal accumulator owns confirmation, one-shot locking and Rest re-arming.
 */
class SixActionDetector(
    private val calibration: AsyncCalibration,
    @Suppress("UNUSED_PARAMETER") private val sampleRateHz: Int = 500,
    @Suppress("UNUSED_PARAMETER") private val refractoryMs: Long = 250L,
    @Suppress("UNUSED_PARAMETER") private val sameActionIntervalMs: Long = 750L
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
    private val temporalEvidence = TemporalEvidenceAccumulator(CLASS_COUNT)
    private val physicalEvidence = ArrayDeque<Set<Int>>()

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
        temporalEvidence.reset()
        physicalEvidence.clear()
    }

    fun update(
        probabilities: FloatArray,
        leftRaw: FloatArray,
        rightRaw: FloatArray,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long
    ): SixDetectorResult {
        require(probabilities.size == CLASS_COUNT && probabilities.all { it.isFinite() && it >= 0f })
        require(leftRaw.isNotEmpty() && leftRaw.size == rightRaw.size)
        val gated = gateMotion(probabilities, leftRaw, rightRaw, calibration)
        val control = gated.first
        val features = gated.second
        val strongBite = probabilities[BITE_CLASS] >= STRONG_BITE_PROBABILITY &&
            features.activityStdUv >= calibration.biteMinStdUv
        val currentPhysicalClasses = buildSet {
            features.motionAllowedClass?.let(::add)
            if (features.activityStdUv >= calibration.biteMinStdUv) add(BITE_CLASS)
        }
        physicalEvidence.addLast(currentPhysicalClasses)
        while (physicalEvidence.size > AsyncWindowPolicy.EVIDENCE_WINDOWS) {
            physicalEvidence.removeFirst()
        }
        val temporal = temporalEvidence.update(control) { candidate ->
            physicalEvidence.count { candidate in it } >=
                AsyncWindowPolicy.PHYSICAL_SUPPORT_REQUIRED
        }
        activeClass = temporal.lockedClass ?: -1
        candidateClass = temporal.candidateClass.takeIf { it != REST_CLASS } ?: -1
        candidateHits = temporal.support
        candidateAge = temporal.queuedWindows
        releaseHits = temporal.restStreak
        refractoryUntil = 0L
        sequenceEndpointSeen = false
        state = when {
            temporal.lockedClass != null -> State.IN_ACTION
            temporal.candidateClass != REST_CLASS -> State.POSSIBLE_ACTION
            else -> State.REST
        }
        val singleWindowClasses = buildSet {
            val winner = probabilities.indices.maxByOrNull { probabilities[it] } ?: REST_CLASS
            if (winner != REST_CLASS && winner in currentPhysicalClasses) add(winner)
        }
        return SixDetectorResult(
            eventClass = temporal.eventClass,
            controlProbabilities = temporal.evidence,
            features = features,
            singleWindowClasses = singleWindowClasses,
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
        private const val TEMPLATE_MAX_SHIFT_FRACTION = 0.25f
        private const val STRONG_BITE_PROBABILITY = 0.50f
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
            val winner = MOTION_CLASSES.maxByOrNull { probabilities[it] } ?: LEFT_CLASS
            val amplitudeThreshold = if (winner in SEQUENCE_CLASSES) {
                calibration.sequenceMinStdUv
            } else {
                calibration.directionMinStdUv
            }
            val allowed = winner.takeIf {
                activity >= amplitudeThreshold &&
                    (winner !in SEQUENCE_CLASSES || span >= calibration.sequenceMinSpanUv)
            }
            val fused = allowed?.let { classId -> probabilities[classId] } ?: 0f
            val directionScore = directionScore(difference, activity)
            val phase = when {
                activity < calibration.directionMinStdUv -> null
                directionScore <= -DIRECTION_PHASE_SCORE -> LEFT_CLASS
                directionScore >= DIRECTION_PHASE_SCORE -> RIGHT_CLASS
                else -> null
            }
            return probabilities.copyOf() to SixMotionFeatures(
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

package com.example.input_ds.bci

import com.example.input_ds.personalization.ClassificationProtocol
import kotlin.math.abs
import kotlin.math.sqrt

data class AsyncCalibration(
    val windowPoints: Int,
    val directionMinStdUv: Float,
    val biteMinStdUv: Float,
    val source: String,
    val isSessionCalibrated: Boolean = true,
    val protocol: ClassificationProtocol = ClassificationProtocol.FOUR_CLASS,
    val sequenceMinStdUv: Float = directionMinStdUv,
    val sequenceMinSpanUv: Float = Float.POSITIVE_INFINITY,
    val motionTemplates: Map<Int, FloatArray> = emptyMap(),
    val sessionId: String? = null
)

data class WindowFeatures(
    val activityStdUv: Float,
    val directionScore: Float,
    val allowedDirectionClass: Int?
)

data class DetectorResult(
    val eventClass: Int?,
    val controlProbabilities: FloatArray,
    val features: WindowFeatures,
    val state: FourClassActionDetector.State,
    val strongBite: Boolean,
    val strongDirection: Boolean
)

class FourClassActionDetector(
    private val calibration: AsyncCalibration,
    @Suppress("UNUSED_PARAMETER") private val refractoryMs: Long = 250L,
    @Suppress("UNUSED_PARAMETER") private val sameDirectionIntervalMs: Long = 1_000L
) {
    enum class State { REST, POSSIBLE_ACTION, IN_ACTION, REFRACTORY }

    private var state = State.REST
    private val temporalEvidence = TemporalEvidenceAccumulator(CLASS_COUNT)
    private val directionPhysicalEvidence = ArrayDeque<Boolean>()
    private val bitePhysicalEvidence = ArrayDeque<Boolean>()

    fun reset() {
        state = State.REST
        temporalEvidence.reset()
        directionPhysicalEvidence.clear()
        bitePhysicalEvidence.clear()
    }

    fun update(
        probabilities: FloatArray,
        leftRaw: FloatArray,
        rightRaw: FloatArray,
        @Suppress("UNUSED_PARAMETER") timestampMs: Long
    ): DetectorResult {
        require(probabilities.size >= CLASS_COUNT)
        val features = calculateFeatures(leftRaw, rightRaw, calibration.directionMinStdUv)
        val hasDirectionEvidence = features.allowedDirectionClass != null
        // The differential feature validates that an eye movement happened.
        // Its sign is phase-dependent in an asynchronous rolling window, so
        // the model—not the sign heuristic—owns the left/right class decision.
        directionPhysicalEvidence.pushEvidence(hasDirectionEvidence)
        bitePhysicalEvidence.pushEvidence(features.activityStdUv >= calibration.biteMinStdUv)

        val strongBite = probabilities[BITE_CLASS] >= STRONG_BITE_PROBABILITY &&
            features.activityStdUv >= calibration.biteMinStdUv
        val modelDirection = if (probabilities[LEFT_CLASS] >= probabilities[RIGHT_CLASS]) {
            LEFT_CLASS
        } else {
            RIGHT_CLASS
        }
        val strongDirection = hasDirectionEvidence &&
            probabilities[modelDirection] >= STRONG_DIRECTION_PROBABILITY &&
            abs(features.directionScore) >= STRONG_DIRECTION_SCORE &&
            features.activityStdUv >= calibration.directionMinStdUv

        val temporal = temporalEvidence.update(probabilities) { candidate ->
            when (candidate) {
                LEFT_CLASS, RIGHT_CLASS -> directionPhysicalEvidence.count { it } >=
                    AsyncWindowPolicy.PHYSICAL_SUPPORT_REQUIRED
                BITE_CLASS -> bitePhysicalEvidence.count { it } >=
                    AsyncWindowPolicy.PHYSICAL_SUPPORT_REQUIRED
                else -> false
            }
        }
        state = when {
            temporal.lockedClass != null -> State.IN_ACTION
            temporal.candidateClass != REST_CLASS -> State.POSSIBLE_ACTION
            else -> State.REST
        }
        return DetectorResult(
            temporal.eventClass,
            temporal.evidence,
            features,
            state,
            strongBite,
            strongDirection
        )
    }

    companion object {
        const val REST_CLASS = 0
        const val BITE_CLASS = 1
        const val LEFT_CLASS = 2
        const val RIGHT_CLASS = 3
        private const val CLASS_COUNT = 4
        private const val STRONG_DIRECTION_PROBABILITY = 0.94f
        private const val STRONG_DIRECTION_SCORE = 0.80f
        private const val BASE_DIRECTION_SCORE = 0.50f
        private const val STRONG_BITE_PROBABILITY = 0.90f

        fun calculateFeatures(
            left: FloatArray,
            right: FloatArray,
            directionMinStdUv: Float
        ): WindowFeatures {
            val size = minOf(left.size, right.size)
            if (size < 20) return WindowFeatures(0f, 0f, null)
            val diff = FloatArray(size) { left[it] - right[it] }
            val mean = diff.sumOf { it.toDouble() } / size
            val variance = diff.sumOf { value ->
                val centered = value - mean
                centered * centered
            } / size
            val std = sqrt(variance).toFloat()
            if (std <= 1e-6f) return WindowFeatures(std, 0f, null)
            fun segmentMean(fromFraction: Float, toFraction: Float): Double {
                val from = (size * fromFraction).toInt().coerceIn(0, size - 1)
                val to = (size * toFraction).toInt().coerceIn(from + 1, size)
                var sum = 0.0
                for (index in from until to) sum += diff[index]
                return sum / (to - from)
            }
            val score = ((segmentMean(0.40f, 0.70f) - segmentMean(0.05f, 0.30f)) / std).toFloat()
            val allowed = when {
                std < directionMinStdUv -> null
                score <= -BASE_DIRECTION_SCORE -> LEFT_CLASS
                score >= BASE_DIRECTION_SCORE -> RIGHT_CLASS
                else -> null
            }
            return WindowFeatures(std, score, allowed)
        }
    }

    private fun ArrayDeque<Boolean>.pushEvidence(value: Boolean) {
        addLast(value)
        while (size > AsyncWindowPolicy.EVIDENCE_WINDOWS) removeFirst()
    }
}

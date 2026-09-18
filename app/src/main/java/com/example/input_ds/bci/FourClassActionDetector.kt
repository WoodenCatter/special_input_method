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
    private val refractoryMs: Long = 250L,
    private val sameDirectionIntervalMs: Long = 1_000L
) {
    enum class State { REST, POSSIBLE_ACTION, IN_ACTION, REFRACTORY }

    private var state = State.REST
    private var candidateClass = -1
    private var candidateAge = 0
    private var candidateHits = 0
    private var activeClass = -1
    private var releaseHits = 0
    private var refractoryUntil = 0L
    private val lastDirectionEventAt = LongArray(4) { Long.MIN_VALUE }
    private var activeDirectionReturned = false

    fun reset() {
        state = State.REST
        candidateClass = -1
        candidateAge = 0
        candidateHits = 0
        activeClass = -1
        releaseHits = 0
        refractoryUntil = 0L
        activeDirectionReturned = false
        lastDirectionEventAt.fill(Long.MIN_VALUE)
    }

    fun update(
        probabilities: FloatArray,
        leftRaw: FloatArray,
        rightRaw: FloatArray,
        timestampMs: Long
    ): DetectorResult {
        require(probabilities.size >= CLASS_COUNT)
        val features = calculateFeatures(leftRaw, rightRaw, calibration.directionMinStdUv)
        val control = probabilities.copyOf()
        when (features.allowedDirectionClass) {
            LEFT_CLASS -> control[RIGHT_CLASS] = 0f
            RIGHT_CLASS -> control[LEFT_CLASS] = 0f
            else -> {
                control[LEFT_CLASS] = 0f
                control[RIGHT_CLASS] = 0f
            }
        }

        val strongBite = probabilities[BITE_CLASS] >= STRONG_BITE_PROBABILITY &&
            features.activityStdUv >= calibration.biteMinStdUv
        val allowedDirection = features.allowedDirectionClass
        val strongDirection = allowedDirection != null &&
            probabilities[allowedDirection] >= STRONG_DIRECTION_PROBABILITY &&
            abs(features.directionScore) >= STRONG_DIRECTION_SCORE &&
            features.activityStdUv >= calibration.directionMinStdUv

        if (state == State.REFRACTORY && timestampMs >= refractoryUntil) {
            state = State.REST
        }
        var event: Int? = null
        when (state) {
            State.REFRACTORY -> {
                // 不应期尚未结束。
            }
            State.IN_ACTION -> {
                val returned = activeClass == LEFT_CLASS &&
                    features.directionScore >= BASE_DIRECTION_SCORE ||
                    activeClass == RIGHT_CLASS && features.directionScore <= -BASE_DIRECTION_SCORE
                if (returned) {
                    activeDirectionReturned = true
                    enterRefractory(timestampMs)
                } else {
                    if (control.getOrElse(activeClass) { 0f } <= EXIT_THRESHOLD) releaseHits++
                    else releaseHits = 0
                    if (releaseHits >= RELEASE_WINDOWS || probabilities[REST_CLASS] >= REST_RELEASE_THRESHOLD) {
                        enterRefractory(timestampMs)
                    }
                }
            }
            State.REST, State.POSSIBLE_ACTION -> {
                val immediate = when {
                    strongBite -> BITE_CLASS
                    strongDirection -> allowedDirection
                    else -> null
                }
                if (immediate != null && canEmit(immediate, timestampMs)) {
                    event = emit(immediate, timestampMs)
                } else {
                    val possible = bestEnteringClass(control)
                    if (possible == null) {
                        if (state == State.POSSIBLE_ACTION) {
                            candidateAge++
                            if (candidateAge >= CONFIRMATION_WINDOWS) clearCandidate()
                        }
                    } else {
                        if (candidateClass != possible) {
                            candidateClass = possible
                            candidateAge = 1
                            candidateHits = 1
                            state = State.POSSIBLE_ACTION
                        } else {
                            candidateAge++
                            candidateHits++
                        }
                        if (candidateHits >= REQUIRED_HITS && canEmit(possible, timestampMs)) {
                            event = emit(possible, timestampMs)
                        } else if (candidateAge >= CONFIRMATION_WINDOWS) {
                            clearCandidate()
                        }
                    }
                }
            }
        }
        return DetectorResult(event, control, features, state, strongBite, strongDirection)
    }

    private fun bestEnteringClass(control: FloatArray): Int? {
        // 咬牙证据优先接管尚未确认的方向候选。
        if (control[BITE_CLASS] >= BITE_ENTER_THRESHOLD) return BITE_CLASS
        val candidates = buildList {
            if (control[LEFT_CLASS] >= DIRECTION_ENTER_THRESHOLD) add(LEFT_CLASS)
            if (control[RIGHT_CLASS] >= DIRECTION_ENTER_THRESHOLD) add(RIGHT_CLASS)
        }
        return candidates.maxByOrNull { control[it] }
    }

    private fun canEmit(classId: Int, timestampMs: Long): Boolean {
        if (classId !in LEFT_CLASS..RIGHT_CLASS) return true
        if (activeDirectionReturned) return true
        val previous = lastDirectionEventAt[classId]
        return previous == Long.MIN_VALUE || timestampMs - previous >= sameDirectionIntervalMs
    }

    private fun emit(classId: Int, timestampMs: Long): Int {
        activeClass = classId
        state = State.IN_ACTION
        releaseHits = 0
        clearCandidate(keepState = true)
        if (classId in LEFT_CLASS..RIGHT_CLASS) lastDirectionEventAt[classId] = timestampMs
        activeDirectionReturned = false
        return classId
    }

    private fun enterRefractory(timestampMs: Long) {
        state = State.REFRACTORY
        refractoryUntil = timestampMs + refractoryMs
        activeClass = -1
        releaseHits = 0
        clearCandidate(keepState = true)
    }

    private fun clearCandidate(keepState: Boolean = false) {
        candidateClass = -1
        candidateAge = 0
        candidateHits = 0
        if (!keepState) state = State.REST
    }

    companion object {
        const val REST_CLASS = 0
        const val BITE_CLASS = 1
        const val LEFT_CLASS = 2
        const val RIGHT_CLASS = 3
        private const val CLASS_COUNT = 4
        private const val BITE_ENTER_THRESHOLD = 0.75f
        private const val DIRECTION_ENTER_THRESHOLD = 0.90f
        private const val STRONG_DIRECTION_PROBABILITY = 0.94f
        private const val STRONG_DIRECTION_SCORE = 0.80f
        private const val BASE_DIRECTION_SCORE = 0.50f
        private const val STRONG_BITE_PROBABILITY = 0.50f
        private const val EXIT_THRESHOLD = 0.30f
        private const val REST_RELEASE_THRESHOLD = 0.70f
        private const val CONFIRMATION_WINDOWS = 3
        private const val REQUIRED_HITS = 2
        private const val RELEASE_WINDOWS = 2

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
}

package com.example.input_ds.bci

import com.example.input_ds.personalization.ClassificationProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

class SixActionDetectorTest {
    private val leftWave = wave(1)
    private val rightWave = wave(2)
    private val leftRightWave = wave(4)
    private val rightLeftWave = wave(5)

    @Test
    fun sixThresholds_scaleWithSessionAmplitudeAndSpan() {
        val low = AsyncCalibrationManager.calculateSixThresholds(10f, 50f, 40f, 45f, 100f, 180f, 500)
        val high = AsyncCalibrationManager.calculateSixThresholds(30f, 150f, 120f, 130f, 300f, 520f, 500)

        assertTrue(high.directionMinStdUv > low.directionMinStdUv)
        assertTrue(high.sequenceMinStdUv > low.sequenceMinStdUv)
        assertTrue(high.biteMinStdUv > low.biteMinStdUv)
        assertTrue(high.sequenceMinSpanUv > low.sequenceMinSpanUv)
    }

    @Test
    fun shiftedTemplateCorrelationToleratesQuarterWindowOffset() {
        val template = normalize(FloatArray(64) { if (it in 12..25) 1f else -0.2f })
        val shifted = FloatArray(64)
        for (index in 0 until 52) shifted[index + 12] = template[index]

        assertTrue(SixActionDetector.maxShiftedCorrelation(normalize(shifted), template) > 0.95f)
    }

    @Test
    fun sequenceTemplateWithInsufficientSpanFallsBackToDirectionRanking() {
        val leftTemplate = normalizeTemplate(leftRightWave).mapIndexed { index, value ->
            value + normalizeTemplate(leftWave)[index] * 0.12f
        }.toFloatArray().let(::normalize)
        val calibration = calibration(
            spanThreshold = 10_000f,
            templates = templates() + mapOf(
                SixActionDetector.LEFT_CLASS to leftTemplate,
                SixActionDetector.RIGHT_CLASS to FloatArray(leftTemplate.size) { -leftTemplate[it] }
            )
        )
        val result = SixActionDetector.gateMotion(
            probabilities(sequenceLeftRight = 0.9f, left = 0.8f),
            leftRightWave,
            FloatArray(500),
            calibration
        )

        assertEquals(SixActionDetector.LEFT_CLASS, result.second.motionAllowedClass)
        assertEquals(0f, result.first[SixActionDetector.LEFT_RIGHT_CLASS], 0f)
    }

    @Test
    fun strongBiteOverridesImmediateMotion() {
        val detector = SixActionDetector(calibration())

        val result = detector.update(
            probabilities(left = 0.95f, bite = 0.51f),
            leftWave,
            FloatArray(500),
            0L
        )

        assertTrue(result.strongBite)
        assertEquals(setOf(SixActionDetector.BITE_CLASS), result.singleWindowClasses)
        assertEquals(SixActionDetector.BITE_CLASS, result.eventClass)
    }

    @Test
    fun oneOrdinaryProbabilitySpikeDoesNotEmit() {
        val detector = SixActionDetector(calibration().copy(biteMinStdUv = 10_000f))

        val result = detector.update(probabilities(bite = 0.80f), FloatArray(500), FloatArray(500), 0L)

        assertNull(result.eventClass)
        assertEquals(SixActionDetector.State.POSSIBLE_ACTION, result.snapshot.state)
    }

    @Test
    fun lowActivityCannotTriggerMotionEvenWithRepeatedHighModelProbability() {
        val detector = SixActionDetector(calibration(directionThreshold = 50f))
        val quiet = FloatArray(500) { if (it % 2 == 0) 1f else -1f }

        assertNull(detector.update(probabilities(left = 0.99f), quiet, FloatArray(500), 0L).eventClass)
        assertNull(detector.update(probabilities(left = 0.99f), quiet, FloatArray(500), 250L).eventClass)
    }

    @Test
    fun sequenceLocksHalfActionsAndReleasesOnlyAfterOrderedEndpointReturn() {
        val detector = SixActionDetector(calibration())
        val emitted = mutableListOf<Int>()

        detector.update(probabilities(sequenceLeftRight = 0.9f), leftRightWave, FloatArray(500), 0L)
            .eventClass?.let(emitted::add)
        detector.update(probabilities(right = 0.9f), rightWave, FloatArray(500), 250L)
            .eventClass?.let(emitted::add)
        val released = detector.update(probabilities(left = 0.9f), leftWave, FloatArray(500), 500L)
        released.eventClass?.let(emitted::add)

        assertEquals(listOf(SixActionDetector.LEFT_RIGHT_CLASS), emitted)
        assertEquals(SixActionDetector.State.REFRACTORY, released.snapshot.state)
    }

    @Test
    fun sequenceWrongReleaseOrderStaysLockedButTrueRestReleases() {
        val detector = SixActionDetector(calibration())
        detector.update(probabilities(sequenceLeftRight = 0.9f), leftRightWave, FloatArray(500), 0L)
        val wrongOrder = detector.update(probabilities(left = 0.9f), leftWave, FloatArray(500), 250L)
        val rest = detector.update(probabilities(rest = 0.9f), FloatArray(500), FloatArray(500), 500L)

        assertEquals(SixActionDetector.State.IN_ACTION, wrongOrder.snapshot.state)
        assertEquals(SixActionDetector.State.REFRACTORY, rest.snapshot.state)
    }

    @Test
    fun resetClearsStateAndSameActionRateLimit() {
        val detector = SixActionDetector(calibration())
        assertEquals(
            SixActionDetector.LEFT_CLASS,
            detector.update(probabilities(left = 0.9f), leftWave, FloatArray(500), 0L).eventClass
        )

        detector.reset()

        assertEquals(
            SixActionDetector.LEFT_CLASS,
            detector.update(probabilities(left = 0.9f), leftWave, FloatArray(500), 100L).eventClass
        )
    }

    private fun calibration(
        spanThreshold: Float = 20f,
        directionThreshold: Float = 1f,
        templates: Map<Int, FloatArray> = templates()
    ) = AsyncCalibration(
        windowPoints = 500,
        directionMinStdUv = directionThreshold,
        biteMinStdUv = 1f,
        source = "test",
        isSessionCalibrated = true,
        protocol = ClassificationProtocol.SIX_ACTION,
        sequenceMinStdUv = 1f,
        sequenceMinSpanUv = spanThreshold,
        motionTemplates = templates
    )

    private fun templates() = mapOf(
        SixActionDetector.LEFT_CLASS to normalizeTemplate(leftWave),
        SixActionDetector.RIGHT_CLASS to normalizeTemplate(rightWave),
        SixActionDetector.LEFT_RIGHT_CLASS to normalizeTemplate(leftRightWave),
        SixActionDetector.RIGHT_LEFT_CLASS to normalizeTemplate(rightLeftWave)
    )

    private fun probabilities(
        rest: Float = 0.01f,
        left: Float = 0.01f,
        right: Float = 0.01f,
        bite: Float = 0.01f,
        sequenceLeftRight: Float = 0.01f,
        sequenceRightLeft: Float = 0.01f
    ) = floatArrayOf(rest, left, right, bite, sequenceLeftRight, sequenceRightLeft)

    private fun wave(kind: Int): FloatArray = FloatArray(500) { index ->
        when (kind) {
            1 -> when (index) { in 25 until 150 -> 100f; in 200 until 350 -> -100f; else -> 0f }
            2 -> when (index) { in 25 until 150 -> -100f; in 200 until 350 -> 100f; else -> 0f }
            4 -> when (index) { in 20 until 120 -> -100f; in 150 until 280 -> 120f; in 320 until 440 -> -80f; else -> 0f }
            5 -> when (index) { in 20 until 120 -> 100f; in 150 until 280 -> -120f; in 320 until 440 -> 80f; else -> 0f }
            else -> 0f
        }
    }

    private fun normalizeTemplate(values: FloatArray): FloatArray {
        val resampled = FloatArray(64) { index ->
            val position = index.toDouble() * values.lastIndex / 63.0
            val low = floor(position).toInt()
            val high = ceil(position).toInt().coerceAtMost(values.lastIndex)
            val fraction = (position - low).toFloat()
            values[low] * (1f - fraction) + values[high] * fraction
        }
        return normalize(resampled)
    }

    private fun normalize(values: FloatArray): FloatArray {
        val mean = values.average().toFloat()
        val centered = FloatArray(values.size) { values[it] - mean }
        val norm = sqrt(centered.sumOf { it.toDouble() * it }).toFloat()
        return FloatArray(values.size) { centered[it] / norm }
    }
}

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
    fun sixThresholdsScaleWithSessionAmplitudeAndSpan() {
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
    fun modelCandidateMustPassItsOwnPhysicalTemplate() {
        val result = SixActionDetector.gateMotion(
            probabilities(sequenceLeftRight = 0.99f), leftRightWave, FloatArray(500), calibration()
        )
        assertEquals(SixActionDetector.LEFT_RIGHT_CLASS, result.second.motionAllowedClass)
        assertEquals(0.99f, result.first[SixActionDetector.LEFT_RIGHT_CLASS], 0f)
    }

    @Test
    fun lowActivityCannotTriggerMotion() {
        val detector = SixActionDetector(calibration(directionThreshold = 50f))
        val quiet = FloatArray(500) { if (it % 2 == 0) 1f else -1f }
        repeat(5) { index ->
            assertNull(detector.update(probabilities(left = 0.99f), quiet, FloatArray(500), index * 100L).eventClass)
        }
    }

    @Test
    fun sequenceUsesSharedThreeWindowAccumulatorAndLocksOnce() {
        val detector = SixActionDetector(calibration())
        val events = (0 until 5).mapNotNull { index ->
            detector.update(
                probabilities(sequenceLeftRight = 0.99f), leftRightWave, FloatArray(500), index * 100L
            ).eventClass
        }
        assertEquals(listOf(SixActionDetector.LEFT_RIGHT_CLASS), events)
        assertEquals(SixActionDetector.State.IN_ACTION, detector.snapshot().state)
    }

    @Test
    fun resetClearsTemporalEvidence() {
        val detector = SixActionDetector(calibration())
        repeat(2) { detector.update(probabilities(left = 0.99f), leftWave, FloatArray(500), it * 100L) }
        detector.reset()
        assertNull(detector.update(probabilities(left = 0.99f), leftWave, FloatArray(500), 300L).eventClass)
    }

    private fun calibration(
        directionThreshold: Float = 1f,
        spanThreshold: Float = 20f
    ) = AsyncCalibration(
        windowPoints = 500,
        directionMinStdUv = directionThreshold,
        biteMinStdUv = 1f,
        source = "test",
        isSessionCalibrated = true,
        protocol = ClassificationProtocol.SIX_ACTION,
        sequenceMinStdUv = 1f,
        sequenceMinSpanUv = spanThreshold,
        motionTemplates = mapOf(
            SixActionDetector.LEFT_CLASS to normalizeTemplate(leftWave),
            SixActionDetector.RIGHT_CLASS to normalizeTemplate(rightWave),
            SixActionDetector.LEFT_RIGHT_CLASS to normalizeTemplate(leftRightWave),
            SixActionDetector.RIGHT_LEFT_CLASS to normalizeTemplate(rightLeftWave)
        )
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

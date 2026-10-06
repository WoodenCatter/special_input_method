package com.example.input_ds.bci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FourClassActionDetectorTest {
    private val calibration = AsyncCalibration(500, 20f, 35f, "test")

    @Test
    fun strideIsFixedAtOneHundredMilliseconds() {
        assertEquals(50, AsyncWindowPolicy.stridePoints(500))
        assertEquals(50, AsyncWindowPolicy.stridePoints(750))
        assertEquals(50, AsyncWindowPolicy.stridePoints(1_000))
        assertEquals(334, AsyncWindowPolicy.taskOverlapPoints(500))
        assertEquals(667, AsyncWindowPolicy.taskOverlapPoints(1_000))
    }

    @Test
    fun differentSessionAmplitudesProduceDifferentThresholds() {
        val low = AsyncCalibrationManager.calculateThresholds(10f, 50f, 40f, 500)
        val high = AsyncCalibrationManager.calculateThresholds(30f, 140f, 100f, 500)
        assertTrue(high.first > low.first)
        assertTrue(high.second > low.second)
    }

    @Test
    fun lowActivityCannotTriggerDirection() {
        val detector = FourClassActionDetector(calibration)
        val quiet = FloatArray(500) { if (it < 200) 2f else -2f }
        repeat(5) { index ->
            assertNull(detector.update(probabilities(left = 0.99f), quiet, FloatArray(500), index * 100L).eventClass)
        }
    }

    @Test
    fun polarityIsDiagnosticButModelOwnsDirectionClass() {
        val detector = FourClassActionDetector(calibration)
        val oppositePolarity = directionWave(leftDirection = true)
        val features = FourClassActionDetector.calculateFeatures(oppositePolarity, FloatArray(500), 20f)
        assertEquals(FourClassActionDetector.LEFT_CLASS, features.allowedDirectionClass)

        val events = (0 until 5).mapNotNull { index ->
            detector.update(
                probabilities(right = 0.99f), oppositePolarity, FloatArray(500), index * 100L
            ).eventClass
        }
        assertEquals(listOf(FourClassActionDetector.RIGHT_CLASS), events)
    }

    @Test
    fun physicalBiteGateRequiresThreeSupportedWindows() {
        val detector = FourClassActionDetector(calibration)
        val active = biteWave(80f)
        assertNull(detector.update(probabilities(bite = 0.99f), active, FloatArray(500), 0L).eventClass)
        assertNull(detector.update(probabilities(bite = 0.99f), active, FloatArray(500), 100L).eventClass)
        assertEquals(
            FourClassActionDetector.BITE_CLASS,
            detector.update(probabilities(bite = 0.99f), active, FloatArray(500), 200L).eventClass
        )
    }

    @Test
    fun onePhasePoorWindowDoesNotPoisonDirectionProbabilityEvidence() {
        val detector = FourClassActionDetector(calibration)
        val active = directionWave(leftDirection = true)
        val quiet = FloatArray(500)
        assertNull(detector.update(probabilities(left = 0.99f), active, FloatArray(500), 0L).eventClass)
        assertNull(detector.update(probabilities(left = 0.99f), quiet, FloatArray(500), 100L).eventClass)
        assertEquals(
            FourClassActionDetector.LEFT_CLASS,
            detector.update(probabilities(left = 0.99f), active, FloatArray(500), 200L).eventClass
        )
    }

    @Test
    fun lockedActionRearmsOnlyAfterSustainedRestEvidence() {
        val detector = FourClassActionDetector(calibration)
        val active = directionWave(leftDirection = true)
        repeat(3) { detector.update(probabilities(left = 0.99f), active, FloatArray(500), it * 100L) }
        repeat(7) { index ->
            detector.update(
                probabilities(rest = 0.99f), FloatArray(500), FloatArray(500), 300L + index * 100L
            )
        }
        assertEquals(FourClassActionDetector.State.REST, detector.update(
            probabilities(rest = 0.99f), FloatArray(500), FloatArray(500), 1_100L
        ).state)
    }

    @Test
    fun resetClearsTemporalEvidence() {
        val detector = FourClassActionDetector(calibration)
        val active = directionWave(leftDirection = true)
        repeat(2) { detector.update(probabilities(left = 0.99f), active, FloatArray(500), it * 100L) }
        detector.reset()
        assertNull(detector.update(probabilities(left = 0.99f), active, FloatArray(500), 300L).eventClass)
    }

    private fun probabilities(
        rest: Float = 0.01f,
        bite: Float = 0.01f,
        left: Float = 0.01f,
        right: Float = 0.01f
    ) = floatArrayOf(rest, bite, left, right)

    private fun directionWave(leftDirection: Boolean): FloatArray {
        val sign = if (leftDirection) 1f else -1f
        return FloatArray(500) { index ->
            sign * when (index) {
                in 25 until 150 -> 100f
                in 200 until 350 -> -100f
                else -> 0f
            }
        }
    }

    private fun biteWave(amplitude: Float): FloatArray =
        FloatArray(500) { index -> if (index % 2 == 0) amplitude else -amplitude }
}

package com.example.input_ds.bci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FourClassActionDetectorTest {
    private val calibration = AsyncCalibration(500, 20f, 35f, "test")

    @Test
    fun strideIsAlwaysQuarterOfWindow() {
        assertEquals(125, AsyncWindowPolicy.stridePoints(500))
        assertEquals(188, AsyncWindowPolicy.stridePoints(750))
        assertEquals(250, AsyncWindowPolicy.stridePoints(1_000))
    }

    @Test
    fun differentSessionAmplitudesProduceDifferentThresholds() {
        val lowAmplitude = AsyncCalibrationManager.calculateThresholds(10f, 50f, 40f, 500)
        val highAmplitude = AsyncCalibrationManager.calculateThresholds(30f, 140f, 100f, 500)

        assertTrue(highAmplitude.first > lowAmplitude.first)
        assertTrue(highAmplitude.second > lowAmplitude.second)
    }

    @Test
    fun lowActivityDriftCannotTriggerDirection() {
        val detector = FourClassActionDetector(calibration)
        val low = FloatArray(500) { if (it < 200) 2f else -2f }

        val result = detector.update(probabilities(left = 0.98f), low, FloatArray(500), 0L)

        assertNull(result.eventClass)
        assertNull(result.features.allowedDirectionClass)
    }

    @Test
    fun polarityAllowsOnlyMatchingDirection() {
        val leftWave = directionWave(leftDirection = true)
        val leftFeatures = FourClassActionDetector.calculateFeatures(leftWave, FloatArray(500), 20f)
        val rightWave = directionWave(leftDirection = false)
        val rightFeatures = FourClassActionDetector.calculateFeatures(rightWave, FloatArray(500), 20f)

        assertEquals(FourClassActionDetector.LEFT_CLASS, leftFeatures.allowedDirectionClass)
        assertEquals(FourClassActionDetector.RIGHT_CLASS, rightFeatures.allowedDirectionClass)
    }

    @Test
    fun strongDirectionEmitsImmediatelyAndOnlyOnceWhileHeld() {
        val detector = FourClassActionDetector(calibration)
        val wave = directionWave(leftDirection = true)

        val first = detector.update(probabilities(left = 0.97f), wave, FloatArray(500), 0L)
        val held = detector.update(probabilities(left = 0.97f), wave, FloatArray(500), 250L)

        assertEquals(FourClassActionDetector.LEFT_CLASS, first.eventClass)
        assertNull(held.eventClass)
    }

    @Test
    fun basicDirectionNeedsTwoWindows() {
        val detector = FourClassActionDetector(calibration)
        val wave = directionWave(leftDirection = true, moderateScore = true)

        val first = detector.update(probabilities(left = 0.92f), wave, FloatArray(500), 0L)
        val second = detector.update(probabilities(left = 0.92f), wave, FloatArray(500), 250L)

        assertNull(first.eventClass)
        assertEquals(FourClassActionDetector.LEFT_CLASS, second.eventClass)
    }

    @Test
    fun strongBiteOverridesStrongDirectionInSameWindow() {
        val detector = FourClassActionDetector(calibration)
        val wave = directionWave(leftDirection = true)

        val result = detector.update(
            floatArrayOf(0.01f, 0.51f, 0.97f, 0.01f),
            wave,
            FloatArray(500),
            0L
        )

        assertTrue(result.strongBite)
        assertTrue(result.strongDirection)
        assertEquals(FourClassActionDetector.BITE_CLASS, result.eventClass)
    }

    @Test
    fun normalBiteNeedsTwoHits() {
        val detector = FourClassActionDetector(calibration.copy(biteMinStdUv = 200f))
        val quiet = FloatArray(500)

        assertNull(detector.update(probabilities(bite = 0.80f), quiet, quiet, 0L).eventClass)
        assertEquals(
            FourClassActionDetector.BITE_CLASS,
            detector.update(probabilities(bite = 0.80f), quiet, quiet, 250L).eventClass
        )
    }

    @Test
    fun resetClearsPendingCandidate() {
        val detector = FourClassActionDetector(calibration.copy(biteMinStdUv = 200f))
        val quiet = FloatArray(500)
        detector.update(probabilities(bite = 0.80f), quiet, quiet, 0L)

        detector.reset()
        val afterReset = detector.update(probabilities(bite = 0.80f), quiet, quiet, 250L)

        assertNull(afterReset.eventClass)
    }

    private fun probabilities(
        rest: Float = 0.01f,
        bite: Float = 0.01f,
        left: Float = 0.01f,
        right: Float = 0.01f
    ) = floatArrayOf(rest, bite, left, right)

    private fun directionWave(leftDirection: Boolean, moderateScore: Boolean = false): FloatArray {
        val sign = if (leftDirection) 1f else -1f
        val middle = if (moderateScore) -35f else -100f
        return FloatArray(500) { index ->
            sign * when (index) {
                in 25 until 150 -> 100f
                in 200 until 350 -> middle
                else -> 0f
            }
        }
    }
}

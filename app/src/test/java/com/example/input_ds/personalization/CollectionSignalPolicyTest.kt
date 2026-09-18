package com.example.input_ds.personalization

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSignalPolicyTest {
    @Test
    fun collection_requiresTwoStableWindows() {
        assertEquals(2, CollectionSignalPolicy.REQUIRED_STABLE_WINDOWS)
    }

    @Test
    fun stableRate_acceptsConfiguredToleranceBoundaries() {
        assertTrue(CollectionSignalPolicy.isStableRate(450f))
        assertTrue(CollectionSignalPolicy.isStableRate(500f))
        assertTrue(CollectionSignalPolicy.isStableRate(550f))
        assertFalse(CollectionSignalPolicy.isStableRate(449.9f))
        assertFalse(CollectionSignalPolicy.isStableRate(550.1f))
    }

    @Test
    fun rateHz_usesActualElapsedTime() {
        assertTrue(CollectionSignalPolicy.rateHz(samples = 500, elapsedMs = 1_000) == 500f)
        assertTrue(CollectionSignalPolicy.rateHz(samples = 900, elapsedMs = 2_000) == 450f)
    }

    @Test
    fun actionWindow_rejectsAnyShortage() {
        assertTrue(CollectionSignalPolicy.hasCompleteActionWindow(1_000, 1_500, 500))
        assertTrue(CollectionSignalPolicy.hasCompleteActionWindow(1_000, 1_550, 500))
        assertFalse(CollectionSignalPolicy.hasCompleteActionWindow(1_000, 1_499, 500))
    }

    @Test
    fun actionWindow_rejectsAnAcquisitionEpochChange() {
        assertTrue(CollectionSignalPolicy.hasValidActionWindow(1_000, 1_500, 500, 7L, 7L))
        assertFalse(CollectionSignalPolicy.hasValidActionWindow(1_000, 1_500, 500, 7L, 8L))
    }
}

package com.example.input_ds.bci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BleStreamLivenessTest {
    @Test
    fun healthyPairsRemainStreaming() {
        val liveness = BleStreamLiveness()
        liveness.reset(1_000L)
        liveness.onLeft(1_100L)
        liveness.onRight(1_100L)
        liveness.onPair(1_100L)

        val snapshot = liveness.snapshot(1_900L)

        assertEquals(BleStreamLiveness.Health.STREAMING, snapshot.health)
        assertNull(snapshot.stalledSide)
    }

    @Test
    fun oneSilentEarIsIdentified() {
        val liveness = BleStreamLiveness()
        liveness.reset(1_000L)
        liveness.onLeft(1_100L)
        liveness.onRight(1_100L)
        liveness.onPair(1_100L)
        liveness.onRight(2_000L)

        val snapshot = liveness.snapshot(2_700L)

        assertEquals(BleStreamLiveness.Health.STALLED, snapshot.health)
        assertEquals("左耳", snapshot.stalledSide)
    }

    @Test
    fun freshEarsWithoutPairsIdentifyAlignmentStall() {
        val liveness = BleStreamLiveness()
        liveness.reset(1_000L)
        liveness.onLeft(1_100L)
        liveness.onRight(1_100L)
        liveness.onPair(1_100L)
        liveness.onLeft(2_650L)
        liveness.onRight(2_650L)

        val snapshot = liveness.snapshot(2_700L)

        assertEquals(BleStreamLiveness.Health.STALLED, snapshot.health)
        assertEquals("双耳配对", snapshot.stalledSide)
    }

    @Test
    fun aRecoveredPairReturnsToStreaming() {
        val liveness = BleStreamLiveness()
        liveness.reset(1_000L)
        liveness.onLeft(1_100L)
        liveness.onRight(1_100L)
        liveness.onPair(1_100L)
        assertEquals(BleStreamLiveness.Health.STALLED, liveness.snapshot(2_700L).health)

        liveness.onLeft(2_800L)
        liveness.onRight(2_800L)
        liveness.onPair(2_800L)

        assertEquals(BleStreamLiveness.Health.STREAMING, liveness.snapshot(2_850L).health)
    }

    @Test
    fun localReferenceRecoveryDefersOnlyThePairDeadline() {
        val liveness = BleStreamLiveness()
        liveness.reset(1_000L)
        liveness.onLeft(2_400L)
        liveness.onRight(2_400L)
        liveness.deferPairDeadline(2_500L)

        val snapshot = liveness.snapshot(2_600L)

        assertEquals(BleStreamLiveness.Health.STREAMING, snapshot.health)
        assertEquals(200L, snapshot.leftAgeMs)
        assertEquals(200L, snapshot.rightAgeMs)
        assertEquals(100L, snapshot.pairAgeMs)
    }
}

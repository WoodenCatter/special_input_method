package com.example.input_ds.bci

/**
 * Aligns independently delivered left/right EEG packets by the unsigned
 * protocol packet counter.
 *
 * BLE callback time is deliberately not used as an acquisition clock: Android
 * may deliver notifications late or in bursts. A stereo packet is published as
 * soon as both ears provide the same counter. Missing counters create a new
 * acquisition epoch, but matching continues at the next counter shared by both
 * ears without accumulating latency.
 */
internal class EegStereoPacketAligner(
    private val maxPendingPerSide: Int = 8
) {
    data class StereoPacket(
        val packetCount: Int,
        val left: FloatArray,
        val right: FloatArray,
        val resynchronized: Boolean,
        val missingPacketPairs: Int
    )

    data class Stats(
        val leftPacketsReceived: Long,
        val rightPacketsReceived: Long,
        val exactMatches: Long,
        val resyncMatches: Long,
        val leftDroppedPackets: Long,
        val rightDroppedPackets: Long,
        val pendingLeftPackets: Int,
        val pendingRightPackets: Int,
        val lastLeftCounter: Int?,
        val lastRightCounter: Int?,
        val lastMatchedCounter: Int?,
        val missingPacketPairs: Long
    ) {
        val totalMatches: Long get() = exactMatches + resyncMatches
    }

    private val pendingLeft = LinkedHashMap<Int, FloatArray>()
    private val pendingRight = LinkedHashMap<Int, FloatArray>()
    private var leftPacketsReceived = 0L
    private var rightPacketsReceived = 0L
    private var exactMatches = 0L
    private var resyncMatches = 0L
    private var leftDroppedPackets = 0L
    private var rightDroppedPackets = 0L
    private var missingPacketPairs = 0L
    private var lastLeftCounter: Int? = null
    private var lastRightCounter: Int? = null
    private var lastMatchedCounter: Int? = null

    init {
        require(maxPendingPerSide > 0)
    }

    @Synchronized
    fun append(packet: EegDataParser.EegPacket): StereoPacket? {
        val isLeft = packet.earSide == "left"
        val own = if (isLeft) pendingLeft else pendingRight
        val other = if (isLeft) pendingRight else pendingLeft

        if (isLeft) {
            leftPacketsReceived++
            lastLeftCounter = packet.packetCount
        } else {
            rightPacketsReceived++
            lastRightCounter = packet.packetCount
        }

        // A repeated, still-unpaired counter is a duplicate notification. Keep
        // the newest CRC-validated payload, but account for the replaced packet.
        if (own.put(packet.packetCount, packet.samples) != null) {
            recordDrop(isLeft)
        }

        val matching = other.remove(packet.packetCount)
        if (matching == null) {
            trim(own, isLeft)
            return null
        }
        own.remove(packet.packetCount)

        return if (isLeft) {
            buildStereo(packet.packetCount, packet.samples, matching)
        } else {
            buildStereo(packet.packetCount, matching, packet.samples)
        }
    }

    @Synchronized
    fun stats(): Stats = Stats(
        leftPacketsReceived = leftPacketsReceived,
        rightPacketsReceived = rightPacketsReceived,
        exactMatches = exactMatches,
        resyncMatches = resyncMatches,
        leftDroppedPackets = leftDroppedPackets,
        rightDroppedPackets = rightDroppedPackets,
        pendingLeftPackets = pendingLeft.size,
        pendingRightPackets = pendingRight.size,
        lastLeftCounter = lastLeftCounter,
        lastRightCounter = lastRightCounter,
        lastMatchedCounter = lastMatchedCounter,
        missingPacketPairs = missingPacketPairs
    )

    @Synchronized
    fun reset() {
        pendingLeft.clear()
        pendingRight.clear()
        leftPacketsReceived = 0L
        rightPacketsReceived = 0L
        exactMatches = 0L
        resyncMatches = 0L
        leftDroppedPackets = 0L
        rightDroppedPackets = 0L
        missingPacketPairs = 0L
        lastLeftCounter = null
        lastRightCounter = null
        lastMatchedCounter = null
    }

    private fun buildStereo(
        counter: Int,
        left: FloatArray,
        right: FloatArray
    ): StereoPacket? {
        val alignedSize = minOf(left.size, right.size)
        if (alignedSize <= 0) return null

        val previous = lastMatchedCounter
        val forwardDistance = previous?.let { (counter - it) and 0xFF }
        if (forwardDistance != null && (forwardDistance == 0 || forwardDistance > 127)) {
            // This pair was already emitted, or arrived after a newer pair.
            leftDroppedPackets++
            rightDroppedPackets++
            return null
        }

        val missing = if (forwardDistance != null && forwardDistance > 1) {
            forwardDistance - 1
        } else {
            0
        }
        if (missing > 0) {
            resyncMatches++
            missingPacketPairs += missing.toLong()
        } else {
            exactMatches++
        }
        lastMatchedCounter = counter

        return StereoPacket(
            packetCount = counter,
            left = if (left.size == alignedSize) left else left.copyOf(alignedSize),
            right = if (right.size == alignedSize) right else right.copyOf(alignedSize),
            resynchronized = missing > 0,
            missingPacketPairs = missing
        )
    }

    private fun trim(pending: LinkedHashMap<Int, FloatArray>, isLeft: Boolean) {
        while (pending.size > maxPendingPerSide) {
            pending.remove(pending.entries.first().key)
            recordDrop(isLeft)
        }
    }

    private fun recordDrop(isLeft: Boolean) {
        if (isLeft) leftDroppedPackets++ else rightDroppedPackets++
    }
}

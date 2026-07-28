package com.example.input_ds.bci

/**
 * Matches the independently delivered left/right BLE notifications by the
 * device packet counter. A packet is released only when both ears are present.
 */
internal class EegStereoPacketAligner(
    private val maxPendingPerSide: Int = 8
) {
    data class StereoPacket(
        val packetCount: Int,
        val left: FloatArray,
        val right: FloatArray
    )

    private val pendingLeft = LinkedHashMap<Int, FloatArray>()
    private val pendingRight = LinkedHashMap<Int, FloatArray>()

    init {
        require(maxPendingPerSide > 0)
    }

    @Synchronized
    fun append(packet: EegDataParser.EegPacket): StereoPacket? {
        val own = if (packet.earSide == "left") pendingLeft else pendingRight
        val other = if (packet.earSide == "left") pendingRight else pendingLeft
        own[packet.packetCount] = packet.samples

        val matching = other.remove(packet.packetCount)
        if (matching == null) {
            trim(own)
            return null
        }
        own.remove(packet.packetCount)

        val left = if (packet.earSide == "left") packet.samples else matching
        val right = if (packet.earSide == "right") packet.samples else matching
        val alignedSize = minOf(left.size, right.size)
        if (alignedSize <= 0) return null

        return StereoPacket(
            packetCount = packet.packetCount,
            left = if (left.size == alignedSize) left else left.copyOf(alignedSize),
            right = if (right.size == alignedSize) right else right.copyOf(alignedSize)
        )
    }

    @Synchronized
    fun reset() {
        pendingLeft.clear()
        pendingRight.clear()
    }

    private fun trim(pending: LinkedHashMap<Int, FloatArray>) {
        while (pending.size > maxPendingPerSide) {
            pending.remove(pending.entries.first().key)
        }
    }
}

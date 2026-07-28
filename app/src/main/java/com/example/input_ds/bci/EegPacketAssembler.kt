package com.example.input_ds.bci

/**
 * 将可能被 BLE 分片或合并的通知重组为完整 EEG 数据包。
 *
 * 固件存在三种长度字段：载荷字节数、采样点数，或“载荷 + 2 字节状态”。
 * BLE-096E 实机使用第三种格式（152 + 8 = 160 字节整包）。这里兼容三种编码，
 * 并用尾标志和 CRC 确认边界，避免把载荷中的字节误判为新包。
 */
class EegPacketAssembler {
    private var pending = ByteArray(0)

    fun append(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        pending = (pending + chunk).takeLast(MAX_PENDING_BYTES).toByteArray()
        val packets = mutableListOf<ByteArray>()

        while (pending.size >= HEADER_SIZE) {
            val headerIndex = findHeader(pending)
            if (headerIndex < 0) {
                pending = pending.takeLast(1).toByteArray()
                break
            }
            if (headerIndex > 0) pending = pending.copyOfRange(headerIndex, pending.size)
            if (pending.size < LENGTH_FIELD_SIZE) break

            val lengthField = pending[4].toInt() and 0xFF
            val candidateLengths = listOf(
                lengthField + PACKET_OVERHEAD,
                lengthField * BYTES_PER_SAMPLE + PACKET_OVERHEAD,
                lengthField + STATUS_LENGTH_PACKET_OVERHEAD
            ).distinct().filter { it in MIN_PACKET_BYTES..MAX_PACKET_BYTES }
            if (candidateLengths.isEmpty()) {
                pending = pending.copyOfRange(1, pending.size)
                continue
            }

            val packetLength = candidateLengths
                .filter { pending.size >= it }
                .firstOrNull { isCompletePacket(pending, it) }
            if (packetLength != null) {
                packets += pending.copyOfRange(0, packetLength)
                pending = pending.copyOfRange(packetLength, pending.size)
            } else if (pending.size < candidateLengths.max()) {
                break
            } else {
                pending = pending.copyOfRange(1, pending.size)
            }
        }
        return packets
    }

    fun reset() {
        pending = ByteArray(0)
    }

    private fun findHeader(data: ByteArray): Int {
        for (i in 0 until data.lastIndex) {
            if (BleProtocol.isHeader(data, i)) return i
        }
        return -1
    }

    private fun isCompletePacket(data: ByteArray, length: Int): Boolean {
        if (length < MIN_PACKET_BYTES ||
            data[length - 3] != 0x55.toByte() ||
            data[length - 2] != 0xAA.toByte()) return false
        return crc8Maxim(data, length - 1) == (data[length - 1].toInt() and 0xFF)
    }

    private fun crc8Maxim(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0x8C else crc shr 1
            }
        }
        return crc
    }

    private companion object {
        const val HEADER_SIZE = 2
        const val LENGTH_FIELD_SIZE = 5
        const val PACKET_OVERHEAD = 10
        const val STATUS_LENGTH_PACKET_OVERHEAD = 8
        const val BYTES_PER_SAMPLE = 3
        const val MIN_PACKET_BYTES = 10
        const val MAX_PACKET_BYTES = 265
        const val MAX_PENDING_BYTES = 4096
    }
}

package com.example.input_ds.bci

import com.example.input_ds.bci.BleProtocol.MAX_MILLI_VOLT
import com.example.input_ds.bci.BleProtocol.MAGNIFICATION
import com.example.input_ds.bci.BleProtocol.FULL_RANGE_DATA
import android.util.Log

/**
 * 解析 BLE-096E EEG 数据包 → μV 浮点值
 * 对应 Python DataParser.parse_eeg_data
 */
object EegDataParser {

    data class EegPacket(val earSide: String, val leadOff: Int, val packetCount: Int, val samples: FloatArray)

    fun parseEegData(data: ByteArray, earSide: String): EegPacket? {
        if (data.size < 10) { Log.w("EegParser", "$earSide 数据太短: ${data.size}"); return null }
        if (!BleProtocol.isHeader(data, 0)) { Log.w("EegParser", "$earSide 包头不是AA55: ${data[0].toHex()} ${data[1].toHex()}"); return null }

        // CRC8
        val expectedCrc = data[data.size - 1].toInt() and 0xFF
        val actualCrc = crc8Maxim(data, data.size - 1)
        if (expectedCrc != actualCrc) { Log.w("EegParser", "$earSide CRC失败 expect=$expectedCrc actual=$actualCrc len=${data.size}"); return null }

        // 尾标志验证: data[-3] == 55, data[-2] == AA (对齐 Python data[-3:-1] == b"\\x55\\xAA")
        if (data[data.size - 3] != 0x55.toByte() || data[data.size - 2] != 0xAA.toByte()) {
            Log.w("EegParser", "$earSide 尾标志不是55AA: ${data[data.size-3].toHex()} ${data[data.size-2].toHex()}")
            return null
        }

        val earFlag = data[3].toInt() and 0xFF
        val expected = if (earSide == "left") 0 else 1
        if (earFlag != expected) { Log.w("EegParser", "$earSide 耳标志不匹配: $earFlag vs $expected"); return null }

        val lengthField = data[4].toInt() and 0xFF
        val payloadLength = when {
            data.size == lengthField + 10 && lengthField % 3 == 0 -> lengthField
            data.size == lengthField * 3 + 10 -> lengthField * 3
            data.size == lengthField + 8 && (lengthField - 2) % 3 == 0 -> lengthField - 2
            else -> {
                Log.w("EegParser", "$earSide 长度字段异常: field=$lengthField total=${data.size}")
                return null
            }
        }
        if (payloadLength % 3 != 0) {
            Log.w("EegParser", "$earSide 载荷不是完整的24位采样: $payloadLength")
            return null
        }
        val payloadStart = 5
        val payloadEnd = payloadStart + payloadLength

        val samples = mutableListOf<Float>()
        var i = payloadStart
        while (i + 3 <= payloadEnd) {
            val b0 = data[i].toInt() and 0xFF
            val b1 = data[i + 1].toInt() and 0xFF
            val b2 = data[i + 2].toInt() and 0xFF
            var value = (b2 shl 16) or (b1 shl 8) or b0
            if ((value and (1 shl 23)) != 0) value -= 1 shl 24
            val uV = value.toDouble() * MAX_MILLI_VOLT * MAGNIFICATION / FULL_RANGE_DATA
            samples.add(uV.toFloat())
            i += 3
        }

        val leadOff = data[data.size - 5].toInt() and 0xFF
        val packetCount = data[data.size - 4].toInt() and 0xFF

        return EegPacket(earSide, leadOff, packetCount, samples.toFloatArray())
    }

    private fun crc8Maxim(data: ByteArray, length: Int): Int {
        var crc = 0
        for (i in 0 until length) {
            crc = crc xor (data[i].toInt() and 0xFF)
            for (j in 0..7) {
                crc = if ((crc and 1) != 0) (crc shr 1) xor 0x8C else crc shr 1
            }
        }
        return crc
    }

    private fun Byte.toHex() = String.format("%02X", this.toInt() and 0xFF)
}

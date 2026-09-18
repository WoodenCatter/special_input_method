package com.example.input_ds.bci

/**
 * Naoyun Pods BLE-096E BLE 协议常量
 * 对应 Python 项目 connect/src/ble/tools.py
 */
object BleProtocol {
    // ── 设备过滤 ──
    const val DEVICE_NAME_FILTER = "Naoyun Pods BLE"

    // ── BLE UUID ──
    const val CMD_SERVICE_UUID = "30ae0100-0000-1000-8000-009034122420"
    const val CMD_WRITE_UUID = "32ae0100-0000-1000-8000-009034122420"
    const val CMD_NOTIFY_UUID = "31ae0100-0000-1000-8000-009034122420"

    const val DATA_SERVICE_UUID = "30ae0100-0000-1000-8000-009021091520"
    const val DATA_LEFT_NOTIFY_UUID = "31ae0200-0000-1000-8000-009121091520"
    const val DATA_RIGHT_NOTIFY_UUID = "32ae0300-0000-1000-8000-009221091520"

    // ── 命令 ──
    val GET_INFO_CMD = byteArrayOf(0xAA.toByte(), 0x55, 0x00, 0xE0.toByte(), 0x00, 0x55, 0xAA.toByte(), 0x26)
    val OPEN_DATA_CMD = byteArrayOf(0xAA.toByte(), 0x55, 0x01, 0x01, 0x00, 0x55, 0xAA.toByte(), 0x2D)

    // ── 数据解析常量 ──
    const val SAMPLE_RATE = 500
    const val SAMPLES_PER_PACKET = 50
    const val MAX_MILLI_VOLT = 5000.0
    const val MAGNIFICATION = 1000.0 / 24.0
    const val FULL_RANGE_DATA = 16777215.0  // 2^24 - 1

    /** 数据包头 AA 55 */
    fun isHeader(data: ByteArray, offset: Int): Boolean =
        data.size - offset >= 2 && data[offset] == 0xAA.toByte() && data[offset + 1] == 0x55.toByte()
}

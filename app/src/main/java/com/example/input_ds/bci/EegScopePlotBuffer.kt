package com.example.input_ds.bci

/**
 * 示波器式固定画布缓冲区。
 *
 * 新样本只覆盖当前写指针位置；写到末尾后从左侧继续，不移动历史数据。
 */
internal class EegScopePlotBuffer(private val capacity: Int = 2_500) {
    data class Snapshot(
        val values: FloatArray,
        val writePosition: Int,
        val validCount: Int,
        val current: Float,
        val mean: Float,
        val peakToPeak: Float
    )

    private val values = FloatArray(capacity)
    private var writePosition = 0
    private var validCount = 0
    private var current = 0f

    init {
        require(capacity > 1)
    }

    fun append(samples: FloatArray) {
        for (sample in samples) {
            if (!sample.isFinite()) continue
            values[writePosition] = sample
            writePosition = (writePosition + 1) % capacity
            validCount = minOf(validCount + 1, capacity)
            current = sample
        }
    }

    /**
     * Writes against the absolute shared sample clock. Reattaching either
     * channel can no longer give it a different visual cursor origin.
     */
    fun appendAligned(startSample: Int, samples: FloatArray) {
        writePosition = Math.floorMod(startSample, capacity)
        append(samples)
    }

    fun snapshot(): Snapshot {
        var minimum = Float.POSITIVE_INFINITY
        var maximum = Float.NEGATIVE_INFINITY
        var sum = 0.0
        if (validCount < capacity) {
            for (index in 0 until validCount) {
                val value = values[index]
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                sum += value
            }
        } else {
            for (value in values) {
                minimum = minOf(minimum, value)
                maximum = maxOf(maximum, value)
                sum += value
            }
        }
        return Snapshot(
            values = values.copyOf(),
            writePosition = writePosition,
            validCount = validCount,
            current = current,
            mean = if (validCount > 0) (sum / validCount).toFloat() else 0f,
            peakToPeak =
                if (validCount > 0) maximum - minimum else 0f
        )
    }

    fun reset() {
        values.fill(0f)
        writePosition = 0
        validCount = 0
        current = 0f
    }
}

/**
 * 对齐轮椅项目 EEGSignalProcessor 的四阶 0.01–100 Hz Butterworth 实时带通。
 *
 * 使用与原项目等价的二阶节（SOS）Direct Form II Transposed，并跨数据包
 * 保存状态。SOS 可避免 0.01 Hz 极低截止频率在手机上用八阶直接形式造成的
 * 数值偏置；初始状态按首样本缩放，避免设备约 ±10 mV 的直流基线启动瞬态。
 */
internal class RealtimeEegDisplayFilter {
    private val state = Array(SOS.size) { DoubleArray(2) }
    private var initialized = false

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (!initialized) {
            val first = input.firstOrNull { it.isFinite() }?.toDouble() ?: 0.0
            for (section in state.indices) {
                state[section][0] = INITIAL_STATE[section][0] * first
                state[section][1] = INITIAL_STATE[section][1] * first
            }
            initialized = true
        }

        val output = FloatArray(input.size)
        for (sampleIndex in input.indices) {
            var sectionInput =
                input[sampleIndex].takeIf(Float::isFinite)?.toDouble() ?: 0.0
            for (section in SOS.indices) {
                val coefficients = SOS[section]
                val sectionOutput =
                    coefficients[0] * sectionInput + state[section][0]
                state[section][0] =
                    coefficients[1] * sectionInput -
                            coefficients[4] * sectionOutput +
                            state[section][1]
                state[section][1] =
                    coefficients[2] * sectionInput -
                            coefficients[5] * sectionOutput
                sectionInput = sectionOutput
            }
            output[sampleIndex] = sectionInput.toFloat()
        }
        return output
    }

    fun reset() {
        state.forEach { it.fill(0.0) }
        initialized = false
    }

    private companion object {
        // scipy.signal.butter(4, [0.01/250, 100/250], "band", output="sos")
        val SOS = arrayOf(
            doubleArrayOf(
                0.04656819121005451, 0.09313638242010902,
                0.04656819121005451, 1.0,
                -0.32912876872762403, 0.06462299468914907
            ),
            doubleArrayOf(
                1.0, 2.0, 1.0, 1.0,
                -0.453171904311331, 0.466386398688328
            ),
            doubleArrayOf(
                1.0, -2.0, 1.0, 1.0,
                -1.9997677865147194, 0.9997678023089164
            ),
            doubleArrayOf(
                1.0, -2.0, 1.0, 1.0,
                -1.9999038217597829, 0.9999038375511904
            )
        )
        val INITIAL_STATE = arrayOf(
            doubleArrayOf(0.20669384439151703, 0.030201640028411077),
            doubleArrayOf(0.7465737820149013, -0.21304779065617524),
            doubleArrayOf(-0.9998358138141308, 0.9998358138150137),
            doubleArrayOf(0.0, 0.0)
        )
    }
}

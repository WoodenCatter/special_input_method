package com.example.input_ds.bci

/** 滚动时间线使用的环形缓冲区；存储不搬移，显示时按时间顺序读取。 */
internal class EegScopePlotBuffer(private val capacity: Int = 2_500) {
    data class Snapshot(
        val values: FloatArray,
        val writePosition: Int,
        val validCount: Int,
        val current: Float,
        val mean: Float,
        val peakToPeak: Float
    ) {
        /** 0 是当前窗口最早的样本，validCount - 1 是最新样本。 */
        fun chronologicalValueAt(index: Int): Float {
            require(index in 0 until validCount)
            val oldest = Math.floorMod(writePosition - validCount, values.size)
            return values[(oldest + index) % values.size]
        }
    }

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
        val oldest = Math.floorMod(writePosition - validCount, capacity)
        for (logicalIndex in 0 until validCount) {
            val value = values[(oldest + logicalIndex) % capacity]
            minimum = minOf(minimum, value)
            maximum = maxOf(maximum, value)
            sum += value
        }
        return Snapshot(
            // The plot buffer is only mutated and drawn on the main thread.
            // Reuse it instead of allocating a 2,500-float copy per update.
            values = values,
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
 * 与实时绘图标注一致的四阶 1–45 Hz Butterworth 因果带通。
 *
 * 使用 Direct Form II Transposed 并跨数据包保存状态，因此每次刷新只处理
 * 新到样本。初始状态按首样本缩放，避免设备直流基线造成启动瞬态。
 */
internal class RealtimeEegDisplayFilter {
    private val state = DoubleArray(FILTER_ORDER)
    private var initialized = false

    fun process(input: FloatArray): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        if (!initialized) {
            val first = input.firstOrNull { it.isFinite() }?.toDouble() ?: 0.0
            for (index in state.indices) {
                state[index] = INITIAL_STATE[index] * first
            }
            initialized = true
        }

        val output = FloatArray(input.size)
        for (sampleIndex in input.indices) {
            val sample =
                input[sampleIndex].takeIf(Float::isFinite)?.toDouble() ?: 0.0
            val filtered = B[0] * sample + state[0]
            for (index in 0 until FILTER_ORDER - 1) {
                state[index] =
                    B[index + 1] * sample - A[index + 1] * filtered + state[index + 1]
            }
            state[FILTER_ORDER - 1] =
                B[FILTER_ORDER] * sample - A[FILTER_ORDER] * filtered
            output[sampleIndex] = filtered.toFloat()
        }
        return output
    }

    fun reset() {
        state.fill(0.0)
        initialized = false
    }

    private companion object {
        const val FILTER_ORDER = 4
        // scipy.signal.butter(2, [1.0 / 250, 45.0 / 250], "bandpass")
        val B = doubleArrayOf(
            0.05432786111292473,
            0.0,
            -0.10865572222584946,
            0.0,
            0.05432786111292473
        )
        val A = doubleArrayOf(
            1.0,
            -3.229289188026897,
            3.9209736639167874,
            -2.150060682534536,
            0.45841205794992834
        )
        // scipy.signal.lfilter_zi(B, A)
        val INITIAL_STATE = doubleArrayOf(
            -0.054327861113516715,
            -0.05432786111160498,
            0.05432786111192328,
            0.05432786111319609
        )
    }
}

package com.example.input_ds.bci

/**
 * 与 Ear_EEG_2 轮椅项目训练和实时推理保持一致的模型预处理：
 * 1. 500 Hz 双通道连续数据；
 * 2. scipy.signal.butter(2, [1, 45], "bandpass")；
 * 3. scipy.signal.filtfilt(..., padlen=15)；
 * 4. 滤波后截取模型窗口；输入法800ms模式为末尾400点；
 * 5. 输出通道顺序为 [左耳, 右耳]。
 *
 * 这里仅负责模型输入；实时绘图继续使用独立的显示滤波链路。
 */
object EegPreprocessor {

    // scipy.signal.butter(2, [1.0 * 2 / 500, 45.0 * 2 / 500], "bandpass")
    private val trainingB = doubleArrayOf(
        0.05432786111292473, 0.0, -0.10865572222584946, 0.0,
        0.05432786111292473
    )
    private val trainingA = doubleArrayOf(
        1.0, -3.229289188026897, 3.9209736639167874,
        -2.150060682534536, 0.45841205794992834
    )

    // scipy.signal.iirnotch(50 / 250, Q=30)
    private val displayNotchB = doubleArrayOf(
        0.9896361753628921, -1.6012649682336106, 0.9896361753628921
    )
    private val displayNotchA = doubleArrayOf(
        1.0, -1.6012649682336106, 0.9792723507257841
    )

    // scipy.signal.butter(4, [0.1 / 250, 100 / 250], btype="bandpass")
    private val displayBandB = doubleArrayOf(
        0.04643589106059568, 0.0, -0.18574356424238272, 0.0,
        0.2786153463635741, 0.0, -0.18574356424238272, 0.0,
        0.04643589106059568
    )
    private val displayBandA = doubleArrayOf(
        1.0, -4.78086529255385, 9.805984440680623, -11.596115069271498,
        8.974602219379856, -4.724201487852284, 1.59455040371086,
        -0.30417946736345464, 0.030224253271605914
    )

    fun preprocess(
        left: FloatArray,
        right: FloatArray,
        targetPoints: Int = 400
    ): Array<FloatArray>? {
        if (targetPoints <= TRAINING_PAD_LENGTH ||
            left.size < targetPoints ||
            right.size < targetPoints ||
            left.size <= TRAINING_PAD_LENGTH ||
            right.size <= TRAINING_PAD_LENGTH
        ) {
            return null
        }
        if (!left.isFinite() || !right.isFinite()) return null

        // Ear_EEG_2 filters accumulated continuous samples before slicing the
        // latest two-second model window.
        val leftFiltered =
            filtfilt(left, trainingB, trainingA, TRAINING_PAD_LENGTH)
        val rightFiltered =
            filtfilt(right, trainingB, trainingA, TRAINING_PAD_LENGTH)
        val result = arrayOf(
            leftFiltered.copyOfRange(
                leftFiltered.size - targetPoints,
                leftFiltered.size
            ),
            rightFiltered.copyOfRange(
                rightFiltered.size - targetPoints,
                rightFiltered.size
            )
        )
        return result.takeIf { channels ->
            channels.all { channel -> channel.isFinite() }
        }
    }

    /**
     * 对最近的显示窗口应用桌面 signal_monitor.py 使用的滤波。
     * 数据不足时与桌面端一致，直接返回原始副本。
     */
    fun filterForDisplay(data: FloatArray): FloatArray {
        if (data.size < 10 || !data.isFinite()) return data.copyOf()
        val padLength = minOf(data.size / 3, DISPLAY_PAD_LENGTH).coerceAtLeast(1)
        val notch = filtfilt(data, displayNotchB, displayNotchA, padLength)
        return filtfilt(notch, displayBandB, displayBandA, padLength)
    }

    private fun filtfilt(
        input: FloatArray,
        numerator: DoubleArray,
        denominator: DoubleArray,
        padLength: Int
    ): FloatArray {
        require(input.isNotEmpty())
        require(padLength in 0 until input.size)

        val b = numerator.copyOf(maxOf(numerator.size, denominator.size))
        val a = denominator.copyOf(b.size)
        val a0 = a[0]
        require(a0 != 0.0)
        if (a0 != 1.0) {
            for (i in b.indices) {
                b[i] /= a0
                a[i] /= a0
            }
        }

        val extended = oddExtend(input, padLength)
        val zi = lfilterZi(b, a)
        var filtered = lfilter(extended, b, a, zi, extended.first())
        filtered.reverse()
        filtered = lfilter(filtered, b, a, zi, filtered.first())
        filtered.reverse()
        return FloatArray(input.size) { index ->
            filtered[padLength + index].toFloat()
        }
    }

    /** scipy.signal.filtfilt 默认 padtype="odd" 的扩展方式。 */
    private fun oddExtend(input: FloatArray, edge: Int): DoubleArray {
        if (edge == 0) return DoubleArray(input.size) { input[it].toDouble() }
        val output = DoubleArray(input.size + edge * 2)
        val first = input.first().toDouble()
        val last = input.last().toDouble()
        for (i in 0 until edge) {
            output[i] = 2.0 * first - input[edge - i].toDouble()
        }
        for (i in input.indices) {
            output[edge + i] = input[i].toDouble()
        }
        for (i in 0 until edge) {
            output[edge + input.size + i] =
                2.0 * last - input[input.lastIndex - 1 - i].toDouble()
        }
        return output
    }

    /**
     * scipy.signal.lfilter_zi 的等价实现：
     * 解 (I - companion(a).T) * zi = b[1:] - a[1:] * b[0]。
     */
    private fun lfilterZi(b: DoubleArray, a: DoubleArray): DoubleArray {
        val order = a.size - 1
        if (order == 0) return DoubleArray(0)
        val matrix = Array(order) { DoubleArray(order) }
        val vector = DoubleArray(order)
        for (row in 0 until order) {
            matrix[row][row] = 1.0
            matrix[row][0] += a[row + 1]
            if (row < order - 1) matrix[row][row + 1] -= 1.0
            vector[row] = b[row + 1] - a[row + 1] * b[0]
        }
        return solveLinearSystem(matrix, vector)
    }

    /** Direct Form II transposed，与 scipy.signal.lfilter 的状态定义一致。 */
    private fun lfilter(
        input: DoubleArray,
        b: DoubleArray,
        a: DoubleArray,
        zi: DoubleArray,
        initialScale: Double
    ): DoubleArray {
        val order = b.size - 1
        val state = DoubleArray(order) { zi[it] * initialScale }
        val output = DoubleArray(input.size)
        for (sampleIndex in input.indices) {
            val sample = input[sampleIndex]
            val value = b[0] * sample + if (order > 0) state[0] else 0.0
            output[sampleIndex] = value
            for (stateIndex in 0 until order - 1) {
                state[stateIndex] =
                    b[stateIndex + 1] * sample +
                            state[stateIndex + 1] -
                            a[stateIndex + 1] * value
            }
            if (order > 0) {
                state[order - 1] = b[order] * sample - a[order] * value
            }
        }
        return output
    }

    private fun solveLinearSystem(
        sourceMatrix: Array<DoubleArray>,
        sourceVector: DoubleArray
    ): DoubleArray {
        val size = sourceVector.size
        val matrix = Array(size) { sourceMatrix[it].copyOf() }
        val vector = sourceVector.copyOf()
        for (column in 0 until size) {
            var pivot = column
            for (row in column + 1 until size) {
                if (kotlin.math.abs(matrix[row][column]) >
                    kotlin.math.abs(matrix[pivot][column])
                ) {
                    pivot = row
                }
            }
            require(kotlin.math.abs(matrix[pivot][column]) > 1e-15) {
                "滤波器初始状态矩阵不可逆"
            }
            if (pivot != column) {
                val row = matrix[column]
                matrix[column] = matrix[pivot]
                matrix[pivot] = row
                val value = vector[column]
                vector[column] = vector[pivot]
                vector[pivot] = value
            }

            val diagonal = matrix[column][column]
            for (index in column until size) matrix[column][index] /= diagonal
            vector[column] /= diagonal
            for (row in 0 until size) {
                if (row == column) continue
                val factor = matrix[row][column]
                if (factor == 0.0) continue
                for (index in column until size) {
                    matrix[row][index] -= factor * matrix[column][index]
                }
                vector[row] -= factor * vector[column]
            }
        }
        return vector
    }

    private fun FloatArray.isFinite(): Boolean = all(Float::isFinite)

    private const val TRAINING_PAD_LENGTH = 15
    private const val DISPLAY_PAD_LENGTH = 27
}

package com.example.input_ds.bci

import org.json.JSONArray
import org.json.JSONObject

/**
 * 可组合的双通道预处理。绘图与推理调用同一组批处理步骤，步骤顺序由
 * [EegPreprocessStep] 固定，避免多选时产生不确定结果。
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

    // scipy.signal.butter(4, [0.1 / 250, 40.0 / 250], btype="bandpass")
    private val csanetB = doubleArrayOf(
        0.002215442022975766, 0.0, -0.008861768091903064, 0.0,
        0.013292652137854596, 0.0, -0.008861768091903064, 0.0,
        0.002215442022975766
    )
    private val csanetA = doubleArrayOf(
        1.0, -6.693691566973611, 19.645825697627185, -33.0528927641038,
        34.89262138048841, -23.678748864838038, 10.089583587913928,
        -2.4680554995106556, 0.2653580293966755
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
        targetPoints: Int = 400,
        steps: Set<EegPreprocessStep> = EegPreprocessStep.DEFAULT
    ): Array<FloatArray>? {
        if (targetPoints <= 0 ||
            left.size < targetPoints ||
            right.size < targetPoints ||
            left.isEmpty() ||
            right.isEmpty()
        ) {
            return null
        }
        if (!left.isFinite() || !right.isFinite()) return null

        val leftFiltered = applyFilterSteps(left, steps) ?: return null
        val rightFiltered = applyFilterSteps(right, steps) ?: return null
        val result = arrayOf(
            applyWindowSteps(leftFiltered.copyOfRange(
                leftFiltered.size - targetPoints,
                leftFiltered.size
            ), steps),
            applyWindowSteps(rightFiltered.copyOfRange(
                rightFiltered.size - targetPoints,
                rightFiltered.size
            ), steps)
        )
        return result.takeIf { channels ->
            channels.all { channel -> channel.isFinite() }
        }
    }

    /** Executes the exact allow-listed preprocessing contract embedded in a trained ONNX model. */
    fun preprocess(
        left: FloatArray,
        right: FloatArray,
        contract: JSONObject
    ): Array<FloatArray>? = runCatching {
        val targetPoints = contract.getInt("window_points")
        require(targetPoints > 0 && left.size >= targetPoints && right.size >= targetPoints)
        require(left.isFinite() && right.isFinite())
        val streamSteps = contract.optJSONArray("stream_steps") ?: JSONArray()
        val windowSteps = contract.optJSONArray("window_steps") ?: JSONArray()
        fun channel(input: FloatArray): FloatArray {
            val streamed = applyContractSteps(input, streamSteps)
            val window = streamed.copyOfRange(streamed.size - targetPoints, streamed.size)
            return applyContractSteps(window, windowSteps)
        }
        arrayOf(channel(left), channel(right)).also { channels ->
            require(channels.all { channel -> channel.isFinite() })
        }
    }.getOrNull()

    /**
     * 对最近的显示窗口应用桌面 signal_monitor.py 使用的滤波。
     * 数据不足时与桌面端一致，直接返回原始副本。
     */
    fun filterForDisplay(data: FloatArray): FloatArray {
        return preprocessForDisplay(
            data,
            setOf(
                EegPreprocessStep.NOTCH_50_HZ,
                EegPreprocessStep.BANDPASS_0_1_100_HZ
            )
        ) ?: data.copyOf()
    }

    /** Processes the complete visible window with the same selected chain. */
    fun preprocessForDisplay(
        data: FloatArray,
        steps: Set<EegPreprocessStep>
    ): FloatArray? {
        if (data.isEmpty() || !data.isFinite()) return null
        val filtered = applyFilterSteps(data, steps) ?: return null
        return applyWindowSteps(filtered, steps).takeIf { it.isFinite() }
    }

    private fun applyFilterSteps(
        input: FloatArray,
        steps: Set<EegPreprocessStep>
    ): FloatArray? {
        var output = input.copyOf()
        for (step in EegPreprocessStep.entries) {
            if (step !in steps) continue
            output = when (step) {
                EegPreprocessStep.NOTCH_50_HZ ->
                    filterIfLongEnough(
                        output,
                        displayNotchB,
                        displayNotchA,
                        DISPLAY_PAD_LENGTH
                    ) ?: return null
                EegPreprocessStep.BANDPASS_0_1_100_HZ ->
                    filterIfLongEnough(
                        output,
                        displayBandB,
                        displayBandA,
                        DISPLAY_PAD_LENGTH
                    ) ?: return null
                EegPreprocessStep.BANDPASS_0_1_40_HZ ->
                    filterIfLongEnough(
                        output,
                        csanetB,
                        csanetA,
                        DISPLAY_PAD_LENGTH
                    ) ?: return null
                EegPreprocessStep.BANDPASS_1_45_HZ ->
                    filterIfLongEnough(
                        output,
                        trainingB,
                        trainingA,
                        TRAINING_PAD_LENGTH
                    ) ?: return null
                EegPreprocessStep.REMOVE_MEAN,
                EegPreprocessStep.Z_SCORE -> output
            }
        }
        return output
    }

    private fun applyContractSteps(input: FloatArray, steps: JSONArray): FloatArray {
        var output = input.copyOf()
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            output = when (step.getString("operation")) {
                "bandpass" -> {
                    require(step.optString("implementation", "butterworth_filtfilt") == "butterworth_filtfilt") {
                        "手机端当前仅支持窗口级 butterworth_filtfilt"
                    }
                    val low = step.getDouble("low_hz")
                    val high = step.getDouble("high_hz")
                    val order = step.getInt("order")
                    val coefficients = when {
                        low == 1.0 && high == 45.0 && order == 2 -> trainingB to trainingA
                        low == 0.1 && high == 40.0 && order == 4 -> csanetB to csanetA
                        else -> error("手机端不支持该带通参数：$low–$high Hz / $order 阶")
                    }
                    val padLength = 3 * maxOf(coefficients.first.size, coefficients.second.size)
                    filterIfLongEnough(output, coefficients.first, coefficients.second, padLength)
                        ?: error("信号长度不足以执行带通滤波")
                }
                "zscore" -> zScore(output, step.optDouble("epsilon", 1e-6))
                "scale" -> {
                    val factor = step.getDouble("factor")
                    val offset = step.optDouble("offset", 0.0)
                    FloatArray(output.size) { (output[it] * factor + offset).toFloat() }
                }
                "clip" -> {
                    val minimum = step.getDouble("minimum").toFloat()
                    val maximum = step.getDouble("maximum").toFloat()
                    FloatArray(output.size) { output[it].coerceIn(minimum, maximum) }
                }
                else -> error("不支持的预处理操作：${step.getString("operation")}")
            }
            require(output.isFinite())
        }
        return output
    }

    private fun filterIfLongEnough(
        input: FloatArray,
        numerator: DoubleArray,
        denominator: DoubleArray,
        preferredPadLength: Int
    ): FloatArray? {
        if (input.size < MIN_FILTER_POINTS) return null
        val padLength =
            minOf(input.size / 3, preferredPadLength).coerceAtLeast(1)
        return filtfilt(input, numerator, denominator, padLength)
            .takeIf { it.isFinite() }
    }

    private fun applyWindowSteps(
        input: FloatArray,
        steps: Set<EegPreprocessStep>
    ): FloatArray {
        var output = input
        if (EegPreprocessStep.REMOVE_MEAN in steps) {
            output = removeMean(output)
        }
        if (EegPreprocessStep.Z_SCORE in steps) {
            output = zScore(output)
        }
        return output
    }

    private fun removeMean(input: FloatArray): FloatArray {
        val mean = input.fold(0.0) { sum, value -> sum + value } / input.size
        return FloatArray(input.size) { index ->
            (input[index] - mean).toFloat()
        }
    }

    private fun zScore(input: FloatArray): FloatArray {
        return zScore(input, 1e-8)
    }

    private fun zScore(input: FloatArray, epsilon: Double): FloatArray {
        val centered = removeMean(input)
        val variance =
            centered.fold(0.0) { sum, value -> sum + value * value } /
                    centered.size
        val standardDeviation = kotlin.math.sqrt(variance)
        val denominator = maxOf(standardDeviation, epsilon)
        return FloatArray(centered.size) { index ->
            (centered[index] / denominator).toFloat()
        }
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
    private const val MIN_FILTER_POINTS = 10
}

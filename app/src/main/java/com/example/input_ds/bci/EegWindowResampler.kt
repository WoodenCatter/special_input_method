package com.example.input_ds.bci

import kotlin.math.floor

/**
 * 将检测窗口线性重采样为 ONNX 固定输入点数。
 *
 * 当前实时检测窗为 400 点（800 ms），当前模型输入为 500 点。
 * 重采样只改变时间轴点数，不跨通道混合数据。
 */
object EegWindowResampler {

    fun resample(channels: Array<FloatArray>, targetPoints: Int): Array<FloatArray>? {
        if (targetPoints < 2 ||
            channels.isEmpty() ||
            channels.any { it.size < 2 || it.any { value -> !value.isFinite() } }
        ) {
            return null
        }
        return Array(channels.size) { channel ->
            resampleChannel(channels[channel], targetPoints)
        }
    }

    private fun resampleChannel(source: FloatArray, targetPoints: Int): FloatArray {
        if (source.size == targetPoints) return source.copyOf()
        val scale = (source.size - 1).toDouble() / (targetPoints - 1)
        return FloatArray(targetPoints) { targetIndex ->
            val position = targetIndex * scale
            val lower = floor(position).toInt()
            val upper = minOf(lower + 1, source.lastIndex)
            val fraction = (position - lower).toFloat()
            source[lower] * (1f - fraction) + source[upper] * fraction
        }
    }
}

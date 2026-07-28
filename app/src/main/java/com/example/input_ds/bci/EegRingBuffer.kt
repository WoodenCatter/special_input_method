package com.example.input_ds.bci

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 双通道环形缓冲区（线程安全）
 * 左耳 leftBuffer / 右耳 rightBuffer
 */
class EegRingBuffer(private val capacitySeconds: Int = 10, val sampleRate: Int = 500) {
    private val capacity = capacitySeconds * sampleRate
    private val lock = ReentrantLock()

    private var leftBuf = FloatArray(capacity)
    private var rightBuf = FloatArray(capacity)
    @Volatile var leftCount = 0; private set
    @Volatile var rightCount = 0; private set

    fun pushLeft(values: FloatArray) = lock.withLock {
        values.forEach { v ->
            leftBuf[leftCount % capacity] = v
            leftCount++
        }
    }

    fun pushRight(values: FloatArray) = lock.withLock {
        values.forEach { v ->
            rightBuf[rightCount % capacity] = v
            rightCount++
        }
    }

    /**
     * Publishes an already matched stereo packet on one shared sample clock.
     * Readers can therefore never consume a different number of left/right
     * samples from this write.
     */
    fun pushStereo(left: FloatArray, right: FloatArray) = lock.withLock {
        val size = minOf(left.size, right.size)
        val start = minOf(leftCount, rightCount)
        for (index in 0 until size) {
            val target = (start + index) % capacity
            leftBuf[target] = left[index]
            rightBuf[target] = right[index]
        }
        val end = start + size
        leftCount = end
        rightCount = end
    }

    fun synchronizedCount(): Int = lock.withLock {
        minOf(leftCount, rightCount)
    }

    /**
     * 获取最近 N 秒的左耳数据
     */
    fun getRecentLeft(seconds: Float): FloatArray = lock.withLock {
        val n = (seconds * sampleRate).toInt().coerceAtMost(minOf(leftCount, capacity))
        if (n <= 0) FloatArray(0)
        else FloatArray(n) { i -> leftBuf[(leftCount - n + i) % capacity] }
    }

    fun getRecentRight(seconds: Float): FloatArray = lock.withLock {
        val n = (seconds * sampleRate).toInt().coerceAtMost(minOf(rightCount, capacity))
        if (n <= 0) FloatArray(0)
        else FloatArray(n) { i -> rightBuf[(rightCount - n + i) % capacity] }
    }

    /** 获取指定时间范围的左耳数据（按采样计数） */
    fun getLeftRange(startSample: Int, endSample: Int): FloatArray = lock.withLock {
        getRange(leftBuf, leftCount, startSample, endSample)
    }

    fun getRightRange(startSample: Int, endSample: Int): FloatArray = lock.withLock {
        getRange(rightBuf, rightCount, startSample, endSample)
    }

    private fun getRange(
        buffer: FloatArray,
        count: Int,
        startSample: Int,
        endSample: Int
    ): FloatArray {
        val earliestAvailable = (count - capacity).coerceAtLeast(0)
        val safeStart = startSample.coerceIn(earliestAvailable, count)
        val safeEnd = endSample.coerceIn(safeStart, count)
        return FloatArray(safeEnd - safeStart) { i -> buffer[(safeStart + i) % capacity] }
    }

    fun reset() = lock.withLock {
        leftBuf = FloatArray(capacity); rightBuf = FloatArray(capacity)
        leftCount = 0; rightCount = 0
    }
}

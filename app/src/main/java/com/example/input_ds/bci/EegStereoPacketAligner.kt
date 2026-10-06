package com.example.input_ds.bci

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Keeps the two raw ear streams independent and aligns complete windows on demand.
 *
 * Each ear owns a monotonically increasing sample index. Packet-end arrival times
 * are retained as clock anchors; a rolling affine model maps sample index to the
 * Android monotonic clock. No aligned sample is permanently committed, so every
 * plot, inference or collection window is rebuilt from the current clock models.
 */
internal class EegStereoPacketAligner(
    private val sampleRate: Int = DEFAULT_SAMPLE_RATE,
    private val historySeconds: Int = DEFAULT_HISTORY_SECONDS,
    private val clockWindowSeconds: Int = DEFAULT_CLOCK_WINDOW_SECONDS
) {
    data class Discontinuity(
        val side: String,
        val missingPackets: Int,
        val reason: String
    )

    data class AppendResult(
        val acceptedSamples: Int,
        val discontinuity: Discontinuity? = null
    )

    data class AlignedSnapshot(
        val left: FloatArray,
        val right: FloatArray,
        val startTimeUs: Long,
        val endTimeUs: Long,
        val quality: Float,
        val leftRateHz: Double,
        val rightRateHz: Double
    )

    data class Stats(
        val leftPacketsReceived: Long,
        val rightPacketsReceived: Long,
        val leftSamplesReceived: Long,
        val rightSamplesReceived: Long,
        val leftDroppedPackets: Long,
        val rightDroppedPackets: Long,
        val leftMissingPackets: Long,
        val rightMissingPackets: Long,
        val pendingLeftSamples: Int,
        val pendingRightSamples: Int,
        val lastLeftCounter: Int?,
        val lastRightCounter: Int?,
        val leftRateHz: Double?,
        val rightRateHz: Double?,
        val leftClockResidualP95Ms: Double?,
        val rightClockResidualP95Ms: Double?,
        val latestArrivalSkewMs: Double?,
        val timelineRestarts: Long
    )

    private data class IndexedSample(val index: Long, val value: Float)
    private data class ClockAnchor(val sampleIndex: Long, val arrivalTimeUs: Long)
    private data class ClockModel(
        val usPerSample: Double,
        val interceptUs: Double,
        val residualP95Us: Double,
        val anchorCount: Int
    ) {
        fun timeAt(index: Long): Double = interceptUs + usPerSample * index
        val rateHz: Double get() = 1_000_000.0 / usPerSample
    }

    private class ChannelState(private val nominalPeriodUs: Double) {
        val samples = ArrayDeque<IndexedSample>()
        val anchors = ArrayDeque<ClockAnchor>()
        var nextSampleIndex = 0L
        var lastCounter: Int? = null
        var lastArrivalTimeUs: Long? = null
        var lastPacketSamples = DEFAULT_PACKET_SAMPLES
        var totalSamples = 0L
        var model = ClockModel(nominalPeriodUs, 0.0, 0.0, 0)

        fun clearHistory() {
            samples.clear()
            anchors.clear()
            lastArrivalTimeUs = null
            model = ClockModel(nominalPeriodUs, 0.0, 0.0, 0)
        }

        fun reset() {
            clearHistory()
            nextSampleIndex = 0L
            lastCounter = null
            lastPacketSamples = DEFAULT_PACKET_SAMPLES
            totalSamples = 0L
        }
    }

    private val nominalPeriodUs = 1_000_000.0 / sampleRate
    private val maxHistorySamples = sampleRate * historySeconds
    private val clockWindowUs = clockWindowSeconds * 1_000_000L
    private val left = ChannelState(nominalPeriodUs)
    private val right = ChannelState(nominalPeriodUs)

    private var leftPacketsReceived = 0L
    private var rightPacketsReceived = 0L
    private var leftDroppedPackets = 0L
    private var rightDroppedPackets = 0L
    private var leftMissingPackets = 0L
    private var rightMissingPackets = 0L
    private var timelineRestarts = 0L
    private var automaticRestartSide: String? = null

    init {
        require(sampleRate > 0)
        require(historySeconds > 0)
        require(clockWindowSeconds > 1)
    }

    @Synchronized
    fun appendAtTimeUs(packet: EegDataParser.EegPacket, packetEndTimeUs: Long): AppendResult {
        val isLeft = packet.earSide == LEFT
        val state = if (isLeft) left else right
        if (isLeft) leftPacketsReceived++ else rightPacketsReceived++

        val counter = checkCounter(state, packet.packetCount, isLeft)
        if (counter.drop) return AppendResult(0)
        var discontinuity = counter.discontinuity
        if (discontinuity != null && !restartHistoryFor(discontinuity.side)) {
            discontinuity = null
        }

        val firstIndex = state.nextSampleIndex
        packet.samples.forEachIndexed { offset, value ->
            state.samples.addLast(IndexedSample(firstIndex + offset, value))
        }
        state.nextSampleIndex += packet.samples.size
        state.totalSamples += packet.samples.size
        state.lastPacketSamples = packet.samples.size.coerceAtLeast(1)
        state.lastArrivalTimeUs = packetEndTimeUs
        if (packet.samples.isNotEmpty()) {
            state.anchors.addLast(ClockAnchor(state.nextSampleIndex - 1L, packetEndTimeUs))
        }
        trimAndRefit(state)
        return AppendResult(packet.samples.size, discontinuity)
    }

    @Synchronized
    fun latestAlignedSnapshot(targetPoints: Int): AlignedSnapshot? {
        if (targetPoints < 2) return null
        val commonEnd = commonLatestTimeUsInternal() ?: return null
        val start = commonEnd - ((targetPoints - 1) * nominalPeriodUs).roundToLong()
        return alignedSnapshotInternal(start, commonEnd, targetPoints)
    }

    @Synchronized
    fun latestAlignedSnapshotAtMost(maxPoints: Int, minPoints: Int = 2): AlignedSnapshot? {
        if (maxPoints < minPoints || minPoints < 2 || !hasDualCoverage(minPoints)) return null
        val commonEnd = commonLatestTimeUsInternal() ?: return null
        val commonStart = maxOf(
            left.model.timeAt(left.samples.first().index),
            right.model.timeAt(right.samples.first().index)
        ).let(::ceil).toLong()
        val available = floor((commonEnd - commonStart) / nominalPeriodUs).toInt() + 1
        val points = minOf(maxPoints, available)
        if (points < minPoints) return null
        val start = commonEnd - ((points - 1) * nominalPeriodUs).roundToLong()
        return alignedSnapshotInternal(start, commonEnd, points)
    }

    @Synchronized
    fun alignedSnapshot(
        startTimeUs: Long,
        endTimeUs: Long,
        targetPoints: Int
    ): AlignedSnapshot? {
        if (targetPoints < 2 || endTimeUs <= startTimeUs) return null
        val commonLatest = commonLatestTimeUsInternal() ?: return null
        if (commonLatest < endTimeUs) return null
        return alignedSnapshotInternal(startTimeUs, endTimeUs, targetPoints)
    }

    @Synchronized
    fun commonLatestTimeUs(): Long? = commonLatestTimeUsInternal()

    @Synchronized
    fun hasDualCoverage(minPoints: Int = 2): Boolean =
        left.samples.size >= minPoints && right.samples.size >= minPoints &&
            left.model.anchorCount > 0 && right.model.anchorCount > 0

    @Synchronized
    fun restartTimeline() {
        restartHistoryInternal()
    }

    @Synchronized
    fun stats(): Stats = Stats(
        leftPacketsReceived = leftPacketsReceived,
        rightPacketsReceived = rightPacketsReceived,
        leftSamplesReceived = left.totalSamples,
        rightSamplesReceived = right.totalSamples,
        leftDroppedPackets = leftDroppedPackets,
        rightDroppedPackets = rightDroppedPackets,
        leftMissingPackets = leftMissingPackets,
        rightMissingPackets = rightMissingPackets,
        pendingLeftSamples = left.samples.size,
        pendingRightSamples = right.samples.size,
        lastLeftCounter = left.lastCounter,
        lastRightCounter = right.lastCounter,
        leftRateHz = left.model.rateHz.takeIf { left.model.anchorCount >= 2 },
        rightRateHz = right.model.rateHz.takeIf { right.model.anchorCount >= 2 },
        leftClockResidualP95Ms = (left.model.residualP95Us / 1_000.0)
            .takeIf { left.model.anchorCount >= 2 },
        rightClockResidualP95Ms = (right.model.residualP95Us / 1_000.0)
            .takeIf { right.model.anchorCount >= 2 },
        latestArrivalSkewMs = if (left.lastArrivalTimeUs != null && right.lastArrivalTimeUs != null) {
            (left.lastArrivalTimeUs!! - right.lastArrivalTimeUs!!) / 1_000.0
        } else null,
        timelineRestarts = timelineRestarts
    )

    @Synchronized
    fun reset() {
        left.reset()
        right.reset()
        leftPacketsReceived = 0L
        rightPacketsReceived = 0L
        leftDroppedPackets = 0L
        rightDroppedPackets = 0L
        leftMissingPackets = 0L
        rightMissingPackets = 0L
        timelineRestarts = 0L
        automaticRestartSide = null
    }

    private fun alignedSnapshotInternal(
        startTimeUs: Long,
        endTimeUs: Long,
        targetPoints: Int
    ): AlignedSnapshot? {
        val leftValues = resample(left, startTimeUs, endTimeUs, targetPoints) ?: return null
        val rightValues = resample(right, startTimeUs, endTimeUs, targetPoints) ?: return null
        val residual = maxOf(left.model.residualP95Us, right.model.residualP95Us)
        val quality = when {
            residual <= 5_000.0 -> 1f
            residual >= 50_000.0 -> 0.5f
            else -> (1.0 - (residual - 5_000.0) / 90_000.0).toFloat()
        }
        automaticRestartSide = null
        return AlignedSnapshot(
            leftValues,
            rightValues,
            startTimeUs,
            endTimeUs,
            quality,
            left.model.rateHz,
            right.model.rateHz
        )
    }

    private fun resample(
        state: ChannelState,
        startTimeUs: Long,
        endTimeUs: Long,
        targetPoints: Int
    ): FloatArray? {
        if (state.samples.size < 2 || state.model.anchorCount == 0) return null
        val samples = state.samples.toList()
        val first = samples.first()
        val last = samples.last()
        val firstTime = state.model.timeAt(first.index)
        val lastTime = state.model.timeAt(last.index)
        if (startTimeUs < firstTime || endTimeUs > lastTime) return null
        val duration = (endTimeUs - startTimeUs).toDouble()
        return FloatArray(targetPoints) { point ->
            val targetTime = startTimeUs + duration * point / (targetPoints - 1)
            val fractionalIndex = (targetTime - state.model.interceptUs) / state.model.usPerSample
            val lowerIndex = floor(fractionalIndex).toLong().coerceIn(first.index, last.index)
            val upperIndex = (lowerIndex + 1L).coerceAtMost(last.index)
            val lowerOffset = (lowerIndex - first.index).toInt()
            val upperOffset = (upperIndex - first.index).toInt()
            if (lowerOffset !in samples.indices || upperOffset !in samples.indices) return null
            val fraction = (fractionalIndex - lowerIndex).coerceIn(0.0, 1.0)
            (samples[lowerOffset].value +
                (samples[upperOffset].value - samples[lowerOffset].value) * fraction).toFloat()
        }
    }

    private fun commonLatestTimeUsInternal(): Long? {
        if (!hasDualCoverage()) return null
        return minOf(
            left.model.timeAt(left.samples.last().index),
            right.model.timeAt(right.samples.last().index)
        ).let(::floor).toLong()
    }

    private fun trimAndRefit(state: ChannelState) {
        while (state.samples.size > maxHistorySamples) state.samples.removeFirst()
        val latestArrival = state.lastArrivalTimeUs ?: return
        while (state.anchors.size > MIN_CLOCK_ANCHORS &&
            latestArrival - state.anchors.first().arrivalTimeUs > clockWindowUs
        ) state.anchors.removeFirst()
        while (state.anchors.size > MAX_CLOCK_ANCHORS) state.anchors.removeFirst()
        state.model = fitClock(state.anchors)
    }

    private fun fitClock(anchors: ArrayDeque<ClockAnchor>): ClockModel {
        if (anchors.isEmpty()) return ClockModel(nominalPeriodUs, 0.0, 0.0, 0)
        if (anchors.size == 1) {
            val only = anchors.first()
            return ClockModel(
                nominalPeriodUs,
                only.arrivalTimeUs - nominalPeriodUs * only.sampleIndex,
                0.0,
                1
            )
        }
        val points = anchors.toList()
        val meanIndex = points.map { it.sampleIndex.toDouble() }.average()
        val meanTime = points.map { it.arrivalTimeUs.toDouble() }.average()
        var numerator = 0.0
        var denominator = 0.0
        points.forEach { point ->
            val dx = point.sampleIndex - meanIndex
            numerator += dx * (point.arrivalTimeUs - meanTime)
            denominator += dx * dx
        }
        val fittedSlope = if (denominator > 0.0) numerator / denominator else nominalPeriodUs
        val slope = fittedSlope.coerceIn(
            nominalPeriodUs * MIN_CLOCK_SCALE,
            nominalPeriodUs * MAX_CLOCK_SCALE
        )
        // BLE latency is positive and bursty. A low residual percentile anchors
        // the clock close to the least-delayed callbacks instead of their mean.
        val offsets = points.map { it.arrivalTimeUs - slope * it.sampleIndex }.sorted()
        val intercept = percentile(offsets, CLOCK_INTERCEPT_PERCENTILE)
        val residuals = points.map { abs(it.arrivalTimeUs - (intercept + slope * it.sampleIndex)) }
            .sorted()
        return ClockModel(slope, intercept, percentile(residuals, 0.95), points.size)
    }

    private fun percentile(sorted: List<Double>, fraction: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val position = fraction.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(position).toInt()
        val upper = minOf(lower + 1, sorted.lastIndex)
        val part = position - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * part
    }

    private data class CounterCheck(
        val drop: Boolean = false,
        val discontinuity: Discontinuity? = null
    )

    private fun checkCounter(state: ChannelState, rawCounter: Int, isLeft: Boolean): CounterCheck {
        val raw = rawCounter and 0xFF
        val previous = state.lastCounter
        if (previous == null) {
            state.lastCounter = raw
            return CounterCheck()
        }
        val forward = (raw - previous) and 0xFF
        return when {
            forward == 0 || forward > 127 -> {
                if (isLeft) leftDroppedPackets++ else rightDroppedPackets++
                CounterCheck(drop = true)
            }
            forward == 1 -> {
                state.lastCounter = raw
                CounterCheck()
            }
            else -> {
                state.lastCounter = raw
                val missing = forward - 1
                state.nextSampleIndex += missing.toLong() * state.lastPacketSamples
                if (isLeft) leftMissingPackets += missing else rightMissingPackets += missing
                CounterCheck(
                    discontinuity = Discontinuity(
                        if (isLeft) LEFT else RIGHT,
                        missing,
                        "${if (isLeft) "左耳" else "右耳"}包计数缺失 $missing 包"
                    )
                )
            }
        }
    }

    private fun restartHistoryInternal() {
        left.clearHistory()
        right.clearHistory()
        automaticRestartSide = null
        timelineRestarts++
    }

    private fun restartHistoryFor(side: String): Boolean {
        if (automaticRestartSide != null && automaticRestartSide != side &&
            left.samples.isEmpty() xor right.samples.isEmpty()
        ) return false
        left.clearHistory()
        right.clearHistory()
        automaticRestartSide = side
        timelineRestarts++
        return true
    }

    private companion object {
        const val LEFT = "left"
        const val RIGHT = "right"
        const val DEFAULT_SAMPLE_RATE = 500
        const val DEFAULT_PACKET_SAMPLES = 50
        const val DEFAULT_HISTORY_SECONDS = 35
        const val DEFAULT_CLOCK_WINDOW_SECONDS = 10
        const val MIN_CLOCK_ANCHORS = 4
        const val MAX_CLOCK_ANCHORS = 160
        const val MIN_CLOCK_SCALE = 0.98
        const val MAX_CLOCK_SCALE = 1.02
        const val CLOCK_INTERCEPT_PERCENTILE = 0.10
    }
}

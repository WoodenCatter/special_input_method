package com.example.input_ds.bci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class BciPipelineTest {

    @Test
    fun preprocessingSelectionCannotDisableUnifiedBandpass() {
        val left = FloatArray(600) { index ->
            (50.0 * sin(2.0 * PI * 10.0 * index / 500.0)).toFloat()
        }
        val right = FloatArray(600) { -left[it] }

        val emptySelection = EegPreprocessor.preprocess(
            left,
            right,
            targetPoints = 400,
            steps = emptySet()
        )!!
        val defaultSelection = EegPreprocessor.preprocess(left, right, targetPoints = 400)!!

        assertArrayEquals(defaultSelection[0], emptySelection[0], 0f)
        assertArrayEquals(defaultSelection[1], emptySelection[1], 0f)
    }

    @Test
    fun unifiedBandpassIsSharedByPlotAndInference() {
        val input = FloatArray(600) { index ->
            (80.0 * sin(2.0 * PI * 8.0 * index / 500.0)).toFloat()
        }

        val plot = EegPreprocessor.preprocessForDisplay(input, EegPreprocessStep.DEFAULT)!!
        val model = EegPreprocessor.preprocess(
            input,
            input,
            targetPoints = 400,
            steps = EegPreprocessStep.DEFAULT
        )!![0]

        assertArrayEquals(plot.copyOfRange(200, 600), model, 1e-6f)
    }

    @Test
    fun packetAssembler_handlesNoiseFragmentsAndMultiplePackets() {
        val left = buildPacket(earFlag = 0, rawSample = 1)
        val right = buildPacket(earFlag = 1, rawSample = -1, lengthAsSampleCount = true)
        val realDeviceFormat = buildPacket(
            earFlag = 0,
            rawSample = 2,
            lengthIncludesStatus = true
        )
        val assembler = EegPacketAssembler()

        assertTrue(assembler.append(byteArrayOf(0x01, 0x02) + left.copyOfRange(0, 6)).isEmpty())
        val packets = assembler.append(left.copyOfRange(6, left.size) + right + realDeviceFormat)

        assertEquals(3, packets.size)
        assertArrayEquals(left, packets[0])
        assertArrayEquals(right, packets[1])
        assertArrayEquals(realDeviceFormat, packets[2])
    }

    @Test
    fun eegParser_decodesSigned24BitSamples() {
        val positive = EegDataParser.parseEegData(buildPacket(0, 1), "left")
        val negative = EegDataParser.parseEegData(
            buildPacket(1, -1, lengthAsSampleCount = true),
            "right"
        )
        val realDeviceFormat = EegDataParser.parseEegData(
            buildPacket(0, 2, lengthIncludesStatus = true),
            "left"
        )

        assertNotNull(positive)
        assertNotNull(negative)
        assertEquals(1, positive!!.samples.size)
        assertTrue(positive.samples[0] > 0f)
        assertTrue(negative!!.samples[0] < 0f)
        assertEquals(-positive.samples[0], negative.samples[0], 1e-6f)
        assertEquals(positive.samples[0] * 2f, realDeviceFormat!!.samples[0], 1e-6f)
    }

    @Test
    fun ringBuffer_returnsOnlyAvailableWrappedRange() {
        val buffer = EegRingBuffer(capacitySeconds = 1, sampleRate = 4)
        buffer.pushLeft(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))

        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f), buffer.getRecentLeft(1f), 0f)
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), buffer.getLeftRange(0, 5), 0f)
    }

    @Test
    fun preprocessor_returnsFiniteUnnormalizedWheelchairWindow() {
        val points = 3_000
        val left = FloatArray(points) { i ->
            (
                    80.0 * sin(2.0 * PI * 10.0 * i / 500.0) +
                            20.0 * sin(2.0 * PI * 50.0 * i / 500.0)
                    ).toFloat()
        }
        val right = FloatArray(points) { i -> left[i] * 0.8f + 2f }

        val result = EegPreprocessor.preprocess(left, right, 1_000)

        assertNotNull(result)
        assertEquals(2, result!!.size)
        result.forEach { channel ->
            assertEquals(1_000, channel.size)
            assertTrue(channel.all { it.isFinite() })
            val rms = sqrt(channel.map { it * it }.average())
            assertTrue("model input must not be Z-scored", rms > 20.0)
        }
    }

    @Test
    fun preprocessor_supportsInputMethod800msWindow() {
        val history = FloatArray(1_500) { index ->
            (
                    60.0 * sin(2.0 * PI * 8.0 * index / 500.0) +
                            15.0 * sin(2.0 * PI * 50.0 * index / 500.0)
                    ).toFloat()
        }

        val result = EegPreprocessor.preprocess(history, history, 400)

        assertNotNull(result)
        assertEquals(400, result!![0].size)
        assertEquals(400, result[1].size)
        assertTrue(result.all { channel -> channel.all(Float::isFinite) })
        assertTrue(
            "800ms model input must keep physical amplitude",
            sqrt(result[0].map { it * it }.average()) > 20.0
        )
    }

    @Test
    fun wheelchairPreprocessor_matchesDesktopScipyReference() {
        val points = 3_000
        val input = FloatArray(points) { index ->
            (
                    10_000.0 +
                            0.25 * index +
                            100.0 * sin(2.0 * PI * 2.0 * index / 500.0) +
                            20.0 * sin(2.0 * PI * 50.0 * index / 500.0)
                    ).toFloat()
        }

        val actual = EegPreprocessor.preprocess(input, input, 1_000)!![0]
        val indices = intArrayOf(0, 1, 10, 100, 249, 250, 500, 900, 998, 999)
        val scipy = floatArrayOf(
            0.0023298643f, 6.936291f, 23.745108f, 56.137608f,
            -6.8768725f, 0.05671913f, -0.90521246f, -32.08567f,
            -18.694313f, -18.673927f
        )

        indices.indices.forEach { position ->
            assertEquals(
                "index=${indices[position]}",
                scipy[position],
                actual[indices[position]],
                3e-3f
            )
        }
    }

    @Test
    fun legacyDisplayHelperUsesUnifiedBandpass() {
        val points = 2_500
        val input = FloatArray(points) { index ->
            (
                    10_000.0 +
                            0.025 * index +
                            100.0 * sin(2.0 * PI * 2.0 * index / 500.0) +
                            20.0 * sin(2.0 * PI * 50.0 * index / 500.0)
                    ).toFloat()
        }

        val actual = EegPreprocessor.filterForDisplay(input)
        val unified = EegPreprocessor.preprocessForDisplay(input, EegPreprocessStep.DEFAULT)!!

        assertArrayEquals(unified, actual, 0f)
    }

    @Test
    fun windowResampler_maps400PointWindowTo500PointModelInput() {
        val source = FloatArray(400) { it.toFloat() }

        val result = EegWindowResampler.resample(arrayOf(source, source), 500)

        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(500, result[0].size)
        assertEquals(0f, result[0].first(), 0f)
        assertEquals(399f, result[0].last(), 0f)
        assertEquals(199.5f, result[0][250], 0.5f)
        assertTrue(result.all { channel -> channel.all(Float::isFinite) })
    }

    @Test
    fun scopePlotBuffer_overwritesInPlaceAndWrapsCursor() {
        val buffer = EegScopePlotBuffer(capacity = 5)
        buffer.append(floatArrayOf(1f, 2f, 3f))
        buffer.append(floatArrayOf(4f, 5f, 6f))

        val snapshot = buffer.snapshot()

        assertArrayEquals(
            floatArrayOf(6f, 2f, 3f, 4f, 5f),
            snapshot.values,
            0f
        )
        assertEquals(1, snapshot.writePosition)
        assertEquals(5, snapshot.validCount)
        assertEquals(6f, snapshot.current, 0f)
        assertEquals(4f, snapshot.mean, 0f)
        assertEquals(4f, snapshot.peakToPeak, 0f)
    }

    @Test
    fun stereoPacketAligner_resamplesBothEarsOnOneTimestampGrid() {
        val aligner = EegStereoPacketAligner()
        val left = FloatArray(50) { index -> (902 + index * 2).toFloat() }
        val right = FloatArray(50) { index -> (913 + index * 2).toFloat() }

        aligner.appendAtTimeUs(eegPacket("left", 10, *left), 1_000_000L)
        aligner.appendAtTimeUs(eegPacket("right", 99, *right), 1_011_000L)

        val stereo = aligner.alignedSnapshot(913_000L, 999_000L, 44)!!
        assertEquals(913_000L, stereo.startTimeUs)
        assertEquals(999_000L, stereo.endTimeUs)
        assertArrayEquals(stereo.left, stereo.right, 1e-4f)
        assertEquals(913f, stereo.left.first(), 1e-4f)
        assertEquals(999f, stereo.left.last(), 1e-4f)
    }

    @Test
    fun stereoPacketAligner_counterOffsetDoesNotMoveStereoPhase() {
        val aligner = EegStereoPacketAligner()
        val values = FloatArray(50) { it.toFloat() }

        aligner.appendAtTimeUs(eegPacket("left", 10, *values), 1_000_000L)
        aligner.appendAtTimeUs(eegPacket("right", 200, *values), 1_000_000L)
        val stereo = aligner.latestAlignedSnapshot(50)!!

        assertArrayEquals(values, stereo.left, 0f)
        assertArrayEquals(values, stereo.right, 0f)
    }

    @Test
    fun stereoPacketAligner_reportsSingleEarPacketLossImmediately() {
        val aligner = EegStereoPacketAligner()
        val packet = FloatArray(50) { it.toFloat() }
        aligner.appendAtTimeUs(eegPacket("left", 10, *packet), 1_000_000L)
        aligner.appendAtTimeUs(eegPacket("right", 70, *packet), 1_000_000L)

        val result = aligner.appendAtTimeUs(
            eegPacket("left", 12, *packet),
            1_200_000L
        )

        assertNotNull(result.discontinuity)
        assertEquals("left", result.discontinuity!!.side)
        assertEquals(1, result.discontinuity!!.missingPackets)
        assertEquals(50, result.acceptedSamples)
        assertNull(aligner.latestAlignedSnapshot(50))
        assertEquals(1, aligner.stats().leftMissingPackets)
    }

    @Test
    fun stereoPacketAligner_dropsDuplicatesWithoutRewindingTimeline() {
        val aligner = EegStereoPacketAligner()
        val packet = FloatArray(50) { it.toFloat() }

        aligner.appendAtTimeUs(eegPacket("left", 10, *packet), 1_000_000L)
        val duplicate = aligner.appendAtTimeUs(
            eegPacket("left", 10, *FloatArray(50) { 99f }),
            1_100_000L
        )

        assertEquals(0, duplicate.acceptedSamples)
        assertNull(duplicate.discontinuity)
        assertEquals(1, aligner.stats().leftDroppedPackets)
    }

    @Test
    fun stereoPacketAligner_eachEarCounterWrapRemainsContinuous() {
        val aligner = EegStereoPacketAligner()
        val packet = FloatArray(50) { it.toFloat() }
        var arrivalUs = 1_000_000L

        for (counter in listOf(254, 255, 0, 1)) {
            assertNull(
                aligner.appendAtTimeUs(
                    eegPacket("left", counter, *packet),
                    arrivalUs
                ).discontinuity
            )
            assertNull(
                aligner.appendAtTimeUs(
                    eegPacket("right", counter, *packet),
                    arrivalUs
                ).discontinuity
            )
            arrivalUs += 100_000L
        }

        assertEquals(0, aligner.stats().leftMissingPackets)
        assertEquals(0, aligner.stats().rightMissingPackets)
        assertEquals(200, aligner.stats().leftSamplesReceived)
        assertEquals(200, aligner.stats().rightSamplesReceived)
        assertNotNull(aligner.latestAlignedSnapshot(200))
    }

    @Test
    fun stereoPacketAligner_refitsLongRunningIndependentEarClocks() {
        val aligner = EegStereoPacketAligner()
        val packetSamples = 50
        val packetCount = 6_000 // ten minutes at ten packets per second
        val startUs = 5_000_000L
        val leftPacketUs = 100_000.0
        val rightPacketUs = 100_120.0

        repeat(packetCount) { packetIndex ->
            val leftEnd = (startUs + (packetIndex + 1) * leftPacketUs).toLong()
            val rightEnd = (startUs + (packetIndex + 1) * rightPacketUs).toLong()
            val leftValues = FloatArray(packetSamples) { sample ->
                val absolute = packetIndex * packetSamples + sample
                ((startUs + (absolute + 1) * leftPacketUs / packetSamples) / 1_000.0).toFloat()
            }
            val rightValues = FloatArray(packetSamples) { sample ->
                val absolute = packetIndex * packetSamples + sample
                ((startUs + (absolute + 1) * rightPacketUs / packetSamples) / 1_000.0).toFloat()
            }
            aligner.appendAtTimeUs(
                eegPacket("left", packetIndex and 0xFF, *leftValues),
                leftEnd
            )
            aligner.appendAtTimeUs(
                eegPacket("right", packetIndex and 0xFF, *rightValues),
                rightEnd
            )
        }

        val stats = aligner.stats()
        assertEquals(500.0, stats.leftRateHz!!, 0.1)
        assertEquals(499.4007, stats.rightRateHz!!, 0.1)
        val window = aligner.latestAlignedSnapshot(2_500)!!
        assertArrayEquals(window.left, window.right, 0.2f)
        assertTrue(window.quality > 0.9f)
    }

    @Test
    fun callbackTimestampPolicy_expandsBatchInChronologicalOrder() {
        val times = EegCallbackTimestampPolicy.packetEndTimesUs(
            callbackTimeUs = 1_000_000L,
            packetSampleCounts = listOf(50, 50, 50)
        )

        assertArrayEquals(
            longArrayOf(800_000L, 900_000L, 1_000_000L),
            times
        )
    }

    @Test
    fun stereoPacketAligner_keepsFirstNewPacketWhenBothEarsReportSameGap() {
        val aligner = EegStereoPacketAligner()
        val packet = FloatArray(50) { it.toFloat() }
        aligner.appendAtTimeUs(eegPacket("left", 1, *packet), 1_000_000L)
        aligner.appendAtTimeUs(eegPacket("right", 41, *packet), 1_000_000L)

        val leftGap = aligner.appendAtTimeUs(
            eegPacket("left", 3, *packet),
            1_200_000L
        )
        val rightGap = aligner.appendAtTimeUs(
            eegPacket("right", 43, *packet),
            1_200_000L
        )

        assertNotNull(leftGap.discontinuity)
        assertNull(rightGap.discontinuity)
        assertEquals(50, rightGap.acceptedSamples)
        assertNotNull(aligner.latestAlignedSnapshot(50))
        assertEquals(1, aligner.stats().timelineRestarts)
    }

    @Test
    fun stereoRingBuffer_publishesBothChannelsOnOneClock() {
        val buffer = EegRingBuffer(capacitySeconds = 1, sampleRate = 5)

        buffer.pushStereo(
            floatArrayOf(1f, 2f, 3f),
            floatArrayOf(4f, 5f, 6f)
        )

        assertEquals(3, buffer.synchronizedCount())
        assertEquals(3, buffer.leftCount)
        assertEquals(3, buffer.rightCount)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), buffer.getRecentLeft(1f), 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), buffer.getRecentRight(1f), 0f)
    }

    @Test
    fun stereoRingBuffer_readsBothChannelsFromOneSnapshot() {
        val buffer = EegRingBuffer(capacitySeconds = 1, sampleRate = 5)
        buffer.pushStereo(floatArrayOf(1f, 2f, 3f), floatArrayOf(4f, 5f, 6f))

        val range = buffer.getStereoRange(1, 3)

        assertArrayEquals(floatArrayOf(2f, 3f), range.left, 0f)
        assertArrayEquals(floatArrayOf(5f, 6f), range.right, 0f)
    }

    @Test
    fun scopePlotBuffer_alignsCursorToAbsoluteSampleClock() {
        val buffer = EegScopePlotBuffer(capacity = 5)

        buffer.appendAligned(7, floatArrayOf(10f, 11f))

        val snapshot = buffer.snapshot()
        assertEquals(4, snapshot.writePosition)
        assertEquals(10f, snapshot.values[2], 0f)
        assertEquals(11f, snapshot.values[3], 0f)
    }

    @Test
    fun scopePlotBuffer_exposesWrappedSamplesInTimelineOrder() {
        val buffer = EegScopePlotBuffer(capacity = 5)
        buffer.append(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f))

        val snapshot = buffer.snapshot()

        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f, 7f),
            FloatArray(snapshot.validCount, snapshot::chronologicalValueAt),
            0f
        )
        assertEquals(5f, snapshot.mean, 0f)
        assertEquals(4f, snapshot.peakToPeak, 0f)
    }

    @Test
    fun scopePlotBuffer_keepsAlignedPartialWindowInTimelineOrder() {
        val buffer = EegScopePlotBuffer(capacity = 5)
        buffer.appendAligned(7, floatArrayOf(10f, 11f))

        val snapshot = buffer.snapshot()

        assertArrayEquals(
            floatArrayOf(10f, 11f),
            FloatArray(snapshot.validCount, snapshot::chronologicalValueAt),
            0f
        )
        assertEquals(10.5f, snapshot.mean, 0f)
    }

    @Test
    fun realtimeDisplayFilter_preservesStateAcrossPackets() {
        val input = FloatArray(500) { index ->
            (
                    10_000.0 +
                            80.0 * sin(2.0 * PI * 10.0 * index / 500.0)
                    ).toFloat()
        }
        val oneChunk = RealtimeEegDisplayFilter().process(input)
        val packetFilter = RealtimeEegDisplayFilter()
        val packetOutput = ArrayList<Float>()
        for (start in input.indices step 50) {
            packetFilter.process(
                input.copyOfRange(start, minOf(start + 50, input.size))
            ).forEach(packetOutput::add)
        }

        assertArrayEquals(oneChunk, packetOutput.toFloatArray(), 1e-5f)
        assertTrue(oneChunk.all(Float::isFinite))
    }

    @Test
    fun realtimeDisplayFilter_suppressesConstantBaselineAtStartup() {
        val output =
            RealtimeEegDisplayFilter().process(FloatArray(500) { 10_000f })

        assertTrue(output.all(Float::isFinite))
        assertTrue(output.maxOf { kotlin.math.abs(it) } < 0.1f)
    }

    private fun buildPacket(
        earFlag: Int,
        rawSample: Int,
        lengthAsSampleCount: Boolean = false,
        lengthIncludesStatus: Boolean = false
    ): ByteArray {
        val raw = rawSample and 0xFFFFFF
        val packet = byteArrayOf(
            0xAA.toByte(), 0x55, 0x00, earFlag.toByte(),
            when {
                lengthIncludesStatus -> 0x05
                lengthAsSampleCount -> 0x01
                else -> 0x03
            },
            (raw and 0xFF).toByte(),
            ((raw shr 8) and 0xFF).toByte(),
            ((raw shr 16) and 0xFF).toByte(),
            0x00, 0x01, 0x55, 0xAA.toByte(), 0x00
        )
        packet[packet.lastIndex] = crc8Maxim(packet, packet.lastIndex).toByte()
        return packet
    }

    private fun eegPacket(
        side: String,
        counter: Int,
        vararg values: Float
    ): EegDataParser.EegPacket = EegDataParser.EegPacket(
        earSide = side,
        leadOff = 0,
        packetCount = counter,
        samples = values
    )

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
}

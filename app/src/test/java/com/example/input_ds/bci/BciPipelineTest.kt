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
    fun selectablePreprocessing_emptySelectionKeepsRawModelWindow() {
        val left = FloatArray(600) { it.toFloat() }
        val right = FloatArray(600) { (-it).toFloat() }

        val result = EegPreprocessor.preprocess(
            left,
            right,
            targetPoints = 400,
            steps = emptySet()
        )

        assertNotNull(result)
        assertArrayEquals(left.copyOfRange(200, 600), result!![0], 0f)
        assertArrayEquals(right.copyOfRange(200, 600), result[1], 0f)
    }

    @Test
    fun selectablePreprocessing_zScoreIsSharedByPlotAndInference() {
        val input = FloatArray(400) { index -> 20f + index * 0.5f }
        val steps = setOf(EegPreprocessStep.Z_SCORE)

        val plot = EegPreprocessor.preprocessForDisplay(input, steps)!!
        val model = EegPreprocessor.preprocess(
            input,
            input,
            targetPoints = 400,
            steps = steps
        )!![0]

        assertArrayEquals(plot, model, 1e-6f)
        assertEquals(0.0, model.map(Float::toDouble).average(), 1e-6)
        val rms = sqrt(model.map { it.toDouble() * it }.average())
        assertEquals(1.0, rms, 1e-6)
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
    fun displayFilter_matchesDesktopScipyReference() {
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
        val indices = intArrayOf(0, 1, 10, 100, 500, 1250, 2000, 2498, 2499)
        val scipy = floatArrayOf(
            14.513599f, 21.416706f, 39.362286f,
            73.127716f, 14.619328f, 18.132975f,
            5.386518f, -47.547737f, -46.320858f
        )

        indices.indices.forEach { position ->
            assertEquals(
                "index=${indices[position]}",
                scipy[position],
                actual[indices[position]],
                3e-2f
            )
        }
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
    fun stereoPacketAligner_waitsForTheSameProtocolCounter() {
        val aligner = EegStereoPacketAligner()
        val left = eegPacket("left", 10, 1f, 2f, 3f)

        assertNull(aligner.append(left))
        assertNull(aligner.append(eegPacket("right", 99, 11f, 12f, 13f)))
        val stereo = aligner.append(eegPacket("right", 10, 21f, 22f, 23f))

        assertNotNull(stereo)
        assertArrayEquals(left.samples, stereo!!.left, 0f)
        assertArrayEquals(floatArrayOf(21f, 22f, 23f), stereo.right, 0f)
        assertEquals(10, stereo.packetCount)
        assertTrue(!stereo.resynchronized)
        assertEquals(1, aligner.stats().exactMatches)
    }

    @Test
    fun stereoPacketAligner_burstyCallbacksDoNotChangeStereoPhase() {
        val aligner = EegStereoPacketAligner()

        for (counter in 40..45) {
            assertNull(aligner.append(eegPacket("left", counter, counter.toFloat())))
        }
        for (counter in 40..45) {
            val stereo = aligner.append(eegPacket("right", counter, (-counter).toFloat()))
            assertNotNull(stereo)
            assertEquals(counter, stereo!!.packetCount)
            assertTrue(!stereo.resynchronized)
        }

        assertEquals(6, aligner.stats().exactMatches)
        assertEquals(0, aligner.stats().resyncMatches)
    }

    @Test
    fun stereoPacketAligner_recoversAtNextSharedCounterAfterLoss() {
        val aligner = EegStereoPacketAligner()

        assertNull(aligner.append(eegPacket("left", 10, 10f)))
        assertNotNull(aligner.append(eegPacket("right", 10, 20f)))
        assertNull(aligner.append(eegPacket("left", 11, 11f)))
        assertNull(aligner.append(eegPacket("left", 12, 12f)))
        val recovered = aligner.append(eegPacket("right", 12, 22f))

        assertNotNull(recovered)
        assertTrue(recovered!!.resynchronized)
        assertEquals(1, recovered.missingPacketPairs)
        assertEquals(12, recovered.packetCount)
        assertArrayEquals(floatArrayOf(12f), recovered.left, 0f)
        assertArrayEquals(floatArrayOf(22f), recovered.right, 0f)
    }

    @Test
    fun stereoPacketAligner_dropsDuplicatesWithoutRepublishing() {
        val aligner = EegStereoPacketAligner()

        assertNull(aligner.append(eegPacket("left", 10, 1f)))
        assertNull(aligner.append(eegPacket("left", 10, 2f)))
        val first = aligner.append(eegPacket("right", 10, 3f))
        assertNotNull(first)
        assertArrayEquals(floatArrayOf(2f), first!!.left, 0f)

        assertNull(aligner.append(eegPacket("right", 10, 4f)))
        assertNull(aligner.append(eegPacket("left", 10, 5f)))
        assertEquals(1, aligner.stats().totalMatches)
        assertTrue(aligner.stats().leftDroppedPackets > 0)
        assertTrue(aligner.stats().rightDroppedPackets > 0)
    }

    @Test
    fun stereoPacketAligner_counterWrapRemainsContinuous() {
        val aligner = EegStereoPacketAligner()

        for (counter in listOf(254, 255, 0, 1)) {
            assertNull(aligner.append(eegPacket("left", counter, counter.toFloat())))
            val stereo = aligner.append(eegPacket("right", counter, counter.toFloat()))
            assertNotNull(stereo)
            assertTrue(!stereo!!.resynchronized)
        }

        assertEquals(4, aligner.stats().exactMatches)
        assertEquals(0, aligner.stats().missingPacketPairs)
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
    fun scopePlotBuffer_alignsCursorToAbsoluteSampleClock() {
        val buffer = EegScopePlotBuffer(capacity = 5)

        buffer.appendAligned(7, floatArrayOf(10f, 11f))

        val snapshot = buffer.snapshot()
        assertEquals(4, snapshot.writePosition)
        assertEquals(10f, snapshot.values[2], 0f)
        assertEquals(11f, snapshot.values[3], 0f)
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

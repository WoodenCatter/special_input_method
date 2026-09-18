package com.example.input_ds.personalization

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamingChunkCodecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun chunkContainsLittleEndianHeaderThenLeftAndRightArrays() {
        val file = temporaryFolder.newFile("chunk.eegchunk")
        StreamingChunkCodec.write(
            file = file,
            index = 7,
            startSample = 12_500L,
            left = floatArrayOf(1.25f, 2.5f),
            right = floatArrayOf(-1.25f, -2.5f),
            sampleCount = 2
        )

        val bytes = file.readBytes()
        assertArrayEquals(StreamingUploadStore.CHUNK_MAGIC, bytes.copyOfRange(0, 4))
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).apply { position(4) }
        assertEquals(StreamingUploadStore.CHUNK_FORMAT_VERSION, buffer.int)
        assertEquals(7, buffer.int)
        assertEquals(12_500L, buffer.long)
        assertEquals(2, buffer.int)
        assertEquals(StreamingUploadStore.CHANNEL_COUNT, buffer.int)
        assertEquals(1.25f, buffer.float, 0f)
        assertEquals(2.5f, buffer.float, 0f)
        assertEquals(-1.25f, buffer.float, 0f)
        assertEquals(-2.5f, buffer.float, 0f)
        assertEquals(28 + 2 * 2 * Float.SIZE_BYTES, bytes.size)
    }
}

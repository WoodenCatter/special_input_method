package com.example.input_ds.personalization

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

class NpzWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun packFloatArrays_writesNumpyCompatibleLittleEndianMembers() {
        val leftValues = floatArrayOf(1.25f, -2.5f, 3.75f)
        val rightValues = floatArrayOf(-10f, 0f, 10f)
        val left = temporaryFolder.newFile("left.raw")
        val right = temporaryFolder.newFile("right.raw")
        writeLittleEndian(leftValues, left)
        writeLittleEndian(rightValues, right)
        val npz = temporaryFolder.newFile("session.npz").apply { delete() }

        NpzWriter.packFloatArrays(
            npz,
            mapOf("left" to (left to leftValues.size), "right" to (right to rightValues.size))
        )

        assertTrue(npz.length() > 0)
        ZipFile(npz).use { zip ->
            assertEquals(setOf("left.npy", "right.npy"), zip.entries().asSequence().map { it.name }.toSet())
            assertArrayEquals(leftValues, readNpy(zip.getInputStream(zip.getEntry("left.npy")).readBytes()), 0f)
            assertArrayEquals(rightValues, readNpy(zip.getInputStream(zip.getEntry("right.npy")).readBytes()), 0f)
        }
    }

    private fun writeLittleEndian(values: FloatArray, file: java.io.File) {
        DataOutputStream(file.outputStream()).use { output ->
            values.forEach { output.writeInt(Integer.reverseBytes(it.toRawBits())) }
        }
    }

    private fun readNpy(bytes: ByteArray): FloatArray {
        assertArrayEquals(
            byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte()),
            bytes.copyOfRange(0, 6)
        )
        assertEquals(1, bytes[6].toInt())
        val headerLength = ByteBuffer.wrap(bytes, 8, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        val header = bytes.copyOfRange(10, 10 + headerLength).toString(Charsets.US_ASCII)
        assertTrue(header.contains("'descr': '<f4'"))
        assertTrue(header.contains("'fortran_order': False"))
        val payload = ByteBuffer.wrap(bytes, 10 + headerLength, bytes.size - 10 - headerLength)
            .order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(payload.remaining() / Float.SIZE_BYTES) { payload.float }
    }
}

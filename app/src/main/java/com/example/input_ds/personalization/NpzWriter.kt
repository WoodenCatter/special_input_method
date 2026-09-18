package com.example.input_ds.personalization

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object NpzWriter {
    fun packFloatArrays(outputFile: File, arrays: Map<String, Pair<File, Int>>) {
        require(arrays.isNotEmpty())
        outputFile.parentFile?.mkdirs()
        val temporary = File(outputFile.parentFile, "${outputFile.name}.tmp")
        ZipOutputStream(BufferedOutputStream(temporary.outputStream())).use { zip ->
            arrays.forEach { (key, sourceAndCount) ->
                val (source, count) = sourceAndCount
                require(source.length() == count.toLong() * Float.SIZE_BYTES) {
                    "$key raw byte count does not match its sample count"
                }
                zip.putNextEntry(ZipEntry("$key.npy"))
                writeNpyHeader(zip, count)
                FileInputStream(source).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        if (outputFile.exists() && !outputFile.delete()) {
            temporary.delete()
            error("无法替换已有 NPZ 文件")
        }
        check(temporary.renameTo(outputFile)) { "无法完成 NPZ 原子写入" }
    }

    private fun writeNpyHeader(output: OutputStream, count: Int) {
        val magic = byteArrayOf(0x93.toByte(), 'N'.code.toByte(), 'U'.code.toByte(), 'M'.code.toByte(), 'P'.code.toByte(), 'Y'.code.toByte())
        output.write(magic)
        output.write(byteArrayOf(1, 0))
        val dictionary = "{'descr': '<f4', 'fortran_order': False, 'shape': ($count,), }"
        val baseLength = 10 + dictionary.toByteArray(Charsets.US_ASCII).size + 1
        val padding = (16 - baseLength % 16) % 16
        val header = (dictionary + " ".repeat(padding) + "\n").toByteArray(Charsets.US_ASCII)
        output.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(header.size.toShort()).array())
        output.write(header)
    }
}

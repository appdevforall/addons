package org.appdevforall.maps.slicer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VarintTest {

    @Test
    fun encodes_zero_as_single_byte() {
        val out = ByteArrayOutputStream()
        Varint.encode(0L, out)
        assertArrayEquals(byteArrayOf(0), out.toByteArray())
    }

    @Test
    fun encodes_127_as_single_byte() {
        val out = ByteArrayOutputStream()
        Varint.encode(127L, out)
        assertArrayEquals(byteArrayOf(0x7f), out.toByteArray())
    }

    @Test
    fun encodes_128_as_two_bytes() {
        val out = ByteArrayOutputStream()
        Varint.encode(128L, out)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), out.toByteArray())
    }

    @Test
    fun roundtrips_random_values() {
        val samples = listOf(0L, 1L, 127L, 128L, 16_383L, 16_384L, 1_000_000L, Long.MAX_VALUE / 2)
        for (v in samples) {
            val out = ByteArrayOutputStream()
            Varint.encode(v, out)
            val buf = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val decoded = Varint.decode(buf)
            assertEquals("roundtrip $v", v, decoded)
        }
    }
}

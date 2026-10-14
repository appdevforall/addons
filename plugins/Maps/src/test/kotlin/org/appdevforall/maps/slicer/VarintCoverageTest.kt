package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Branch coverage for [Varint] focusing on the guard arms the happy-path
 * roundtrip test does not reach: the negative-value encode `require`, the
 * ">64 bits" decode `require`, and decoding of a maximal-width varint.
 */
class VarintCoverageTest {

    private fun roundtrip(v: Long): Long {
        val out = ByteArrayOutputStream()
        Varint.encode(v, out)
        return Varint.decode(ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN))
    }

    @Test
    fun decodes_single_byte_boundary() {
        assertEquals(127L, roundtrip(127L))
    }

    @Test
    fun decodes_two_byte_boundary() {
        assertEquals(128L, roundtrip(128L))
    }

    @Test
    fun decodes_max_long() {
        // Long.MAX_VALUE encodes to a 9-byte varint; exercises the multi-byte loop fully.
        assertEquals(Long.MAX_VALUE, roundtrip(Long.MAX_VALUE))
    }

    @Test
    fun encode_rejects_negative() {
        assertThrows(IllegalArgumentException::class.java) {
            Varint.encode(-1L, ByteArrayOutputStream())
        }
    }

    @Test
    fun decode_rejects_overlong_varint() {
        // 10 continuation bytes (all MSB set) push shift past 64 -> require fails.
        val bytes = ByteArray(10) { 0x80.toByte() }
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertThrows(IllegalArgumentException::class.java) { Varint.decode(buf) }
    }
}

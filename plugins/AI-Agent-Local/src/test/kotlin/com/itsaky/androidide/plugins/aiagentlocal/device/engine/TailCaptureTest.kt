package com.itsaky.androidide.plugins.aiagentlocal.device.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TailCaptureTest {

    @Test
    fun givenLessThanTheCap_whenWritten_thenEverythingIsKept() {
        val t = TailCapture(10)
        t.write("abc".toByteArray(), 0, 3)
        t.write("de".toByteArray(), 0, 2)
        assertEquals("abcde", String(t.bytes()))
        assertFalse(t.truncated)
        assertEquals(5L, t.total)
    }

    @Test
    fun givenMoreThanTheCap_whenWrittenInPieces_thenTheLastCapBytesRemainInOrder() {
        val t = TailCapture(8)
        val all = (0 until 100).map { ('a' + it % 26).code.toByte() }.toByteArray()
        var o = 0
        val sizes = listOf(3, 5, 1, 7, 8, 9, 2, 20, 45)
        for (n in sizes) { t.write(all, o, minOf(n, all.size - o)); o += n; if (o >= all.size) break }
        assertTrue(t.truncated)
        val seen = minOf(o, all.size)
        assertArrayEquals(all.copyOfRange(seen - 8, seen), t.bytes())
    }

    @Test
    fun givenABlockLargerThanTheCap_whenWritten_thenOnlyItsEndIsKept() {
        val t = TailCapture(4)
        t.write("0123456789".toByteArray(), 0, 10)
        assertEquals("6789", String(t.bytes()))
        t.write("ab".toByteArray(), 0, 2)
        assertEquals("89ab", String(t.bytes()))
        assertEquals(12L, t.total)
    }
}

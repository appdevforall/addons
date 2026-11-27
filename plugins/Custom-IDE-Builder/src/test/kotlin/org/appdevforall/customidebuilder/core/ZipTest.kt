package org.appdevforall.customidebuilder.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Takes the bytes and drops them; records whether it was closed and can be made to fail. */
    private class Sink : OutputStream() {
        var closed = false
        var failWrites = false

        override fun write(b: Int) {
            if (failWrites) throw IOException("disk full")
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (failWrites) throw IOException("disk full")
        }

        override fun close() {
            closed = true
        }
    }

    private fun zeros(count: Long): (OutputStream) -> Unit = { sink ->
        val block = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = minOf(block.size.toLong(), left).toInt()
            sink.write(block, 0, n)
            left -= n
        }
    }

    /** "ab" has a 32-byte header, already aligned, so this ends the data at 0xFFFFFFFE, the last offset a ZIP32 field can hold. */
    private fun RawZipWriter.fillToTheLastOffset() = addStored("ab", 0xFFFFFFFEL - 32, 0, zeros(0xFFFFFFFEL - 32))

    @Test
    fun emptyStoredFilesAreAlignedLikeAnyOtherStoredFile() {
        val file = tmp.newFile("w.zip")
        RawZipWriter(file.outputStream()).use { w ->
            w.addStored("a", 1, 0) { it.write(1) }
            w.addStored("empty.bin", 0, 0) {}
            w.addStored("b", 1, 0) { it.write(1) }
            w.addStored("empty.so", 0, 0) {}
            w.addStored("dir/", 0, 0) {}
        }
        RawZipFile(file).use { z ->
            for (e in z.entries.filter { it.isStored && !it.isDirectory }) assertEquals(e.name, 0L, z.dataOffset(e) % ZipAlignment.requiredFor(e.name))
            assertEquals(2, z.entries.count { it.compressedSize == 0L && !it.isDirectory })
        }
        assertEquals(emptyList<String>(), ZipAlignment.misaligned(file))
    }

    @Test
    fun theAlignmentCheckReportsAMisalignedEmptyStoredFile() {
        val file = tmp.newFile("m.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, data) in listOf("a" to byteArrayOf(1), "empty" to ByteArray(0))) {
                zip.putNextEntry(
                    ZipEntry(name).apply {
                        method = ZipEntry.STORED
                        size = data.size.toLong()
                        compressedSize = data.size.toLong()
                        crc = java.util.zip.CRC32().apply { update(data) }.value
                    },
                )
                zip.write(data)
                zip.closeEntry()
            }
        }
        assertTrue("empty" in ZipAlignment.misaligned(file))
    }

    @Test
    fun aValueMustFitBelowTheReservedAllOnes() {
        requireZip32(0, "x")
        requireZip32(0xFFFFFFFEL, "x")
        for (bad in listOf(0xFFFFFFFFL, 0x1_0000_001DL, -1L)) {
            try {
                requireZip32(bad, "x")
                throw AssertionError("accepted $bad")
            } catch (e: ZipFormatException) {
                // expected
            }
        }
    }

    @Test
    fun headerBytesThatPassTheLastOffsetAreRefusedNotWrittenAsAWrapAround() {
        val writer = RawZipWriter(Sink())
        writer.fillToTheLastOffset()
        try {
            writer.addStored("b", 0, 0) {}
            throw AssertionError("wrote an entry whose offset no longer fits")
        } catch (e: ZipFormatException) {
            assertTrue(e.message, e.message!!.contains("ZIP64"))
        }
    }

    @Test
    fun aCentralDirectoryThatPassesTheLastOffsetIsRefusedAndTheStreamStillClosed() {
        val sink = Sink()
        val writer = RawZipWriter(sink)
        writer.fillToTheLastOffset()
        try {
            writer.close()
            throw AssertionError("wrote an end record that points to the wrong bytes")
        } catch (e: ZipFormatException) {
            assertTrue(e.message, e.message!!.contains("ZIP64"))
        }
        assertTrue(sink.closed)
    }

    @Test
    fun theStreamIsClosedAndTheWriteErrorKeptWhenTheCentralDirectoryCannotBeWritten() {
        val sink = Sink()
        val writer = RawZipWriter(sink)
        // Enough entries that the central directory alone is bigger than the writer's buffer.
        for (i in 0 until 1500) writer.addStored("entry-%05d".format(i), 0, 0) {}
        sink.failWrites = true
        try {
            writer.close()
            throw AssertionError("close succeeded")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertTrue("the output stream must be closed after a failed finish", sink.closed)
    }

    @Test
    fun aSmallArchiveStillRoundTrips() {
        val file = File(tmp.root, "ok.zip")
        RawZipWriter(file.outputStream()).use { it.addStored("x", 3, java.util.zip.CRC32().apply { update(byteArrayOf(1, 2, 3)) }.value) { s -> s.write(byteArrayOf(1, 2, 3)) } }
        java.util.zip.ZipFile(file).use { assertEquals(listOf<Byte>(1, 2, 3), it.getInputStream(it.getEntry("x")).readBytes().toList()) }
        assertFalse(file.length() == 0L)
    }
}

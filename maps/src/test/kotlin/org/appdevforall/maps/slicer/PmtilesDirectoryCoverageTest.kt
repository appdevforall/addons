package org.appdevforall.maps.slicer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Branch coverage for [PmtilesDirectory] (parse/serialize/compress) and
 * [DirectoryEntry.isLeafPointer]. Exercises the offset-delta branches
 * (rawOff == 0 at i==0, rawOff == 0 at i>0, and the explicit-offset arm),
 * the empty-directory short-circuit, the serialize sort-order guard, and
 * the compression `when` arms including the unsupported-codec `else`.
 */
class PmtilesDirectoryCoverageTest {

    private fun rawBuf(bytes: ByteArray): ByteBuffer =
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun parse_empty_directory_returns_empty_list() {
        val out = ByteArrayOutputStream()
        Varint.encode(0L, out) // n = 0
        assertTrue(PmtilesDirectory.parse(rawBuf(out.toByteArray())).isEmpty())
    }

    @Test
    fun serialize_then_parse_roundtrips_contiguous_entries() {
        // Two contiguous entries: entry[1].offset == entry[0].offset + entry[0].length,
        // which serialize encodes as the implicit "0" offset, and parse decodes back
        // through the `rawOff == 0L && i > 0` arm.
        val entries = listOf(
            DirectoryEntry(tileId = 0L, runLength = 1L, length = 100L, offset = 0L),
            DirectoryEntry(tileId = 5L, runLength = 1L, length = 200L, offset = 100L),
        )
        val bytes = PmtilesDirectory.serialize(entries)
        val parsed = PmtilesDirectory.parse(rawBuf(bytes))
        assertEquals(entries, parsed)
    }

    @Test
    fun serialize_then_parse_roundtrips_noncontiguous_entries() {
        // entry[1].offset != entry[0].offset + length -> explicit (offset+1) encoding,
        // exercising the `else -> rawOff - 1L` parse arm and the serialize else arm.
        val entries = listOf(
            DirectoryEntry(tileId = 0L, runLength = 1L, length = 100L, offset = 0L),
            DirectoryEntry(tileId = 7L, runLength = 1L, length = 50L, offset = 500L),
        )
        val bytes = PmtilesDirectory.serialize(entries)
        val parsed = PmtilesDirectory.parse(rawBuf(bytes))
        assertEquals(entries, parsed)
    }

    @Test
    fun parse_first_entry_raw_zero_offset_decodes_to_zero() {
        // Hand-built single-entry directory whose raw offset varint is literally 0
        // at i == 0 -> exercises the parse `rawOff == 0L -> 0L` (i==0) arm directly,
        // which serialize never emits (it always writes offset+1 for the first entry).
        val out = ByteArrayOutputStream()
        Varint.encode(1L, out) // n = 1
        Varint.encode(3L, out) // ids[0] delta = 3 -> tileId 3
        Varint.encode(1L, out) // runs[0] = 1
        Varint.encode(10L, out) // lens[0] = 10
        Varint.encode(0L, out) // raw offset = 0 at i==0
        val parsed = PmtilesDirectory.parse(rawBuf(out.toByteArray()))
        assertEquals(1, parsed.size)
        assertEquals(3L, parsed.single().tileId)
        assertEquals(0L, parsed.single().offset)
    }

    @Test
    fun serialize_rejects_unsorted_entries() {
        val entries = listOf(
            DirectoryEntry(tileId = 10L, runLength = 1L, length = 10L, offset = 0L),
            DirectoryEntry(tileId = 5L, runLength = 1L, length = 10L, offset = 10L),
        )
        assertThrows(IllegalArgumentException::class.java) {
            PmtilesDirectory.serialize(entries)
        }
    }

    @Test
    fun maybeCompress_none_is_identity() {
        val raw = byteArrayOf(1, 2, 3, 4)
        assertEquals(raw.toList(), PmtilesDirectory.maybeCompress(raw, PmtilesV3.COMPRESSION_NONE).toList())
    }

    @Test
    fun maybeCompress_then_decompress_gzip_roundtrips() {
        val raw = ByteArray(256) { (it % 7).toByte() }
        val gz = PmtilesDirectory.maybeCompress(raw, PmtilesV3.COMPRESSION_GZIP)
        val back = PmtilesDirectory.maybeDecompress(gz, PmtilesV3.COMPRESSION_GZIP)
        assertEquals(raw.toList(), back.toList())
    }

    @Test
    fun maybeDecompress_none_is_identity() {
        val raw = byteArrayOf(9, 8, 7)
        assertEquals(raw.toList(), PmtilesDirectory.maybeDecompress(raw, PmtilesV3.COMPRESSION_NONE).toList())
    }

    @Test
    fun maybeCompress_rejects_unsupported_codec() {
        assertThrows(IllegalStateException::class.java) {
            PmtilesDirectory.maybeCompress(byteArrayOf(1), PmtilesV3.COMPRESSION_BROTLI)
        }
    }

    @Test
    fun maybeDecompress_rejects_unsupported_codec() {
        assertThrows(IllegalStateException::class.java) {
            PmtilesDirectory.maybeDecompress(byteArrayOf(1), PmtilesV3.COMPRESSION_ZSTD)
        }
    }

    @Test
    fun parseCompressed_handles_gzip_blob() {
        val entries = listOf(
            DirectoryEntry(tileId = 0L, runLength = 1L, length = 100L, offset = 0L),
            DirectoryEntry(tileId = 5L, runLength = 1L, length = 200L, offset = 100L),
        )
        val raw = PmtilesDirectory.serialize(entries)
        val gz = PmtilesDirectory.maybeCompress(raw, PmtilesV3.COMPRESSION_GZIP)
        assertEquals(entries, PmtilesDirectory.parseCompressed(gz, PmtilesV3.COMPRESSION_GZIP))
    }

    @Test
    fun parseCompressed_handles_uncompressed_blob() {
        val entries = listOf(
            DirectoryEntry(tileId = 1L, runLength = 1L, length = 10L, offset = 0L),
        )
        val raw = PmtilesDirectory.serialize(entries)
        assertEquals(entries, PmtilesDirectory.parseCompressed(raw, PmtilesV3.COMPRESSION_NONE))
    }

    @Test
    fun directoryEntry_isLeafPointer_true_when_runLength_zero() {
        assertTrue(DirectoryEntry(tileId = 0L, runLength = 0L, length = 0L, offset = 0L).isLeafPointer())
    }

    @Test
    fun directoryEntry_isLeafPointer_false_when_runLength_nonzero() {
        assertFalse(DirectoryEntry(tileId = 0L, runLength = 1L, length = 0L, offset = 0L).isLeafPointer())
    }
}

package org.appdevforall.maps.slicer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Parses & serializes PMTiles v3 directory blobs.
 *
 * Wire format (after `internal_compression` decompression):
 * ```
 *   varint  N (number of entries)
 *   N × varint  tile_id (delta-encoded from previous; first = absolute)
 *   N × varint  run_length
 *   N × varint  length
 *   N × varint  offset    (0 = appended directly after previous entry's
 *                          (offset+length); nonzero = absolute - 1)
 * ```
 *
 * Each `Entry` represents a contiguous run of `runLength` tiles sharing the
 * same bytes (`offset`, `length`), starting at `tileId`. For the slicer we
 * mostly work with `runLength=1` entries.
 */
internal data class DirectoryEntry(
    val tileId: Long,
    val runLength: Long,
    val length: Long,
    /** Offset relative to `header.tileDataOffset`. */
    val offset: Long,
) {
    /** True iff this entry is a leaf-directory pointer (per v3 spec: runLength == 0). */
    fun isLeafPointer(): Boolean = runLength == 0L
}

internal object PmtilesDirectory {

    /**
     * Parse a directory blob (already decompressed) from [buf] starting at
     * current position, consuming exactly the entire directory.
     */
    fun parse(buf: ByteBuffer): List<DirectoryEntry> {
        val n = Varint.decode(buf).toInt()
        if (n == 0) return emptyList()
        val ids = LongArray(n)
        val runs = LongArray(n)
        val lens = LongArray(n)
        val offs = LongArray(n)

        var lastId = 0L
        for (i in 0 until n) {
            val delta = Varint.decode(buf)
            lastId += delta
            ids[i] = lastId
        }
        for (i in 0 until n) runs[i] = Varint.decode(buf)
        for (i in 0 until n) lens[i] = Varint.decode(buf)
        for (i in 0 until n) {
            val rawOff = Varint.decode(buf)
            offs[i] = when {
                rawOff == 0L && i > 0 -> offs[i - 1] + lens[i - 1]
                rawOff == 0L -> 0L
                else -> rawOff - 1L
            }
        }

        return List(n) { i -> DirectoryEntry(ids[i], runs[i], lens[i], offs[i]) }
    }

    /**
     * Serialize a list of entries to the raw v3 directory wire bytes
     * (uncompressed). Caller is responsible for gzip-wrapping if needed.
     *
     * Entries MUST be sorted ascending by tileId; the spec requires it.
     */
    fun serialize(entries: List<DirectoryEntry>): ByteArray {
        val out = ByteArrayOutputStream()
        Varint.encode(entries.size.toLong(), out)
        var lastId = 0L
        for (e in entries) {
            val delta = e.tileId - lastId
            require(delta >= 0) { "directory entries must be sorted by tileId" }
            Varint.encode(delta, out)
            lastId = e.tileId
        }
        for (e in entries) Varint.encode(e.runLength, out)
        for (e in entries) Varint.encode(e.length, out)
        for (i in entries.indices) {
            val e = entries[i]
            val expected = if (i > 0) entries[i - 1].offset + entries[i - 1].length else -1L
            val raw = if (i > 0 && e.offset == expected) 0L else e.offset + 1L
            Varint.encode(raw, out)
        }
        return out.toByteArray()
    }

    /** Apply gzip if compression == GZIP, else return raw bytes. */
    fun maybeCompress(raw: ByteArray, compression: Byte): ByteArray = when (compression) {
        PmtilesV3.COMPRESSION_NONE -> raw
        PmtilesV3.COMPRESSION_GZIP -> {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(raw) }
            out.toByteArray()
        }
        else -> error("compression ${PmtilesV3.compressionName(compression)} not supported")
    }

    /** Decompress per the given compression code. */
    fun maybeDecompress(compressed: ByteArray, compression: Byte): ByteArray = when (compression) {
        PmtilesV3.COMPRESSION_NONE -> compressed
        PmtilesV3.COMPRESSION_GZIP -> {
            GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
        }
        else -> error("compression ${PmtilesV3.compressionName(compression)} not supported")
    }

    /** Helper: parse a directory blob given raw possibly-compressed bytes. */
    fun parseCompressed(bytes: ByteArray, compression: Byte): List<DirectoryEntry> {
        val raw = maybeDecompress(bytes, compression)
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        return parse(buf)
    }
}

package org.appdevforall.maps.slicer

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PMTiles v3 header (127 bytes, little-endian).
 *
 * Layout per spec:
 * ```
 *   0   7  magic "PMTiles"
 *   7   1  version (=3)
 *   8   8  root_dir_offset
 *  16   8  root_dir_bytes
 *  24   8  json_metadata_offset
 *  32   8  json_metadata_bytes
 *  40   8  leaf_dirs_offset
 *  48   8  leaf_dirs_bytes
 *  56   8  tile_data_offset
 *  64   8  tile_data_bytes
 *  72   8  addressed_tiles_count
 *  80   8  tile_entries_count
 *  88   8  tile_contents_count
 *  96   1  clustered (1 = clustered, 0 = not)
 *  97   1  internal_compression
 *  98   1  tile_compression
 *  99   1  tile_type
 * 100   1  min_zoom
 * 101   1  max_zoom
 * 102   4  min_lon_e7  (signed int32, longitude × 1e7)
 * 106   4  min_lat_e7
 * 110   4  max_lon_e7
 * 114   4  max_lat_e7
 * 118   1  center_zoom
 * 119   4  center_lon_e7
 * 123   4  center_lat_e7
 * ```
 */
internal data class PmtilesHeader(
    val version: Byte,
    val rootDirOffset: Long,
    val rootDirBytes: Long,
    val jsonMetadataOffset: Long,
    val jsonMetadataBytes: Long,
    val leafDirsOffset: Long,
    val leafDirsBytes: Long,
    val tileDataOffset: Long,
    val tileDataBytes: Long,
    val addressedTilesCount: Long,
    val tileEntriesCount: Long,
    val tileContentsCount: Long,
    val clustered: Byte,
    val internalCompression: Byte,
    val tileCompression: Byte,
    val tileType: Byte,
    val minZoom: Byte,
    val maxZoom: Byte,
    val minLonE7: Int,
    val minLatE7: Int,
    val maxLonE7: Int,
    val maxLatE7: Int,
    val centerZoom: Byte,
    val centerLonE7: Int,
    val centerLatE7: Int,
) {

    fun isClustered(): Boolean = clustered.toInt() == 1

    fun minLon(): Double = minLonE7 / 1e7
    fun minLat(): Double = minLatE7 / 1e7
    fun maxLon(): Double = maxLonE7 / 1e7
    fun maxLat(): Double = maxLatE7 / 1e7

    /** Serialize back to 127 bytes for writing a sliced PMTiles. */
    fun toByteArray(): ByteArray {
        val buf = ByteBuffer.allocate(PmtilesV3.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(PmtilesV3.MAGIC)
        buf.put(version)
        buf.putLong(rootDirOffset)
        buf.putLong(rootDirBytes)
        buf.putLong(jsonMetadataOffset)
        buf.putLong(jsonMetadataBytes)
        buf.putLong(leafDirsOffset)
        buf.putLong(leafDirsBytes)
        buf.putLong(tileDataOffset)
        buf.putLong(tileDataBytes)
        buf.putLong(addressedTilesCount)
        buf.putLong(tileEntriesCount)
        buf.putLong(tileContentsCount)
        buf.put(clustered)
        buf.put(internalCompression)
        buf.put(tileCompression)
        buf.put(tileType)
        buf.put(minZoom)
        buf.put(maxZoom)
        buf.putInt(minLonE7)
        buf.putInt(minLatE7)
        buf.putInt(maxLonE7)
        buf.putInt(maxLatE7)
        buf.put(centerZoom)
        buf.putInt(centerLonE7)
        buf.putInt(centerLatE7)
        require(buf.position() == PmtilesV3.HEADER_BYTES) {
            "header serializer wrote ${buf.position()} bytes, expected ${PmtilesV3.HEADER_BYTES}"
        }
        return buf.array()
    }

    companion object {
        /** Parse a 127-byte header from a buffer at its current position. */
        fun parse(buf: ByteBuffer): PmtilesHeader {
            require(buf.remaining() >= PmtilesV3.HEADER_BYTES) {
                "buffer too small for v3 header: ${buf.remaining()} bytes"
            }
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(7).also { buf.get(it) }
            require(magic.contentEquals(PmtilesV3.MAGIC)) {
                "magic bytes do not match PMTiles: ${magic.joinToString { "%02x".format(it) }}"
            }
            val version = buf.get()
            require(version == PmtilesV3.VERSION) {
                "PMTiles version $version unsupported (want ${PmtilesV3.VERSION})"
            }
            return PmtilesHeader(
                version = version,
                rootDirOffset = buf.long,
                rootDirBytes = buf.long,
                jsonMetadataOffset = buf.long,
                jsonMetadataBytes = buf.long,
                leafDirsOffset = buf.long,
                leafDirsBytes = buf.long,
                tileDataOffset = buf.long,
                tileDataBytes = buf.long,
                addressedTilesCount = buf.long,
                tileEntriesCount = buf.long,
                tileContentsCount = buf.long,
                clustered = buf.get(),
                internalCompression = buf.get(),
                tileCompression = buf.get(),
                tileType = buf.get(),
                minZoom = buf.get(),
                maxZoom = buf.get(),
                minLonE7 = buf.int,
                minLatE7 = buf.int,
                maxLonE7 = buf.int,
                maxLatE7 = buf.int,
                centerZoom = buf.get(),
                centerLonE7 = buf.int,
                centerLatE7 = buf.int,
            )
        }
    }
}

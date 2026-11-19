package org.appdevforall.maps.slicer

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.appdevforall.maps.domain.Bbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Our slicer against the reference implementation: the same bbox and zooms of the bundled archive
 * must produce the tile ids and tile bytes that go-pmtiles `pmtiles extract` produced. The golden
 * file's header records the CLI version, command and capture date.
 */
class PmtilesRegionSlicerGoldenTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val source = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles")
    private val archive = source.readBytes()

    // Must match the --bbox/--minzoom/--maxzoom in the golden file's header.
    private val bbox = Bbox(south = -12.4, west = 5.3, north = 17.6, east = 48.7)
    private val zoomMin = 1
    private val zoomMax = 4

    /** Serves [archive] by byte range, as the IIAB mirror and LAN boxes do. */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (start, end) = request.getHeader("Range")!!.removePrefix("bytes=").split('-').map { it.toInt() }
                val slice = archive.copyOfRange(start, minOf(end, archive.size - 1) + 1)
                return MockResponse().setResponseCode(206).setBody(Buffer().write(slice))
            }
        }
    }

    @Before
    fun setUp() {
        HttpRangeByteCache.clear()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HttpRangeByteCache.clear()
    }

    private fun golden(): Map<Long, String> =
        javaClass.getResource("/slicer/go-pmtiles-extract-ne-z1-z4.txt")!!.readText()
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split(' ').let { it[0].toLong() to it[1] } }

    /** Tile id -> sha256 of the stored bytes, for every tile addressed by [bytes]. */
    private fun tileHashes(bytes: ByteArray): Map<Long, String> {
        val header = PmtilesHeader.parse(
            ByteBuffer.wrap(bytes, 0, PmtilesV3.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        )
        fun slice(offset: Long, length: Long) = bytes.copyOfRange(offset.toInt(), (offset + length).toInt())
        val out = sortedMapOf<Long, String>()
        fun walk(entries: List<DirectoryEntry>) {
            for (e in entries) {
                if (e.isLeafPointer()) {
                    walk(PmtilesDirectory.parseCompressed(
                        slice(header.leafDirsOffset + e.offset, e.length), header.internalCompression,
                    ))
                    continue
                }
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(slice(header.tileDataOffset + e.offset, e.length))
                    .joinToString("") { "%02x".format(it) }
                for (k in 0 until e.runLength) out[e.tileId + k] = digest
            }
        }
        walk(PmtilesDirectory.parseCompressed(
            slice(header.rootDirOffset, header.rootDirBytes), header.internalCompression,
        ))
        return out
    }

    /** Runs the slicer on [url] the way RegionDownloader does and returns the written archive. */
    private fun sliceFrom(url: String): ByteArray = runBlocking {
        val sourceHeader = PmtilesHeader.parse(
            ByteBuffer.wrap(archive, 0, PmtilesV3.HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        )
        val tiles = PmtilesRegionSlicer.tilesInRegion(url, bbox, zoomMin, zoomMax).getOrThrow()
        val target = File(tmp.newFolder(), "slice.pmtiles")
        PmtilesRegionSlicer.downloadAndSlice(
            tiles = tiles,
            globalPmtilesUrl = url,
            sourceHeader = sourceHeader,
            bbox = bbox,
            zoomMin = zoomMin,
            zoomMax = zoomMax,
            targetFile = target,
            onProgress = { _, _ -> },
        ).getOrThrow()
        target.readBytes()
    }

    private fun assertMatchesGolden(sliced: ByteArray) {
        val expected = golden()
        val actual = tileHashes(sliced)
        assertEquals("tile ids", expected.keys.toList(), actual.keys.toList())
        assertEquals("tile bytes", expected, actual)
    }

    @Test
    fun `slicing over http matches go-pmtiles extract tile for tile`() {
        assertMatchesGolden(sliceFrom(server.url("/world.pmtiles").toString()))
    }

    @Test
    fun `slicing a local file matches go-pmtiles extract tile for tile`() {
        assertMatchesGolden(sliceFrom("file://" + source.absolutePath))
    }
}

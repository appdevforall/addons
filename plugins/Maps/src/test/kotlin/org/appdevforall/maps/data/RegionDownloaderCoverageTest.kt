package org.appdevforall.maps.data

import kotlinx.coroutines.runBlocking
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Coverage tests for [RegionDownloader]'s JVM-testable surface.
 *
 * The download orchestration was refactored into [RegionDownloader.downloadInto],
 * which takes the three IO seams (cache root, basemap copy, tile slice) plus the
 * clock injected — so the partial→final dir flow, `meta.json` assembly, atomic
 * rename, progress sequencing, regionId validation, and cleanup-on-failure are all
 * exercised here with a temp dir + fakes. The pure URL builders
 * ([RegionDownloader.buildTilesUrl] / `base`) are covered too.
 *
 * The thin remaining IO — `copyBundledBasemap` (reads a bundled asset via
 * `MapsPlugin.pluginContext`) and `sliceDownload` (range-fetches over the network) —
 * is Android/host-bound (no `Environment` / `AssetManager` on a plain JVM) and is
 * covered by the android-qa device walk instead, documented at each call site.
 */
class RegionDownloaderCoverageTest {

    private lateinit var tmpDir: File

    @Before
    fun setUp() {
        tmpDir = File.createTempFile("region-downloader-cov", "").let {
            it.delete()
            it.mkdirs()
            it
        }
    }

    @After
    fun tearDown() {
        tmpDir.deleteRecursively()
    }

    // ----- downloadInto(): the injected-seam orchestration. The refactor that
    //       extracted this from download() made it testable with a temp dir +
    //       fakes — no Environment, no plugin asset bundle, no network. -----

    private val tinyBbox = Bbox(south = 5.0, west = -1.0, north = 6.0, east = 0.0)

    /** copyBasemap fake: writes [n] bytes to dest + reports progress once. */
    private fun fakeBasemap(n: Int): (File, (Long, Long) -> Unit) -> Unit = { dest, op ->
        dest.writeBytes(ByteArray(n))
        op(n.toLong(), n.toLong())
    }

    /** sliceTiles fake: writes [n] bytes to target + reports progress once. */
    private fun fakeSlice(n: Int): suspend (File, (Long, Long) -> Unit) -> Unit = { target, op ->
        target.writeBytes(ByteArray(n))
        op(n.toLong(), n.toLong())
    }

    @Test
    fun `downloadInto writes the region layout plus meta-json and renames atomically`() = runBlocking {
        val progress = mutableListOf<Pair<RegionDownloader.Phase, Long>>()
        val result = RegionDownloader.downloadInto(
            cacheRoot = tmpDir,
            regionId = "freetown",
            displayName = "Freetown",
            bbox = tinyBbox,
            zoomMin = 6,
            zoomMax = 14,
            sourceKind = SourceKind.IIAB_LAN,
            sourceHost = "box.local",
            sourceVersion = "2026-04-01",
            nowMillis = 1_700_000_000_000L,
            onProgress = { phase, bytes, _ -> progress += phase to bytes },
            copyBasemap = fakeBasemap(11),
            sliceTiles = fakeSlice(22),
        )

        // Renamed to the final dir; the .partial is gone.
        assertEquals(File(tmpDir, "freetown"), result)
        assertTrue("final dir exists", result.isDirectory)
        assertFalse(".partial removed", File(tmpDir, "freetown.partial").exists())

        // All three artifacts present with the written sizes.
        assertEquals(11L, File(result, RegionCache.FILE_BASEMAP_PMTILES).length())
        assertEquals(22L, File(result, RegionCache.FILE_TILES_PMTILES).length())
        val meta = File(result, RegionCache.FILE_META_JSON)
        assertTrue("meta.json written", meta.isFile)

        // meta.json is exactly what was passed in (deterministic via nowMillis).
        val json = org.json.JSONObject(meta.readText())
        assertEquals(RegionDownloader.META_SCHEMA_VERSION, json.getInt("version"))
        assertEquals("freetown", json.getString("regionId"))
        assertEquals("Freetown", json.getString("displayName"))
        assertEquals(6, json.getInt("zoomMin"))
        assertEquals(14, json.getInt("zoomMax"))
        assertEquals(33L, json.getLong("sizeBytes")) // 11 + 22
        assertEquals(1_700_000_000_000L, json.getLong("downloadedAt"))
        assertEquals(1_700_000_000_000L, json.getLong("lastUsedAt"))
        assertEquals(2, json.getJSONArray("layers").length()) // [vector, basemap]
        val source = json.getJSONObject("source")
        assertEquals(SourceKind.IIAB_LAN.wireValue, source.getString("kind"))
        assertEquals("box.local", source.getString("host"))
        assertEquals("2026-04-01", source.getString("version"))

        // Progress fired BASEMAP first, then TILES.
        assertEquals(RegionDownloader.Phase.BASEMAP, progress.first().first)
        assertEquals(RegionDownloader.Phase.TILES, progress.last().first)
    }

    @Test
    fun `downloadInto omits source host and version when null`() = runBlocking {
        val result = RegionDownloader.downloadInto(
            cacheRoot = tmpDir, regionId = "a", displayName = "A", bbox = tinyBbox,
            zoomMin = 6, zoomMax = 14, sourceKind = SourceKind.UNKNOWN,
            sourceHost = null, sourceVersion = null, nowMillis = 1L,
            onProgress = { _, _, _ -> },
            copyBasemap = fakeBasemap(1), sliceTiles = fakeSlice(1),
        )
        val source = org.json.JSONObject(File(result, RegionCache.FILE_META_JSON).readText())
            .getJSONObject("source")
        assertFalse("host omitted when null", source.has("host"))
        assertFalse("version omitted when null", source.has("version"))
    }

    @Test
    fun `downloadInto rejects a malformed regionId before any IO`() = runBlocking {
        try {
            RegionDownloader.downloadInto(
                cacheRoot = tmpDir, regionId = "Bad Region ID", displayName = "X", bbox = tinyBbox,
                zoomMin = 6, zoomMax = 14, sourceKind = SourceKind.UNKNOWN,
                sourceHost = null, sourceVersion = null, nowMillis = 1L,
                onProgress = { _, _, _ -> },
                copyBasemap = { _, _ -> fail("basemap must not run for an invalid id") },
                sliceTiles = { _, _ -> fail("slice must not run for an invalid id") },
            )
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue("message names the offending id", e.message?.contains("Bad Region ID") == true)
        }
        assertFalse(File(tmpDir, "Bad Region ID.partial").exists())
    }

    @Test
    fun `downloadInto cleans up the partial dir when slicing fails`() = runBlocking {
        try {
            RegionDownloader.downloadInto(
                cacheRoot = tmpDir, regionId = "kakuma", displayName = "Kakuma", bbox = tinyBbox,
                zoomMin = 6, zoomMax = 14, sourceKind = SourceKind.UNKNOWN,
                sourceHost = null, sourceVersion = null, nowMillis = 1L,
                onProgress = { _, _, _ -> },
                copyBasemap = fakeBasemap(8),
                sliceTiles = { _, _ -> throw IllegalStateException("slice exploded") },
            )
            fail("expected the slice failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("slice exploded", e.message)
        }
        // The whole partial dir is removed; no final dir was created.
        assertFalse("partial cleaned up", File(tmpDir, "kakuma.partial").exists())
        assertFalse("no final dir on failure", File(tmpDir, "kakuma").exists())
    }

    @Test
    fun `downloadInto replaces a pre-existing final dir`() = runBlocking {
        File(tmpDir, "goma").apply { mkdirs() }.let { File(it, "stale.txt").writeText("old") }
        val result = RegionDownloader.downloadInto(
            cacheRoot = tmpDir, regionId = "goma", displayName = "Goma", bbox = tinyBbox,
            zoomMin = 6, zoomMax = 14, sourceKind = SourceKind.UNKNOWN,
            sourceHost = null, sourceVersion = null, nowMillis = 1L,
            onProgress = { _, _, _ -> },
            copyBasemap = fakeBasemap(3), sliceTiles = fakeSlice(3),
        )
        assertFalse("stale file gone after replace", File(result, "stale.txt").exists())
        assertTrue(File(result, RegionCache.FILE_META_JSON).isFile)
    }

    // ----- buildTilesUrl / base: the pure URL-builder branches -----

    private val tilesFile = "openstreetmap-openmaptiles.2026-04-01.z00-z14.pmtiles"

    @Test
    fun `buildTilesUrl uses the internet base for non-LAN sources`() {
        val url = RegionDownloader.buildTilesUrl(SourceKind.INTERNET, sourceHost = null)
        assertEquals("https://iiab.switnet.org/maps/2/$tilesFile", url)
        // UNKNOWN also routes to the internet base (the else arm).
        assertEquals(url, RegionDownloader.buildTilesUrl(SourceKind.UNKNOWN, "ignored.host"))
    }

    @Test
    fun `buildTilesUrl prefixes a bare LAN host with http and the maps path`() {
        assertEquals(
            "http://box.local/maps/$tilesFile",
            RegionDownloader.buildTilesUrl(SourceKind.IIAB_LAN, "box.local"),
        )
    }

    @Test
    fun `buildTilesUrl keeps an explicit scheme and trims a trailing slash on the LAN host`() {
        assertEquals(
            "http://box.local/maps/$tilesFile",
            RegionDownloader.buildTilesUrl(SourceKind.IIAB_LAN, "http://box.local/"),
        )
        assertEquals(
            "https://box.local/maps/$tilesFile",
            RegionDownloader.buildTilesUrl(SourceKind.IIAB_LAN, "https://box.local"),
        )
    }

    @Test
    fun `buildTilesUrl requires a non-blank host for a LAN source`() {
        try {
            RegionDownloader.buildTilesUrl(SourceKind.IIAB_LAN, sourceHost = "  ")
            fail("expected IllegalArgumentException for a blank LAN host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message?.contains("non-blank host") == true)
        }
    }
}

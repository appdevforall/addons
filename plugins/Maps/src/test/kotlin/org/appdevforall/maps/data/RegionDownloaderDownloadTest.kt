package org.appdevforall.maps.data

import android.content.ContextWrapper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.slicer.HttpRangeByteCache
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Covers the parts of [RegionDownloader] around [RegionDownloader.downloadInto] that the seam
 * tests do not: the public `download` entry point on a JVM with no external storage, a failed
 * final rename, and seams that genuinely suspend.
 */
class RegionDownloaderDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val bbox = Bbox(south = 5.0, west = -1.0, north = 6.0, east = 0.0)

    @After
    fun tearDown() {
        HttpRangeByteCache.clear()
    }

    @Test
    fun `download drops cached ranges first, then fails fast without external storage`() {
        HttpRangeByteCache.put("http://box.local/maps/x.pmtiles", 0L, 4, byteArrayOf(1, 2, 3, 4))

        val thrown = runCatching {
            runBlocking {
                RegionDownloader.download(
                    context = ContextWrapper(null),
                    regionId = "dhaka",
                    displayName = "Dhaka",
                    bbox = bbox,
                    sourceKind = SourceKind.IIAB_LAN,
                    sourceHost = "box.local",
                )
            }
        }.exceptionOrNull()

        // Environment has no external storage on a plain JVM, so the cache root cannot resolve.
        assertTrue("got $thrown", thrown is IllegalStateException)
        assertEquals("External storage unavailable", thrown!!.message)
        assertNull(
            "a download must not reuse ranges cached by the estimate loop",
            HttpRangeByteCache.get("http://box.local/maps/x.pmtiles", 0L, 4),
        )
    }

    @Test
    fun `a failed rename to the final dir throws and leaves no final dir`() {
        val root = tmp.newFolder("cache")
        val thrown = try {
            runCatching {
                runBlocking {
                    RegionDownloader.downloadInto(
                        cacheRoot = root, regionId = "dhaka", displayName = "Dhaka", bbox = bbox,
                        zoomMin = 6, zoomMax = 10, sourceKind = SourceKind.INTERNET,
                        sourceHost = null, sourceVersion = null, nowMillis = 1L,
                        onProgress = { _, _, _ -> },
                        copyBasemap = { dest, _ -> dest.writeBytes(ByteArray(3)) },
                        sliceTiles = { target, _ ->
                            target.writeBytes(ByteArray(5))
                            // A read-only cache root lets meta.json land in the partial dir but
                            // blocks the rename out of it.
                            root.setWritable(false)
                        },
                    )
                }
            }.exceptionOrNull()
        } finally {
            root.setWritable(true)
        }

        assertTrue("got $thrown", thrown is IllegalStateException)
        assertTrue(thrown!!.message!!.startsWith("Couldn't rename"))
        assertFalse(File(root, "dhaka").exists())
    }

    @Test
    fun `seams that suspend still complete the download when called from the IO dispatcher`() {
        val root = tmp.newFolder("cache")
        val phases = mutableListOf<RegionDownloader.Phase>()

        val dir = runBlocking(Dispatchers.IO) {
            RegionDownloader.downloadInto(
                cacheRoot = root, regionId = "dhaka", displayName = "Dhaka", bbox = bbox,
                zoomMin = 6, zoomMax = 10, sourceKind = SourceKind.IIAB_LAN,
                sourceHost = "box.local", sourceVersion = "2026-10-01", nowMillis = 42L,
                onProgress = { phase, _, _ -> phases += phase },
                copyBasemap = { dest, op -> dest.writeBytes(ByteArray(3)); op(3, 3) },
                sliceTiles = { target, op ->
                    yield()
                    target.writeBytes(ByteArray(5))
                    op(5, 5)
                },
            )
        }

        assertEquals(File(root, "dhaka"), dir)
        assertEquals(listOf(RegionDownloader.Phase.BASEMAP, RegionDownloader.Phase.TILES), phases)
        val meta = JSONObject(File(dir, RegionCache.FILE_META_JSON).readText())
        assertEquals(8L, meta.getLong("sizeBytes"))
        assertEquals("box.local", meta.getJSONObject("source").getString("host"))
    }
}

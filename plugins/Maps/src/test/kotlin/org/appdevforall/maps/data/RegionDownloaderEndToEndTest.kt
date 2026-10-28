package org.appdevforall.maps.data

import android.content.ContextWrapper
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.ResourceManager
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.slicer.HttpRangeByteCache
import org.appdevforall.maps.testing.UnusedPluginContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Drives the public [RegionDownloader.download] end to end: a LAN server serves a real PMTiles
 * archive by byte range, and the basemap comes from a fake plugin asset bundle.
 */
class RegionDownloaderEndToEndTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val archive = File("src/main/assets/maps/natural-earth-z0-z4.pmtiles").readBytes()
    private val world = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)
    private val server = MockWebServer()

    /** Serves [archive] at the LAN path, honouring `Range: bytes=a-b` with a 206. */
    private val rangeDispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.path != "/maps/${IiabCatalog.FALLBACK_VECTOR_ARCHIVE}") {
                return MockResponse().setResponseCode(404)
            }
            val (start, end) = request.getHeader("Range")!!.removePrefix("bytes=").split('-')
                .map { it.toLong() }
            val last = minOf(end, archive.size - 1L)
            val slice = archive.copyOfRange(start.toInt(), last.toInt() + 1)
            return MockResponse().setResponseCode(206).setBody(Buffer().write(slice))
        }
    }

    private class AssetBundle(private val basemap: ByteArray?) : ResourceManager {
        override fun openPluginAsset(path: String): InputStream? =
            if (path == "maps/natural-earth-z0-z4.pmtiles") basemap?.inputStream() else null
        override fun getPluginDirectory(): File = throw UnsupportedOperationException()
        override fun getPluginFile(path: String): File = throw UnsupportedOperationException()
        override fun getPluginResource(path: String): ByteArray = throw UnsupportedOperationException()
        override fun openPluginResource(path: String): InputStream = throw UnsupportedOperationException()
    }

    private object SilentLogger : PluginLogger {
        override val pluginId: String = MapsPlugin.PLUGIN_ID
        override fun debug(message: String) = Unit
        override fun debug(message: String, throwable: Throwable) = Unit
        override fun info(message: String) = Unit
        override fun info(message: String, throwable: Throwable) = Unit
        override fun warn(message: String) = Unit
        override fun warn(message: String, throwable: Throwable) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
    }

    private fun installAssets(basemap: ByteArray?) {
        MapsPlugin.pluginContext = object : PluginContext by UnusedPluginContext {
            override val resources: ResourceManager = AssetBundle(basemap)
            override val logger: PluginLogger = SilentLogger
        }
    }

    @Before
    fun setUp() {
        server.dispatcher = rangeDispatcher
        server.start()
        HttpRangeByteCache.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        HttpRangeByteCache.clear()
        MapsPlugin.pluginContext = null
    }

    private fun download(root: File, zoomMax: Int, sourceVersion: String? = null, zoomMin: Int = 0) =
        runBlocking {
            RegionDownloader.download(
                context = ContextWrapper(null),
                regionId = "world",
                displayName = "World",
                bbox = world,
                zoomMin = zoomMin,
                zoomMax = zoomMax,
                sourceKind = SourceKind.IIAB_LAN,
                sourceHost = server.url("/").toString(),
                sourceVersion = sourceVersion,
                cacheRoot = { root },
            )
        }

    @Test
    fun `download slices the region from the server and copies the bundled basemap`() {
        installAssets(archive)
        val root = tmp.newFolder("cache")

        val dir = download(root, zoomMax = 1)

        assertEquals(File(root, "world"), dir)
        assertArrayEquals(archive, File(dir, RegionCache.FILE_BASEMAP_PMTILES).readBytes())
        val tiles = File(dir, RegionCache.FILE_TILES_PMTILES).readBytes()
        assertEquals("PMTiles", String(tiles, 0, 7, Charsets.US_ASCII))
        // With no explicit version, the archive date comes from the file name.
        val source = JSONObject(File(dir, RegionCache.FILE_META_JSON).readText()).getJSONObject("source")
        assertEquals("2026-10-01", source.getString("version"))
        assertTrue("the server must have been asked for ranges", server.requestCount > 0)
    }

    @Test
    fun `a zoom range the archive does not hold fails and leaves no region behind`() {
        installAssets(archive)
        val root = tmp.newFolder("cache")

        val thrown = runCatching { download(root, zoomMin = 10, zoomMax = 12) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is RegionDownloader.NoTilesInRegionException)
        assertTrue(thrown!!.message!!.startsWith("No tiles intersect bbox"))
        assertFalse(File(root, "world").exists())
        assertFalse(File(root, "world.partial").exists())
    }

    @Test
    fun `no plugin context is a packaging error, not a network fallback`() {
        val root = tmp.newFolder("cache")

        val thrown = runCatching { download(root, zoomMax = 1, sourceVersion = "2026-10-01") }
            .exceptionOrNull()

        assertTrue("got $thrown", thrown is IOException)
        assertEquals(
            "bundled basemap asset 'maps/natural-earth-z0-z4.pmtiles' unavailable",
            thrown!!.message,
        )
        // Only LAN archive discovery (the listing, then the probe) may run first, like the internet
        // path's catalog fetch.
        val requests = List(server.requestCount) { server.takeRequest() }
        assertEquals(
            "no tile data may be read before the basemap",
            listOf("/maps/data/" to null, "/maps/${IiabCatalog.FALLBACK_VECTOR_ARCHIVE}" to "bytes=0-0"),
            requests.map { it.path to it.getHeader("Range") },
        )
    }

    @Test
    fun `a bundle without the basemap asset is the same packaging error`() {
        installAssets(basemap = null)
        val root = tmp.newFolder("cache")

        val thrown = runCatching { download(root, zoomMax = 1) }.exceptionOrNull()

        assertTrue("got $thrown", thrown is IOException)
        assertTrue(thrown!!.message!!.endsWith("unavailable"))
        assertFalse(File(root, "world.partial").exists())
    }
}

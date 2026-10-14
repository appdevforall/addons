package org.appdevforall.maps.data

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLogger
import java.io.File
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.slicer.SliceEstimateCache
import org.appdevforall.maps.testing.UnusedPluginContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers [RegionSizeEstimator]'s completed walk against a real PMTiles archive (the bundled
 * Natural Earth file, read over `file://`) and the failure report to the plugin logger.
 */
internal class RegionSizeEstimatorWalkTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = Collections.synchronizedList(mutableListOf<RegionSizeEstimator.State>())
    private val archiveUrl = "file://" + File("src/main/assets/maps/natural-earth-z0-z4.pmtiles").absolutePath
    private val world = Bbox(south = -85.0, west = -180.0, north = 85.0, east = 180.0)

    private class RecordingLogger : PluginLogger {
        val warnings: MutableList<String> = Collections.synchronizedList(mutableListOf())
        override val pluginId: String = MapsPlugin.PLUGIN_ID
        override fun debug(message: String) = Unit
        override fun debug(message: String, throwable: Throwable) = Unit
        override fun info(message: String) = Unit
        override fun info(message: String, throwable: Throwable) = Unit
        override fun warn(message: String) { warnings += message }
        override fun warn(message: String, throwable: Throwable) { warnings += message }
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
    }

    @Before
    fun setUp() {
        SliceEstimateCache.clear()
    }

    @After
    fun tearDown() {
        scope.cancel()
        SliceEstimateCache.clear()
        MapsPlugin.pluginContext = null
    }

    private fun awaitStates(predicate: (List<RegionSizeEstimator.State>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(states.toList())) return
            Thread.sleep(25)
        }
        assertTrue("timed out waiting; states=$states", predicate(states.toList()))
    }

    @Test
    fun `a completed walk reports Done with the tiles and caches them for the next call`() {
        val estimator = RegionSizeEstimator(scope, OkHttpClient(), states::add)

        estimator.estimate(archiveUrl, world, 0, 1)
        awaitStates { it.lastOrNull() is RegionSizeEstimator.State.Done }

        val done = states.last() as RegionSizeEstimator.State.Done
        // z0 has 1 tile and z1 has 4 for the whole world.
        assertEquals(5, done.tiles.size)
        assertEquals(RegionSizeEstimator.State.Calculating, states.first())
        assertEquals(done.tiles, SliceEstimateCache.get(archiveUrl, world, 0, 1))
    }

    @Test
    fun `a failed walk is reported to the plugin logger as well as the UI`() {
        val logger = RecordingLogger()
        MapsPlugin.pluginContext = object : PluginContext by UnusedPluginContext {
            override val logger: PluginLogger = logger
        }
        val missing = "file://" + File("build/does-not-exist.pmtiles").absolutePath
        val estimator = RegionSizeEstimator(scope, OkHttpClient(), states::add)

        estimator.estimate(missing, world, 0, 1)
        awaitStates { it.lastOrNull() == RegionSizeEstimator.State.Failed }

        assertEquals(1, logger.warnings.size)
        assertTrue(logger.warnings.single().startsWith("Slicer estimate failed ("))
    }

    @Test
    fun `a newer uncached estimate supersedes the pending one`() {
        val estimator = RegionSizeEstimator(scope, OkHttpClient(), states::add)
        val europe = Bbox(south = 35.0, west = -10.0, north = 60.0, east = 30.0)

        estimator.estimate(archiveUrl, world, 0, 1)
        estimator.estimate(archiveUrl, europe, 0, 0)
        awaitStates { it.lastOrNull() is RegionSizeEstimator.State.Done }
        Thread.sleep(500)

        // Only the second request may report; the first was cancelled during its debounce.
        val done = states.filterIsInstance<RegionSizeEstimator.State.Done>().single()
        assertEquals(1, done.tiles.size)
        assertEquals(null, SliceEstimateCache.get(archiveUrl, world, 0, 1))
    }

    @Test
    fun `cancel stops a pending estimate and is a no-op before any estimate`() {
        val estimator = RegionSizeEstimator(scope, OkHttpClient(), states::add)
        estimator.cancel()
        assertTrue(states.isEmpty())

        estimator.estimate(archiveUrl, world, 0, 1)
        estimator.cancel()
        // Past the 300 ms debounce plus the walk time a live estimate would need.
        Thread.sleep(1_000)

        assertEquals(listOf<RegionSizeEstimator.State>(RegionSizeEstimator.State.Calculating), states.toList())
    }
}

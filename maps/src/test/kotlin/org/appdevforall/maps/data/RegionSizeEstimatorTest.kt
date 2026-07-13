package org.appdevforall.maps.data

import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.slicer.SliceEstimateCache
import org.appdevforall.maps.slicer.TileEntry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins [RegionSizeEstimator]'s orchestration contract — the debounce/timeout/cache
 * plumbing is timing code that a green build says nothing about:
 *
 *  1. A cache hit reports [RegionSizeEstimator.State.Done] synchronously, with no
 *     Calculating flicker and no network.
 *  2. A cache hit CANCELS a prior in-flight walk, so the superseded request's
 *     eventual failure can never arrive after the fresh Done and flip the UI
 *     into a bogus error state (the stale-Failed trap).
 *  3. A failure for the CURRENT request still reports Failed — the staleness
 *     guard must not swallow legitimate errors.
 *
 * Uses a real scope + MockWebServer (no coroutines-test dependency in this
 * module); waits are bounded polls on the recorded state list, not bare sleeps.
 */
internal class RegionSizeEstimatorTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val states = Collections.synchronizedList(mutableListOf<RegionSizeEstimator.State>())
    private lateinit var server: MockWebServer

    // Short-timeout client so a hung/failed slicer walk resolves within test time.
    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val bboxA = Bbox(south = 5.0, west = -5.0, north = 6.0, east = -4.0)
    private val bboxB = Bbox(south = 40.0, west = 10.0, north = 41.0, east = 11.0)
    private val cachedTiles = listOf(
        TileEntry(z = 6, x = 31, y = 30, tileId = 1L, byteOffset = 0L, byteLength = 128L),
    )

    @Before
    fun setUp() {
        SliceEstimateCache.clear()
        server = MockWebServer()
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
        SliceEstimateCache.clear()
    }

    /** Poll (bounded) until [predicate] holds on the state list, else fail-fast at timeout. */
    private fun awaitStates(timeoutMs: Long = 5_000, predicate: (List<RegionSizeEstimator.State>) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate(states.toList())) return
            Thread.sleep(50)
        }
        assertTrue("timed out waiting; states=$states", predicate(states.toList()))
    }

    @Test
    fun `cache hit reports Done synchronously without Calculating`() {
        server.start()
        val url = server.url("/tiles.pmtiles").toString()
        SliceEstimateCache.put(url, bboxA, 6, 10, cachedTiles)

        val estimator = RegionSizeEstimator(scope, client, states::add)
        estimator.estimate(url, bboxA, 6, 10)

        // Synchronous contract: Done is already there when estimate() returns.
        assertEquals(listOf<RegionSizeEstimator.State>(RegionSizeEstimator.State.Done(cachedTiles)), states.toList())
        assertEquals(0, server.requestCount) // no network on a cache hit
    }

    @Test
    fun `cache hit cancels stale in-flight walk so its failure never lands after Done`() {
        // Request #1's walk hangs on a server that never responds, then dies with
        // a client timeout — WITHOUT the cancel-on-cache-hit + latest-guard it
        // would report Failed ~2s after request #2's Done.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.start()
        val hangingUrl = server.url("/world.pmtiles").toString()

        val estimator = RegionSizeEstimator(scope, client, states::add)
        estimator.estimate(hangingUrl, bboxA, 6, 10)
        awaitStates { it.contains(RegionSizeEstimator.State.Calculating) }
        // Let the debounce elapse so the walk is genuinely in flight on the socket.
        Thread.sleep(500)

        // Request #2 hits the cache → Done, and must cancel request #1's walk.
        val cachedUrl = "http://cache.invalid/other.pmtiles"
        SliceEstimateCache.put(cachedUrl, bboxB, 6, 10, cachedTiles)
        estimator.estimate(cachedUrl, bboxB, 6, 10)
        awaitStates { it.lastOrNull() == RegionSizeEstimator.State.Done(cachedTiles) }

        // Give the stale walk's failure window time to fire if it were going to
        // (client timeouts are 2 s; wait past them), then assert it never did.
        Thread.sleep(3_000)
        val trailing = states.toList().dropWhile { it != RegionSizeEstimator.State.Done(cachedTiles) }
        assertEquals(
            "no state may arrive after the fresh Done; states=$states",
            listOf<RegionSizeEstimator.State>(RegionSizeEstimator.State.Done(cachedTiles)),
            trailing,
        )
    }

    @Test
    fun `failure for the current request still reports Failed`() {
        // Server answers 500 immediately — the walk for the CURRENT request
        // fails, and the staleness guard must let that Failed through.
        server.enqueue(MockResponse().setResponseCode(500))
        server.start()
        val url = server.url("/broken.pmtiles").toString()

        val estimator = RegionSizeEstimator(scope, client, states::add)
        estimator.estimate(url, bboxA, 6, 10)

        awaitStates { it.lastOrNull() == RegionSizeEstimator.State.Failed }
        assertEquals(
            listOf(RegionSizeEstimator.State.Calculating, RegionSizeEstimator.State.Failed),
            states.toList(),
        )
    }
}

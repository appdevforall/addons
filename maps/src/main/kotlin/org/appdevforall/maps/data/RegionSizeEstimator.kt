package org.appdevforall.maps.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.slicer.PmtilesRegionSlicer
import org.appdevforall.maps.slicer.SliceEstimateCache
import org.appdevforall.maps.slicer.TileEntry

/**
 * Owns the debounced "how big is this region's download?" slicer call for the bbox picker.
 *
 * Extracted from `BboxPickerFragment` so the orchestration — debounce, hard timeout, result
 * cache, and dropping a stale result when the user has already moved the bbox — lives in one
 * place, and the Fragment is left to map the [State] callbacks onto its views. Driven by the
 * Fragment's `viewLifecycleOwner.lifecycleScope`, so its job dies with the view.
 */
internal class RegionSizeEstimator(
    private val scope: CoroutineScope,
    private val httpClient: OkHttpClient,
    private val onState: (State) -> Unit,
) {

    /** What the estimator reports back; the consumer maps these onto its UI. */
    sealed interface State {
        /** A fresh estimate is in flight (no cache hit). */
        object Calculating : State

        /** The slicer timed out or errored — show a failure, let the user proceed without a size. */
        object Failed : State

        /** Done — the sliced tile set, ready for `PmtilesRegionSlicer.estimateRegionBytes`. */
        data class Done(val tiles: List<TileEntry>) : State
    }

    private data class Request(val tilesUrl: String, val bbox: Bbox, val zoomMin: Int, val zoomMax: Int)

    private var job: Job? = null

    /** The most recent request; used to drop a slow result the user has already superseded. */
    private var latest: Request? = null

    /**
     * Estimate the download for [bbox] at [zoomMin]..[zoomMax] against [tilesUrl]. A cache hit
     * reports [State.Done] synchronously with no network; otherwise reports [State.Calculating],
     * then [State.Done] / [State.Failed] after a debounced slicer walk. Calling again cancels the
     * prior in-flight estimate.
     */
    fun estimate(tilesUrl: String, bbox: Bbox, zoomMin: Int, zoomMax: Int) {
        val req = Request(tilesUrl, bbox, zoomMin, zoomMax)
        latest = req

        // Cache hit → report real bytes immediately, no network. Cancel any
        // in-flight walk for a superseded bbox first — otherwise its eventual
        // timeout/failure callback would land AFTER this Done and flip the UI
        // into a bogus failure state.
        SliceEstimateCache.get(tilesUrl, bbox, zoomMin, zoomMax)?.let { cached ->
            job?.cancel()
            onState(State.Done(cached))
            return
        }

        onState(State.Calculating)
        job?.cancel()
        job = scope.launch {
            delay(ESTIMATE_DEBOUNCE_MS)
            Log.i(TAG, "Slicer estimate START: url=$tilesUrl bbox=$bbox z=$zoomMin..$zoomMax")
            val startMs = System.currentTimeMillis()
            val tiles = try {
                // Hard timeout so the user is never stuck on "Calculating…" forever.
                withTimeout(SLICER_TIMEOUT_MS) {
                    PmtilesRegionSlicer.tilesInRegion(
                        globalPmtilesUrl = tilesUrl,
                        bbox = bbox,
                        zoomMin = zoomMin,
                        zoomMax = zoomMax,
                        client = httpClient,
                    ).getOrThrow()
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(TAG, "Slicer estimate TIMEOUT after ${elapsed(startMs)}ms — too complex / upstream slow")
                // Same staleness guard as the Done path: a superseded request's
                // failure must not clobber state for the bbox the user is on now.
                if (latest == req) onState(State.Failed)
                return@launch
            } catch (_: CancellationException) {
                // A newer estimate is already in flight (user moved the bbox); bow out quietly.
                return@launch
            } catch (e: Throwable) {
                Log.w(TAG, "Slicer estimate FAILED after ${elapsed(startMs)}ms: ${e.javaClass.simpleName}: ${e.message}")
                MapsPlugin.pluginContext?.logger?.warn(
                    "Slicer estimate failed (${e.javaClass.simpleName}): ${e.message}"
                )
                if (latest == req) onState(State.Failed)
                return@launch
            }
            Log.i(TAG, "Slicer estimate DONE in ${elapsed(startMs)}ms: ${tiles.size} tiles")
            SliceEstimateCache.put(tilesUrl, bbox, zoomMin, zoomMax, tiles)
            // Only report if the user hasn't moved on to a newer bbox in the meantime.
            if (latest == req) onState(State.Done(tiles))
        }
    }

    /** Cancel any in-flight estimate (e.g. on view teardown). */
    fun cancel() {
        job?.cancel()
    }

    private fun elapsed(startMs: Long) = System.currentTimeMillis() - startMs

    companion object {
        private const val TAG = "RegionSizeEstimator"

        /** Debounce delay between a bbox change and the slicer kickoff (ms). */
        private const val ESTIMATE_DEBOUNCE_MS = 300L

        /** Hard timeout for the slicer's directory walk — surfaces an actionable failure state. */
        private const val SLICER_TIMEOUT_MS: Long = 60_000L
    }
}

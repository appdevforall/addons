package org.appdevforall.maps.ui

import org.appdevforall.maps.data.RegionSizeEstimator
import org.appdevforall.maps.domain.AutoShrinkBbox
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.ZoomCap
import org.appdevforall.maps.slicer.PmtilesRegionSlicer

/**
 * The bbox picker's selection + estimate state machine, extracted from
 * [BboxPickerFragment] so the transitions are JVM-unit-testable and the
 * Fragment shrinks to lifecycle + bindings + rendering.
 *
 * Deliberately a **plain class**, not an androidx `ViewModel` — no plugin has a
 * ViewModel precedent, and plain state-holders (the Forms pattern) avoid
 * classloader/lifecycle edge cases in the plugin sandbox. The Fragment owns the
 * instance, calls a transition per user/system event, and re-renders its views
 * from the read-only properties afterwards. All MapLibre/camera/view work stays
 * in the Fragment; nothing in here may import `android.*`.
 *
 * State it owns:
 *  - the selected [currentBbox] and its auto-capped zoom range ([zoomMin]/[zoomMax])
 *  - the map anchor ([anchorLat]/[anchorLon]) + whether a real GPS fix produced it
 *  - the slicer estimate results ([realByteEstimate]/[realEstimateFailed])
 *  - the Step-1 source context ([sourceKind]/[sourceHost]) the estimate needs
 */
internal class BboxSelectionModel(initialBbox: Bbox) {

    /** The user's current region selection, in lat/lon. */
    var currentBbox: Bbox = initialBbox
        private set

    var zoomMin: Int = BboxPickerArgs.DEFAULT_ZOOM_MIN
        private set

    /** Auto-capped by [ZoomCap.pickZoomMax] on every bbox change. */
    var zoomMax: Int = BboxPickerArgs.DEFAULT_ZOOM_MAX
        private set

    /** Lat/lon center the user's bbox is anchored at. */
    var anchorLat: Double = 0.0
        private set
    var anchorLon: Double = 0.0
        private set

    /**
     * True once GPS (or any LocationManager provider) has produced a real fix.
     * Don't infer this from `anchorLat != 0.0` — the camera-idle listener
     * overwrites anchorLat early in the lifecycle with whatever MapLibre's
     * default cameraPosition.target.latitude happens to be (rounded floats
     * break the equality test).
     */
    var haveRealGpsFix: Boolean = false
        private set

    /**
     * Real bytes estimate from the slicer (or null while still computing /
     * before the first network call returns). Drives Step 3's summary card
     * when present.
     */
    var realByteEstimate: Long? = null
        private set

    /**
     * True when the most recent slicer call failed (network, server, parse).
     * The Fragment's estimate line uses it to show a visible error state
     * instead of leaving the UI stuck on a "calculating…" message forever.
     */
    var realEstimateFailed: Boolean = false
        private set

    /** Source context piped in from Step 1, needed for the slicer-driven estimate. */
    var sourceKind: SourceKind = SourceKind.UNKNOWN
        private set
    var sourceHost: String? = null
        private set

    /**
     * True once the user has driven the camera with a real gesture (pan/zoom —
     * the Fragment reports MapLibre's `REASON_API_GESTURE` move-started events
     * here). Auto-shrink is gated on it: until the user takes the wheel, the
     * selection — whether the Refresh flow's saved bounds or the programmatic
     * default — is authoritative, and camera-idles triggered by our own camera
     * placement (or by transient layout/animation states of the dialog) must
     * not collapse it to whatever viewport they happen to see (ADFA-2436
     * Refresh regression: a saved ~1050 km region shrunk to ~98×160 km on open).
     */
    var userHasDrivenCamera: Boolean = false
        private set

    /** Record a user-initiated camera gesture; enables viewport-tracking shrink. */
    fun noteUserCameraGesture() {
        userHasDrivenCamera = true
    }

    /**
     * Seed the model from the picker's launch arguments (the state half of the
     * Fragment's `applyLaunchArgs`). A well-formed 4-element [prefillBbox]
     * (Refresh flow) becomes the current selection and re-centers the anchor;
     * a malformed one keeps the existing default selection.
     */
    fun applyLaunchArgs(
        sourceKind: SourceKind,
        sourceHost: String?,
        zoomMin: Int,
        zoomMax: Int,
        prefillBbox: DoubleArray?,
    ) {
        this.sourceKind = sourceKind
        this.sourceHost = sourceHost
        this.zoomMin = zoomMin
        this.zoomMax = zoomMax
        if (prefillBbox != null && prefillBbox.size == 4) {
            anchorLat = (prefillBbox[0] + prefillBbox[2]) / 2.0
            anchorLon = (prefillBbox[1] + prefillBbox[3]) / 2.0
            currentBbox = runCatching {
                Bbox(prefillBbox[0], prefillBbox[1], prefillBbox[2], prefillBbox[3])
            }.getOrDefault(currentBbox)
        }
    }

    /**
     * The one "bbox changed" transition every path funnels through
     * (corner-handle resize, initial/GPS placement, auto-shrink): adopt
     * [newBbox], recompute the auto-capped [zoomMax], and invalidate the slicer
     * estimate so the UI drops back to "Calculating…" until a fresh result lands.
     */
    fun applyBboxChange(newBbox: Bbox) {
        currentBbox = newBbox
        zoomMax = ZoomCap.pickZoomMax(currentBbox, zoomMin)
        realByteEstimate = null
        realEstimateFailed = false
    }

    /**
     * The state half of the auto-shrink check: if the current bbox has an edge
     * outside the viewport ([viewN]/[viewS]/[viewE]/[viewW]), shrink it to the
     * viewport inset by [margin] and apply that as a normal bbox change.
     * Returns the new bbox for the Fragment to push onto the overlay, or null
     * when nothing changed (bbox already fully visible / degenerate viewport).
     *
     * **Gated on [userHasDrivenCamera]:** until the user has actually panned or
     * zoomed the map, every camera-idle is one WE caused (initial placement,
     * the Refresh flow's saved-bounds fit) or a transient layout/animation
     * artifact — shrinking against those viewports silently destroys the
     * selection the user was given (the ADFA-2436 Refresh regression). A
     * programmatically-placed selection is authoritative until the user takes
     * the wheel.
     */
    fun maybeShrinkToFit(
        viewN: Double,
        viewS: Double,
        viewE: Double,
        viewW: Double,
        margin: Double,
    ): Bbox? {
        if (!userHasDrivenCamera) return null
        val newBbox = AutoShrinkBbox.computeShrunkBbox(
            bbox = currentBbox,
            viewN = viewN,
            viewS = viewS,
            viewE = viewE,
            viewW = viewW,
            margin = margin,
        ) ?: return null
        applyBboxChange(newBbox)
        return newBbox
    }

    /** Track the map camera's settled center (does NOT count as a GPS fix). */
    fun updateAnchor(lat: Double, lon: Double) {
        anchorLat = lat
        anchorLon = lon
    }

    /** A real location fix resolved — re-anchor and remember that it was real. */
    fun applyLocationFix(lat: Double, lon: Double) {
        anchorLat = lat
        anchorLon = lon
        haveRealGpsFix = true
    }

    /**
     * Map an estimator [state] onto the estimate fields. `Calculating` clears a
     * stale failure flag (but keeps the last bytes so the UI doesn't flicker);
     * `Done` converts the sliced tile set to bytes via
     * [PmtilesRegionSlicer.estimateRegionBytes].
     */
    fun onEstimateState(state: RegionSizeEstimator.State) {
        when (state) {
            RegionSizeEstimator.State.Calculating -> realEstimateFailed = false
            RegionSizeEstimator.State.Failed -> realEstimateFailed = true
            is RegionSizeEstimator.State.Done -> {
                realByteEstimate = PmtilesRegionSlicer.estimateRegionBytes(state.tiles)
                realEstimateFailed = false
            }
        }
    }

    /** True when Step 1 picked a source the slicer can actually walk. */
    fun hasSliceableSource(): Boolean = when (sourceKind) {
        SourceKind.INTERNET -> true
        SourceKind.IIAB_LAN -> !sourceHost.isNullOrBlank()
        else -> false
    }
}

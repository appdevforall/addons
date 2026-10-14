package org.appdevforall.maps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.data.MapFontExtractor
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.data.RegionSizeEstimator
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.EstimateDisplay
import org.appdevforall.maps.domain.IiabStyleBuilder
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.ZoomFit
import org.appdevforall.maps.domain.ZoomLabel
import org.appdevforall.maps.domain.TileEstimate
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Wizard step 2 — Choose Region.
 *
 * The bbox rectangle is fixed in the center of the screen; the map pans/zooms
 * underneath it via standard MapLibre gestures. [BboxOverlayView] intercepts
 * only corner-handle touches; everything else falls through to the [MapView].
 * After each camera idle, the bbox's lat/lon bounds are recomputed from the
 * pixel rect via [MapLibreMap.Projection]. Corner handles resize the box.
 *
 * Map background loads IIAB OpenMapTiles vector tiles via the `pmtiles://` URL
 * scheme from the source picked in Step 1, falling back to a solid `#e8f4f8`
 * background if that source is unreachable. No third-party tile servers.
 *
 * This is a [DialogFragment], not a regular `Fragment`, so the map UI runs in
 * its own `Window` above the host Activity. That's the only way to escape CoGo's
 * `ContentTranslatingDrawerLayout` gesture capture: every in-Activity plugin UI
 * surface sits inside the host DrawerLayout and `setDrawerLockMode` is silently
 * ignored by CoGo's `InterceptableDrawerLayout` subclass. A DialogFragment's own
 * Window keeps the host DrawerLayout out of the touch dispatch chain, so
 * left-edge swipe-rights pan the map instead of opening the file-tree drawer.
 */
class BboxPickerFragment : androidx.fragment.app.DialogFragment() {

    interface Listener {
        fun onBboxPickerNext(
            bbox: Bbox,
            /**
             * Slicer-derived estimate, or null if the slicer hasn't returned
             * yet (or failed). Step 3 should treat null as "calculating" and
             * show that state until the cache lookup completes.
             */
            estimate: TileEstimate?,
            prefillRegionId: String?,
            prefillRegionName: String?,
        )

        fun onBboxPickerBack()
    }

    companion object {
        const val REQUEST_LOCATION_PERMISSION = 0xC0A4

        /** Default bbox edge (km) when GPS is available: 50×50 km around the fix. */
        private const val GPS_DEFAULT_EDGE_KM = 50.0

        /** Default bbox edge (km) when GPS is unavailable: 200×200 km on the GMT line. */
        private const val NO_GPS_DEFAULT_EDGE_KM = 200.0

        private const val DEFAULT_BBOX_EDGE_KM = NO_GPS_DEFAULT_EDGE_KM

        // No-GPS world-view default: a 20°×20° box centered ~lat 15°N, lon 5°E
        // (Algeria / Mali / Mediterranean). Visually prominent at world-view zoom
        // so the user sees they can drag/resize it. Sized to match
        // Bbox.isReasonableRegionSize()'s ≤ 20° threshold so the startup state is
        // already a valid region.
        private const val NO_GPS_BBOX_SOUTH = 5.0
        private const val NO_GPS_BBOX_NORTH = 25.0
        private const val NO_GPS_BBOX_WEST = -5.0
        private const val NO_GPS_BBOX_EAST = 15.0

        /**
         * Fallback camera when GPS is denied or unresolved. applyInitialBbox
         * normally drives the camera; these only cover early frames before the
         * map style is ready.
         */
        private const val FALLBACK_LAT = 0.0
        private const val FALLBACK_LON = 0.0
        private const val FALLBACK_ZOOM = 1.0

        /** Zoom for the no-GPS world-view default: whole world plus a bit of detail. */
        private const val NO_GPS_WORLD_ZOOM = 1.5

        /** Screen fraction the GPS-default bbox occupies on first open (~half width). */
        private const val GPS_DEFAULT_SCREEN_FRACTION = 0.5

        /**
         * Wait this long after the camera goes idle before deciding to shrink
         * the bbox to fit the new viewport — gives the user time to finish a
         * multi-step pan/zoom gesture without thrashing the selection.
         */
        private const val AUTO_SHRINK_DEBOUNCE_MS = 1_000L

        /**
         * Inset the auto-shrunk bbox by this fraction of the viewport on each side.
         * 0.35 leaves the selection at ~30%×30% of the visible area so the user has
         * a comfortable grab-margin to pan with — a bbox that fills the viewport
         * intercepts every pan gesture as a drag-bbox.
         */
        private const val AUTO_SHRINK_MARGIN = 0.35

        fun newInstance(
            prefillRegionId: String? = null,
            prefillDisplayName: String? = null,
            prefillBbox: DoubleArray? = null,
            sourceKind: SourceKind = SourceKind.UNKNOWN,
            sourceHost: String? = null,
            zoomMin: Int = BboxPickerArgs.DEFAULT_ZOOM_MIN,
            zoomMax: Int = BboxPickerArgs.DEFAULT_ZOOM_MAX,
        ): BboxPickerFragment = BboxPickerFragment().apply {
            arguments = BboxPickerArgs(
                prefillRegionId = prefillRegionId,
                prefillDisplayName = prefillDisplayName,
                prefillBbox = prefillBbox,
                sourceKind = sourceKind,
                sourceHost = sourceHost,
                zoomMin = zoomMin,
                zoomMax = zoomMax,
            ).toBundle()
        }
    }

    private lateinit var mapView: MapView
    private lateinit var bboxOverlay: BboxOverlayView
    private lateinit var estimateSizeLine: TextView
    private lateinit var estimateFree: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnNext: MaterialButton

    /** Retained so we can enable the LocationComponent once style + permission are both ready. */
    private var mapLibreMap: MapLibreMap? = null

    /**
     * The bbox/estimate state machine — the selection, its auto-capped zoom
     * range, the anchor, and the slicer-estimate results all live here (pure
     * Kotlin, unit-tested in `BboxSelectionModelTest`). This Fragment applies a
     * transition per event and re-renders its views from the selection model's read
     * properties; MapLibre/camera/view work stays here.
     */
    private val selectionModel = BboxSelectionModel(
        initialBbox = Bbox.aroundPoint(FALLBACK_LAT, FALLBACK_LON, DEFAULT_BBOX_EDGE_KM),
    )

    private var prefillRegionId: String? = null
    private var prefillDisplayName: String? = null

    /**
     * Runs the debounced "download size" slicer call. Recreated per view (it captures the
     * view-scoped coroutine scope); [onEstimateState] maps its results onto our state + views.
     */
    private var sizeEstimator: RegionSizeEstimator? = null

    /** Held so [onDestroyView] can unregister them — they close over the
     *  overlay view and would leak across dialog re-creates otherwise. */
    private var cameraMoveListener: org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener? = null
    private var cameraIdleListener: org.maplibre.android.maps.MapLibreMap.OnCameraIdleListener? = null
    private var cameraMoveStartedListener:
        org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener? = null

    /**
     * Pending coroutine for the debounced "shrink the bbox to fit the viewport"
     * check. Cancelled + restarted on every camera idle so only the last settled
     * view triggers the check.
     */
    private var autoShrinkJob: Job? = null

    /**
     * Owns location permission + GPS-fix plumbing. Created lazily so it picks up
     * the live [mapLibreMap] and applies a resolved fix to wizard state via the
     * [applyLocation] tail.
     */
    private val locationController: MapLocationController by lazy {
        MapLocationController(
            fragment = this,
            mapProvider = { mapLibreMap },
            onLocationFix = { lat, lon -> applyLocation(lat, lon) },
        )
    }

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        themedPluginInflater(super.onGetLayoutInflater(savedInstanceState))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Material3 fullscreen dialog: no rounded corners, status-bar-respecting,
        // edge-to-edge content. We want the whole map visible.
        setStyle(
            STYLE_NORMAL,
            com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog,
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        // Fullscreen dim-free window so the map fills the screen.
        dialog.window?.apply {
            setLayout(
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
                android.view.WindowManager.LayoutParams.MATCH_PARENT,
            )
            setBackgroundDrawableResource(android.R.color.transparent)
        }
        return dialog
    }

    override fun onStart() {
        super.onStart()
        // Re-assert MATCH_PARENT after the dialog rebuilds its window on start.
        dialog?.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
        )
        if (::mapView.isInitialized) mapView.onStart()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // The MapLibre process singleton must be initialized (with a
        // plugin-resource-aware context) before the MapView inflates — the why
        // and the crash modes it guards against are documented on
        // [MapLibreBootstrap].
        MapLibreBootstrap.ensureInitialized(
            inflaterContext = inflater.context,
            appContext = requireContext().applicationContext,
        )
        return inflater.inflate(R.layout.fragment_bbox_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // 1. Grab the map, overlay, estimate labels, and nav buttons from the layout.
        bindViews(view)
        // 2. Seed the selection model from the launch args. On the Refresh flow this
        //    prefills a saved region's bbox/name; the returned prefillBbox is used later
        //    (once the style is ready) to position the camera on that saved region.
        val prefillBbox = applyLaunchArgs()
        // 3. The debounced "how big is this download?" slicer. Scoped to the view's
        //    lifecycle so it's cancelled with the view; each result comes back through
        //    onEstimateState, which folds it into selectionModel + re-renders the label.
        sizeEstimator = RegionSizeEstimator(
            scope = viewLifecycleOwner.lifecycleScope,
            httpClient = RegionDownloader.httpClient,
            onState = ::onEstimateState,
        )

        // 4. Bring MapLibre up asynchronously. configureMap runs once the map is ready
        //    and wires the style, gestures, camera listeners, and projection (and applies
        //    prefillBbox / the default view). Nothing below here needs the map yet.
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map -> configureMap(map, prefillBbox) }

        // 5. Wire the UI events. A corner-handle drag-end is the ONLY thing that changes
        //    the selection (onBboxResized); camera pan/zoom just re-projects the fixed
        //    lat/lon bbox to new pixels, it never mutates the selection.
        bboxOverlay.setListener { newBbox -> onBboxResized(newBbox) }
        btnBack.setOnClickListener { back() }
        btnNext.setOnClickListener { next() }
        // 6. Paint the initial "<W>km × <H>km — max zoom N" line before the first settle.
        updateBboxDimsLabel()
    }

    private fun bindViews(view: View) {
        mapView = view.findViewById(R.id.map_view)
        bboxOverlay = view.findViewById(R.id.bbox_overlay)
        estimateSizeLine = view.findViewById(R.id.estimate_size_line)
        estimateFree = view.findViewById(R.id.estimate_free)
        btnBack = view.findViewById(R.id.btn_back)
        btnNext = view.findViewById(R.id.btn_next)
    }

    /**
     * Parse the launch arguments into Fragment state and return the prefill bbox (if any), which
     * the style-ready callback needs to position the camera on the Refresh flow.
     */
    private fun applyLaunchArgs(): DoubleArray? {
        val args = BboxPickerArgs.fromBundle(arguments)
        prefillRegionId = args.prefillRegionId
        prefillDisplayName = args.prefillDisplayName
        selectionModel.applyLaunchArgs(
            sourceKind = args.sourceKind,
            sourceHost = args.sourceHost,
            zoomMin = args.zoomMin,
            zoomMax = args.zoomMax,
            prefillBbox = args.prefillBbox,
        )
        return args.prefillBbox
    }

    /** Wire the freshly-ready [MapLibreMap]: load the style, then gestures + camera + projection. */
    private fun configureMap(map: MapLibreMap, prefillBbox: DoubleArray?) {
        mapLibreMap = map
        loadStyleThenPosition(map, prefillBbox)

        // Geo-anchored model: the bbox is stored in lat/lon. Camera move/idle does NOT change the
        // bbox — it only re-projects the stored bbox to fresh screen pixels so the overlay tracks
        // the map's pan/zoom.
        map.uiSettings.isRotateGesturesEnabled = false
        // No annotations on the picker, so consume map clicks. (The AnnotationManager dimen-resolve
        // crash is prevented by the plugin-resource ContextWrapper in onCreateView, not here.)
        map.addOnMapClickListener { true }
        wireCameraListeners(map)
        wireProjection(map)
    }

    /**
     * Resolve the IIAB PMTiles URL + extract glyphs off the main thread (both touch disk, which
     * trips StrictMode on the UI thread), then apply the style and position the camera. The URL is
     * the same one RegionDownloader uses, so the estimate ≈ the download and the basemap matches.
     */
    private fun loadStyleThenPosition(map: MapLibreMap, prefillBbox: DoubleArray?) {
        viewLifecycleOwner.lifecycleScope.launch {
            val (pmtilesUrl, fontsRoot) = withContext(Dispatchers.IO) {
                val resources = MapsPlugin.pluginContext?.resources
                val cacheDir = context?.cacheDir
                val fonts = if (resources != null && cacheDir != null) {
                    MapFontExtractor.extract(resources, cacheDir)
                } else {
                    null
                }
                resolveIiabTilesUrl() to fonts?.absolutePath
            }
            if (view == null) return@launch
            val styleJson = IiabStyleBuilder.buildStyle(pmtilesUrl, fontsRoot)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                applyStyleReady(map, style, prefillBbox)
            }
        }
    }

    private fun wireCameraListeners(map: MapLibreMap) {
        // Hold the listener refs so onDestroyView can clear them; the lambdas close over
        // bboxOverlay and would otherwise leak across re-creates.
        val moveListener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener {
            bboxOverlay.recomputePixelRect()
        }
        val idleListener = org.maplibre.android.maps.MapLibreMap.OnCameraIdleListener {
            val center = map.cameraPosition.target ?: return@OnCameraIdleListener
            selectionModel.updateAnchor(center.latitude, center.longitude)
            bboxOverlay.recomputePixelRect()
            scheduleAutoShrinkBbox(map)
        }
        // Auto-shrink only tracks USER-driven camera changes: a REASON_API_GESTURE
        // move-started event is the one signal that the user (not our own camera
        // placement, and not a transient layout/animation state) is driving the
        // viewport. Without this gate, the camera-idle that follows the Refresh
        // flow's programmatic saved-bounds fit could collapse the saved selection
        // to a fraction of whatever viewport the idle happened to report
        // (ADFA-2436: 1054×1047 km region → 98×160 km on open).
        val moveStartedListener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener { reason ->
            if (reason ==
                org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            ) {
                selectionModel.noteUserCameraGesture()
            }
        }
        map.addOnCameraMoveListener(moveListener)
        map.addOnCameraIdleListener(idleListener)
        map.addOnCameraMoveStartedListener(moveStartedListener)
        cameraMoveListener = moveListener
        cameraIdleListener = idleListener
        cameraMoveStartedListener = moveStartedListener
    }

    /** Let the overlay convert between geographic and screen coordinates via the map's projection. */
    private fun wireProjection(map: MapLibreMap) {
        bboxOverlay.setProjection(
            toScreen = { lat, lon ->
                val p = map.projection.toScreenLocation(LatLng(lat, lon))
                p.x to p.y
            },
            toLatLon = { x, y ->
                val ll = map.projection.fromScreenLocation(android.graphics.PointF(x, y))
                ll.latitude to ll.longitude
            },
        )
    }

    private fun onBboxResized(newBbox: Bbox) = applyBboxChange(newBbox)

    /**
     * Shared tail for every "the bbox changed" path — corner-handle resize,
     * initial/GPS placement ([applyInitialBbox]), and the auto-shrink check.
     * Applies the state transition on [model] (adopt bbox, re-cap zoomMax,
     * invalidate the estimate), then re-renders the dependent views and kicks
     * a fresh slicer estimate. Previously copy-pasted in three methods.
     */
    private fun applyBboxChange(newBbox: Bbox) {
        selectionModel.applyBboxChange(newBbox)
        renderBboxState()
    }

    /** Re-render everything that depends on the bbox/estimate state + restart the estimate. */
    private fun renderBboxState() {
        renderEstimate()
        updateBboxDimsLabel()
        scheduleRealEstimate()
    }

    // ----- Map lifecycle proxying (onStart is at dialog-setup section above) -----

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        sizeEstimator?.cancel()
        sizeEstimator = null
        autoShrinkJob?.cancel()
        // Unregister listeners that capture the bbox overlay before
        // disposing the map view — prevents a leak across dialog re-creates.
        if (::mapView.isInitialized) {
            runCatching {
                mapView.getMapAsync { map ->
                    cameraMoveListener?.let { map.removeOnCameraMoveListener(it) }
                    cameraIdleListener?.let { map.removeOnCameraIdleListener(it) }
                    cameraMoveStartedListener?.let { map.removeOnCameraMoveStartedListener(it) }
                }
            }
        }
        cameraMoveListener = null
        cameraIdleListener = null
        cameraMoveStartedListener = null
        runCatching { mapView.onDestroy() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    // ----- Location permission + fix -----

    @Deprecated("Legacy Fragment permissions API; see MapLocationController note")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            locationController.onLocationPermissionGranted()
        }
    }

    /**
     * Apply a resolved fix (lat/lon) to the wizard state and animate the camera.
     * Invoked by [MapLocationController] from both the fused-success and
     * LocationManager-fallback paths; owns the Fragment-state-mutating tail.
     */
    private fun applyLocation(lat: Double, lon: Double) {
        if (view == null) return
        selectionModel.applyLocationFix(lat, lon)
        android.util.Log.i("BboxPicker", "applyLocation: ${selectionModel.anchorLat},${selectionModel.anchorLon}")
        // When GPS resolves, swap to a 50×50 km box centered on the fix, zoomed
        // to ~half the screen width. Skip if editing an existing region (prefill
        // bbox) — a late GPS fix shouldn't clobber the user's saved bounds.
        val isPrefill = BboxPickerArgs.fromBundle(arguments).prefillBbox != null
        if (isPrefill) return
        val map = mapLibreMap ?: return
        applyInitialBbox(map, Bbox.aroundPoint(selectionModel.anchorLat, selectionModel.anchorLon, GPS_DEFAULT_EDGE_KM))
    }

    // ----- IIAB tile source helpers -----

    /**
     * Resolve the IIAB PMTiles URL for the source the user selected in Step 1.
     * Delegates to [RegionDownloader.buildTilesUrl] — the same URL the actual
     * download uses — so the bbox-picker basemap and the download come from the
     * same place.
     *
     * For internet sources, the URL uses the dated fallback file in
     * [RegionDownloader.FALLBACK_VECTOR_DATE]. For LAN sources, uses
     * `http://<host>/maps/openstreetmap-openmaptiles...`.
     *
     * Returns null only when sourceKind is UNKNOWN or LAN with no host — both
     * mean "no source configured yet," which falls back to a blank-background map.
     */
    private fun resolveIiabTilesUrl(): String? = runCatching {
        when (selectionModel.sourceKind) {
            SourceKind.INTERNET -> RegionDownloader.buildTilesUrl(selectionModel.sourceKind, null)
            SourceKind.IIAB_LAN -> {
                val host = selectionModel.sourceHost?.trim().orEmpty()
                if (host.isBlank()) null
                else RegionDownloader.buildTilesUrl(selectionModel.sourceKind, host)
            }
            else -> null
        }
    }.onFailure {
        MapsPlugin.pluginContext?.logger?.warn(
            "resolveIiabTilesUrl failed: ${it.message}"
        )
    }.getOrNull()

    /**
     * Called once the MapLibre style has finished loading. Positions the camera,
     * enables the GPS dot if permission is granted, and queues the location modal.
     */
    private fun applyStyleReady(map: MapLibreMap, style: Style, prefillBbox: DoubleArray?) {
        // Initial bbox + camera:
        //   - prefill bbox provided → use the saved bbox (Refresh flow)
        //   - GPS not granted / not resolved → 200×200 km box on the GMT line,
        //     camera at world view
        //   - GPS granted → fetchLastKnownLocation fires applyLocation, which
        //     swaps in the 50×50 km GPS default
        val noGpsDefault = runCatching {
            Bbox(NO_GPS_BBOX_SOUTH, NO_GPS_BBOX_WEST, NO_GPS_BBOX_NORTH, NO_GPS_BBOX_EAST)
        }.getOrDefault(Bbox.aroundPoint(FALLBACK_LAT, FALLBACK_LON, NO_GPS_DEFAULT_EDGE_KM))
        val initialBbox = when {
            prefillBbox != null && prefillBbox.size == 4 -> runCatching {
                Bbox(prefillBbox[0], prefillBbox[1], prefillBbox[2], prefillBbox[3])
            }.getOrDefault(noGpsDefault)
            else -> noGpsDefault
        }
        // No-GPS path: force world-view zoom so the whole map is visible and the
        // default bbox renders as a clearly-visible rectangle. (GPS path uses the
        // fitting math in applyInitialBbox.)
        val worldView = (prefillBbox == null)
        android.util.Log.i(
            "BboxPicker",
            "applyStyleReady: initialBbox=$initialBbox prefill=${prefillBbox != null} worldView=$worldView"
        )
        applyInitialBbox(map, initialBbox, worldView = worldView)
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationController.enableLocationComponent(map, style)
            locationController.fetchLastKnownLocation()
        }
        // Do NOT auto-request location permission. The host CoGo APK doesn't
        // declare ACCESS_COARSE/FINE_LOCATION, so the system dialog never appears
        // and Settings → CoGo has no Location row — requesting just strands the
        // user. GPS is convenience (the user can pan/zoom manually), not
        // correctness. Re-enable via a "Center on my location" FAB once the
        // host's manifest declares the permission.
    }

    // ----- Bbox + camera initialization -----

    /**
     * Programmatically set the geographic bbox + reposition the camera so the
     * bbox fills [GPS_DEFAULT_SCREEN_FRACTION] of the screen width. Also
     * triggers a fresh slicer estimate.
     *
     * When [worldView] is true, override the bbox-fitting zoom calculation with
     * [NO_GPS_WORLD_ZOOM] — the no-GPS open path lands on a "whole world" view
     * with the bbox visible as a draggable/resizable rectangle.
     */
    private fun applyInitialBbox(map: MapLibreMap, bbox: Bbox, worldView: Boolean = false) {
        bboxOverlay.setBboxLatLon(bbox)
        val centerLat = (bbox.south + bbox.north) / 2.0
        // No-GPS path opens at world view; otherwise fit the bbox to ~half the viewport width.
        val targetZoom: Double = if (worldView) {
            NO_GPS_WORLD_ZOOM
        } else {
            ZoomFit.fitZoom(bbox, mapView.width, GPS_DEFAULT_SCREEN_FRACTION)
        }
        android.util.Log.i(
            "BboxPicker",
            "applyInitialBbox: bbox=$bbox centerLat=$centerLat widthKm=${bbox.widthKm()} " +
                "worldView=$worldView → zoom=$targetZoom",
        )
        map.cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(centerLat, (bbox.west + bbox.east) / 2.0))
            .zoom(targetZoom)
            .build()
        // The camera change triggers move/idle listeners that recompute the
        // pixel rect; applyBboxChange invalidates + reschedules the estimate.
        applyBboxChange(bbox)
    }

    /**
     * Render the estimate line. Driven by the slicer's directory walk — per-tile
     * byte count varies ~100× between dense-city and sparse-ocean tiles, so a
     * fixed bytes-per-tile constant would be misleading.
     *
     * No "bbox too big" check: `pickZoomMax` auto-caps zoom by cell budget, so
     * even a whole-world bbox opens at a low zoom with a reasonable estimate. The
     * 1 GB byte cap still applies and surfaces "over 1 GB limit" when exceeded.
     *
     * States:
     *   - no sliceable source → nothing rendered yet (waiting for source pick)
     *   - slicer running     → "Calculating download size…"
     *   - slicer failed      → "Couldn't calculate size — check connection"
     *   - slicer done, under cap → "<N> MB download size"
     *   - slicer done, over 1 GB → "<N> MB · over 1 GB limit. Choose a smaller region"
     */
    private fun renderEstimate() {
        // Decision logic (1 GB cap, calculating/failed/done states) lives in the pure,
        // unit-tested EstimateDisplay; here we only map its result onto the views.
        val state = EstimateDisplay.compute(
            realBytes = selectionModel.realByteEstimate,
            estimateFailed = selectionModel.realEstimateFailed,
            hasSliceableSource = selectionModel.hasSliceableSource(),
            // budget constants default to EstimateDisplay's own (the 1 GB cap + allowance).
        )
        estimateSizeLine.text = state.text
        // Resolve through the VIEW's context (from the themed plugin inflater),
        // not requireContext() — the host context can't resolve plugin R.color
        // ids, and only the plugin context picks up the values-night variants.
        estimateSizeLine.setTextColor(
            estimateSizeLine.context.getColor(
                if (state.isError) R.color.plugin_error else R.color.plugin_on_surface_variant,
            ),
        )
        bboxOverlay.setOverBudget(state.overBudget)
        btnNext.isEnabled = state.nextEnabled
    }

    /**
     * Kick off a debounced download-size estimate for the current bbox. The orchestration
     * (debounce, timeout, cache, staleness) lives in [RegionSizeEstimator]; [onEstimateState]
     * maps its results back onto our state + views.
     */
    private fun scheduleRealEstimate() {
        if (!selectionModel.hasSliceableSource()) return
        sizeEstimator?.estimate(
            tilesUrl = RegionDownloader.buildTilesUrl(selectionModel.sourceKind, selectionModel.sourceHost),
            bbox = selectionModel.currentBbox,
            zoomMin = selectionModel.zoomMin,
            zoomMax = selectionModel.zoomMax,
        )
    }

    /** Apply an estimator [state] on the model + re-render the size line. */
    private fun onEstimateState(state: RegionSizeEstimator.State) {
        selectionModel.onEstimateState(state)
        if (view != null) renderEstimate()
    }

    /**
     * Debounced "shrink bbox to fit viewport", mirroring Google Maps'
     * selection-tracks-viewport behavior. Fires 1 s after camera idle (once the
     * pan/zoom has settled), and only acts when the bbox has at least one edge
     * outside the current viewport.
     *
     * Only ever acts after the user's first real pan/zoom gesture — the model
     * gates [BboxSelectionModel.maybeShrinkToFit] on
     * [BboxSelectionModel.userHasDrivenCamera], so idles caused by our own
     * camera placement (initial fit, Refresh saved-bounds fit) never collapse
     * a selection the user hasn't touched.
     *
     * **Naturally no-ops when:**
     *  - User zoomed out — viewport grew, bbox still fully inside it.
     *  - User panned but bbox remained fully visible.
     *
     * **Acts when:**
     *  - User zoomed in past the bbox extent on any axis.
     *  - User panned far enough that any bbox edge crossed the viewport edge.
     *
     * New bbox = visible viewport inset by [AUTO_SHRINK_MARGIN] on every side,
     * which leaves a comfortable gap to the screen edge (Google Maps-style).
     */
    private fun scheduleAutoShrinkBbox(map: MapLibreMap) {
        autoShrinkJob?.cancel()
        autoShrinkJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTO_SHRINK_DEBOUNCE_MS)
            if (view == null) return@launch
            maybeShrinkBboxToFit(map)
        }
    }

    private fun maybeShrinkBboxToFit(map: MapLibreMap) {
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull()
            ?: return
        // The decision + state transition live in the model; a null return means
        // the bbox was already fully visible (or the viewport was degenerate).
        val newBbox = selectionModel.maybeShrinkToFit(
            viewN = bounds.latitudeNorth,
            viewS = bounds.latitudeSouth,
            viewE = bounds.longitudeEast,
            viewW = bounds.longitudeWest,
            margin = AUTO_SHRINK_MARGIN,
        ) ?: return
        bboxOverlay.setBboxLatLon(newBbox)
        renderBboxState()
    }

    /**
     * Bbox dimensions + auto-capped max zoom level line, e.g.
     * "2220km × 2140km — max zoom level 12 (town)".
     *
     * Zoom labels follow OpenStreetMap's "Zoom levels" wiki convention
     * (wiki.openstreetmap.org/wiki/Zoom_levels); for levels OSM leaves blank
     * (8, 14, 16+) we use the standard Mapbox / Bing naming.
     */
    private fun updateBboxDimsLabel() {
        val width = selectionModel.currentBbox.widthKm().toInt()
        val height = selectionModel.currentBbox.heightKm().toInt()
        val label = ZoomLabel.forZoom(selectionModel.zoomMax)
        estimateFree.text =
            "${width}km × ${height}km — max zoom level ${selectionModel.zoomMax} ($label)"
        // Same rule as renderEstimate: resolve via the themed view context so the
        // plugin palette (and its dark-mode variant) wins, not the host theme.
        estimateFree.setTextColor(
            estimateFree.context.getColor(R.color.plugin_on_surface_variant)
        )
    }

    // ----- Navigation -----

    private fun back() {
        (parentFragment as? Listener)?.onBboxPickerBack()
            ?: defaultPopBack()
    }

    private fun next() {
        // Forward the picker's auto-capped zoom range so the downloader uses it
        // instead of its z=14 default. sizeBytesEstimate is -1L when the slicer
        // hasn't returned yet — Step 3 reads that as "Calculating…".
        val forwarded = run {
            TileEstimate(
                tileCount = 0L, // tile count isn't used by Step 3
                sizeBytesEstimate = selectionModel.realByteEstimate ?: -1L,
                zoomMin = selectionModel.zoomMin,
                zoomMax = selectionModel.zoomMax,
            )
        }
        (parentFragment as? Listener)?.onBboxPickerNext(
            bbox = selectionModel.currentBbox,
            estimate = forwarded,
            prefillRegionId = prefillRegionId,
            prefillRegionName = prefillDisplayName,
        ) ?: defaultPopBack()
    }

    private fun defaultPopBack() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }
}

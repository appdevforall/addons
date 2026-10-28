package org.appdevforall.maps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.TileEstimate
import com.google.android.material.button.MaterialButton
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
        internal const val NO_GPS_DEFAULT_EDGE_KM = 200.0

        private const val DEFAULT_BBOX_EDGE_KM = NO_GPS_DEFAULT_EDGE_KM

        // No-GPS world-view default: a 20°×20° box centered ~lat 15°N, lon 5°E
        // (Algeria / Mali / Mediterranean). Visually prominent at world-view zoom
        // so the user sees they can drag/resize it. Sized to match
        // Bbox.isReasonableRegionSize()'s ≤ 20° threshold so the startup state is
        // already a valid region.
        internal const val NO_GPS_BBOX_SOUTH = 5.0
        internal const val NO_GPS_BBOX_NORTH = 25.0
        internal const val NO_GPS_BBOX_WEST = -5.0
        internal const val NO_GPS_BBOX_EAST = 15.0

        /**
         * Fallback camera when GPS is denied or unresolved. applyInitialBbox
         * normally drives the camera; these only cover early frames before the
         * map style is ready.
         */
        internal const val FALLBACK_LAT = 0.0
        internal const val FALLBACK_LON = 0.0

        /** Zoom for the no-GPS world-view default: whole world plus a bit of detail. */
        internal const val NO_GPS_WORLD_ZOOM = 1.5

        /** Screen fraction the GPS-default bbox occupies on first open (~half width). */
        internal const val GPS_DEFAULT_SCREEN_FRACTION = 0.5

        /**
         * Wait this long after the camera goes idle before deciding to shrink
         * the bbox to fit the new viewport — gives the user time to finish a
         * multi-step pan/zoom gesture without thrashing the selection.
         */
        internal const val AUTO_SHRINK_DEBOUNCE_MS = 1_000L

        /**
         * Inset the auto-shrunk bbox by this fraction of the viewport on each side.
         * 0.35 leaves the selection at ~30%×30% of the visible area so the user has
         * a comfortable grab-margin to pan with — a bbox that fills the viewport
         * intercepts every pan gesture as a drag-bbox.
         */
        internal const val AUTO_SHRINK_MARGIN = 0.35

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

    /** The estimate lines and Next button; recreated per view. */
    private var estimateBinder: EstimateLabelBinder? = null

    /** Style, camera and auto-shrink for the map; created once the map is ready. */
    private var mapController: PickerMapController? = null

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
        // 3. The estimate lines and their debounced "how big is this download?" slicer,
        //    scoped to the view's lifecycle so it's cancelled with the view.
        val binder = EstimateLabelBinder(
            selectionModel = selectionModel,
            sizeLine = estimateSizeLine,
            dimsLine = estimateFree,
            overlay = bboxOverlay,
            nextButton = btnNext,
            scope = viewLifecycleOwner.lifecycleScope,
            isViewAlive = { this.view != null },
        )
        estimateBinder = binder

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
        // The two estimate lines are what needs explaining, and they stay pressable while Next is disabled.
        listOf(estimateFree, estimateSizeLine, btnNext)
            .forEach { it.showMapsTooltipOnLongPress(MapsPlugin.TOOLTIP_TAG_BBOX_PICKER) }
        // 6. Paint the initial "<W>km × <H>km — max zoom N" line before the first settle.
        binder.renderDimensions()
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

    /** Hand the freshly-ready [MapLibreMap] to a [PickerMapController]. */
    private fun configureMap(map: MapLibreMap, prefillBbox: DoubleArray?) {
        mapLibreMap = map
        val binder = estimateBinder ?: return
        mapController = PickerMapController(
            map = map,
            overlay = bboxOverlay,
            selectionModel = selectionModel,
            scope = viewLifecycleOwner.lifecycleScope,
            mapSizeDp = {
                val density = resources.displayMetrics.density
                mapView.width / density.toDouble() to mapView.height / density.toDouble()
            },
            cacheDir = { context?.cacheDir },
            isViewAlive = { this.view != null },
            onBboxChanged = binder::onBboxChanged,
            onStyleReady = { style -> onStyleReady(map, style) },
        ).also { it.start(prefillBbox) }
    }

    private fun onBboxResized(newBbox: Bbox) = applyBboxChange(newBbox)

    /** A corner-handle resize: adopt the bbox, re-render, restart the estimate. */
    private fun applyBboxChange(newBbox: Bbox) {
        selectionModel.applyBboxChange(newBbox)
        estimateBinder?.onBboxChanged()
    }

    // ----- Map lifecycle proxying (onStart is at dialog-setup section above) -----

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        estimateBinder?.cancel()
        estimateBinder = null
        // Unregister the listeners that capture the overlay before disposing the map view.
        mapController?.release()
        mapController = null
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
        // No super call: Fragment's implementation is empty (fragment 1.8.8) and deprecated.
        if (requestCode != REQUEST_LOCATION_PERMISSION) return
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (granted) {
            locationController.onLocationPermissionGranted()
        }
    }

    /**
     * Apply a resolved fix (lat/lon) to the wizard state and animate the camera.
     * Invoked by [MapLocationController] when it finds a fix; owns the Fragment-state-mutating tail.
     */
    private fun applyLocation(lat: Double, lon: Double) {
        if (view == null) return
        selectionModel.applyLocationFix(lat, lon)
        // When GPS resolves, swap to a 50×50 km box centered on the fix, zoomed
        // to ~half the screen width. Skip if editing an existing region (prefill
        // bbox) — a late GPS fix shouldn't clobber the user's saved bounds.
        val isPrefill = BboxPickerArgs.fromBundle(arguments).prefillBbox != null
        if (isPrefill) return
        mapController?.applyInitialBbox(
            Bbox.aroundPoint(selectionModel.anchorLat, selectionModel.anchorLon, GPS_DEFAULT_EDGE_KM),
        )
    }

    /**
     * After the first camera placement: enable the GPS dot and fetch a fix if location is
     * already granted. Never requests the permission: the host APK does not declare it, so the
     * system dialog would never appear and the user would be stranded.
     */
    private fun onStyleReady(map: MapLibreMap, style: Style) {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            locationController.enableLocationComponent(map, style)
            locationController.fetchLastKnownLocation()
        }
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

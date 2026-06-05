package org.appdevforall.maps.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
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
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.domain.AutoShrinkBbox
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.TileEstimate
import org.appdevforall.maps.domain.ZoomCap
import org.appdevforall.maps.slicer.PmtilesRegionSlicer
import org.appdevforall.maps.slicer.SliceEstimateCache
import org.appdevforall.maps.slicer.TileEntry
import com.google.android.gms.location.LocationServices
import com.google.android.material.button.MaterialButton
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import org.maplibre.android.MapLibre
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
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
        const val ARG_PREFILL_REGION_ID = "prefillRegionId"
        const val ARG_PREFILL_DISPLAY_NAME = "prefillDisplayName"
        const val ARG_PREFILL_BBOX = "prefillBbox"
        const val ARG_SOURCE_KIND = "sourceKindWire"
        const val ARG_SOURCE_HOST = "sourceHost"
        const val ARG_ZOOM_MIN = "zoomMin"
        const val ARG_ZOOM_MAX = "zoomMax"

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

        /**
         * Hard cap on total download size (1 GiB). Applied to the slicer-derived
         * vector estimate plus a small basemap allowance. Step 2 disables
         * Next over the cap; Step 3 disables Save symmetrically.
         */
        internal const val MAX_DOWNLOAD_BYTES: Long = 1L * 1024L * 1024L * 1024L  // 1 GiB

        /** Headroom beyond the slicer's vector estimate, for per-tile overhead in
         *  the sliced archive. The Natural Earth basemap is copied from the plugin
         *  bundle (not downloaded), so it no longer counts toward the download size. */
        internal const val NON_VECTOR_ALLOWANCE_BYTES: Long = 4L * 1024L * 1024L

        /** Hard timeout for the slicer's directory walk. Surfaces an actionable
         *  failure state instead of letting the estimate hang on "Calculating…". */
        internal const val SLICER_TIMEOUT_MS: Long = 60_000L

        /** Estimate-text color when the bbox is downloadable (low-emphasis body). */
        private val ESTIMATE_TEXT_NORMAL: Int = Color.parseColor("#5F6368")
        /** Estimate-text color when the bbox is too large — Material red 700, same
         *  hue as the over-budget bbox border in [BboxOverlayView.setOverBudget]. */
        private val ESTIMATE_TEXT_ERROR: Int = Color.parseColor("#D32F2F")

        /** Screen fraction the GPS-default bbox occupies on first open (~half width). */
        private const val GPS_DEFAULT_SCREEN_FRACTION = 0.5

        /** Web-Mercator meters-per-pixel at zoom 0 at the equator
         *  (2 × π × earthRadius / 256). Used for the GPS-default zoom calculation. */
        private const val MERCATOR_M_PER_PX_Z0 = 156543.034

        /** Debounce delay between bbox drag-end and slicer kickoff (ms). */
        private const val ESTIMATE_DEBOUNCE_MS = 300L

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

        /** Default zoom range (matches RegionDownloader defaults). */
        private const val DEFAULT_ZOOM_MIN = 6
        private const val DEFAULT_ZOOM_MAX = 14

        /**
         * Glyph PBFs bundled in plugin assets under `assets/fonts/{stack}/{range}.pbf`.
         * Extracted to filesystem at first picker open because MapLibre's AssetFileSource
         * reads only the host APK's assets, not the plugin's. Latin coverage only;
         * non-Latin scripts render as tofu boxes until those ranges are added.
         */
        private val BUNDLED_FONT_STACKS: Map<String, List<String>> = mapOf(
            "Noto Sans Regular" to listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447"),
            "Noto Sans Italic" to listOf("0-255", "256-511", "512-767", "7680-7935", "8192-8447"),
        )

        fun newInstance(
            prefillRegionId: String? = null,
            prefillDisplayName: String? = null,
            prefillBbox: DoubleArray? = null,
            sourceKind: SourceKind = SourceKind.UNKNOWN,
            sourceHost: String? = null,
            zoomMin: Int = DEFAULT_ZOOM_MIN,
            zoomMax: Int = DEFAULT_ZOOM_MAX,
        ): BboxPickerFragment = BboxPickerFragment().apply {
            arguments = Bundle().apply {
                if (prefillRegionId != null) putString(ARG_PREFILL_REGION_ID, prefillRegionId)
                if (prefillDisplayName != null) putString(ARG_PREFILL_DISPLAY_NAME, prefillDisplayName)
                if (prefillBbox != null) putDoubleArray(ARG_PREFILL_BBOX, prefillBbox)
                putString(ARG_SOURCE_KIND, sourceKind.wireValue)
                if (sourceHost != null) putString(ARG_SOURCE_HOST, sourceHost)
                putInt(ARG_ZOOM_MIN, zoomMin)
                putInt(ARG_ZOOM_MAX, zoomMax)
            }
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

    /** Lat/lon center the user's bbox is anchored at. */
    private var anchorLat: Double = FALLBACK_LAT
    private var anchorLon: Double = FALLBACK_LON

    /**
     * True once GPS (or any LocationManager provider) has produced a real fix.
     * Don't infer this from `anchorLat != FALLBACK_LAT` — the camera-idle
     * listener overwrites anchorLat early in the lifecycle with whatever
     * MapLibre's default cameraPosition.target.latitude happens to be (rounded
     * floats break the equality test).
     */
    private var haveRealGpsFix: Boolean = false

    private var currentBbox: Bbox = Bbox.aroundPoint(FALLBACK_LAT, FALLBACK_LON, DEFAULT_BBOX_EDGE_KM)

    private var prefillRegionId: String? = null
    private var prefillDisplayName: String? = null

    /** Source context piped in from Step 1, needed for the slicer-driven estimate. */
    private var sourceKind: SourceKind = SourceKind.UNKNOWN
    private var sourceHost: String? = null
    private var zoomMin: Int = DEFAULT_ZOOM_MIN
    private var zoomMax: Int = DEFAULT_ZOOM_MAX

    /**
     * Pending coroutine for the debounced slicer call. We cancel-and-restart
     * it on every bbox change so only the latest drag-end actually runs.
     */
    private var estimateJob: Job? = null

    /** Held so [onDestroyView] can unregister them — they close over the
     *  overlay view and would leak across dialog re-creates otherwise. */
    private var cameraMoveListener: org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener? = null
    private var cameraIdleListener: org.maplibre.android.maps.MapLibreMap.OnCameraIdleListener? = null

    /**
     * Pending coroutine for the debounced "shrink the bbox to fit the viewport"
     * check. Cancelled + restarted on every camera idle so only the last settled
     * view triggers the check.
     */
    private var autoShrinkJob: Job? = null

    /**
     * Real bytes estimate from the slicer (or null while still computing /
     * before the first network call returns). Drives Step 3's summary card
     * when present.
     */
    private var realByteEstimate: Long? = null

    /**
     * True when the most recent slicer call failed (network, server, parse).
     * [renderEstimate] uses it to show a visible error state instead of leaving
     * the UI stuck on a "calculating…" message forever.
     */
    private var realEstimateFailed: Boolean = false

    /** Guard: only fire the permission ask once per fragment instance. */
    private var permissionAsked: Boolean = false

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(MapsPlugin.PLUGIN_ID, inflater)
    }

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
        // MapLibre.getInstance must run before MapView is inflated, and must use
        // the 3-arg form — the 1-arg variant is a deprecated no-op (the .so loads
        // but the Java-side INSTANCE stays null, so MapView.initialize() crashes).
        //
        // CRITICAL: pass a context whose getResources() resolves the PLUGIN's
        // resources, not the host app's. MapLibre stores this context and on a
        // single map tap does `getApplicationContext().getResources()
        // .getDimension(R.dimen.maplibre_eight_dp)` inside AnnotationManager's
        // hit-test. The host Resources have no plugin resource package attached,
        // so that lookup throws Resources$NotFoundException and crashes the whole
        // host on a misclick. The plugin inflater's context resolves plugin
        // resources; wrap it so getApplicationContext() returns itself. (An
        // addOnMapClickListener{true} mitigation can't help — it fires AFTER
        // AnnotationManager.onTap, where the crash already happened.)
        //
        // MapLibre.INSTANCE is a process-life singleton (first getInstance wins),
        // so this wrapper is pinned for the process — a bounded one-object
        // retention, not a per-open leak. The picker is the only in-process
        // MapLibre user so it reliably wins; if a future path inits MapLibre
        // first, this wrapper must move there.
        val pluginResources = inflater.context.resources
        val mapLibreContext = object : android.content.ContextWrapper(
            requireContext().applicationContext
        ) {
            override fun getResources(): android.content.res.Resources = pluginResources
            override fun getApplicationContext(): android.content.Context = this
        }
        try {
            MapLibre.getInstance(
                mapLibreContext,
                null,
                WellKnownTileServer.MapLibre
            )
        } catch (t: Throwable) {
            android.util.Log.e("MapsPlugin", "MapLibre.getInstance failed", t)
            MapsPlugin.pluginContext?.logger?.warn(
                "MapLibre.getInstance failed: ${t.message}"
            )
        }
        return inflater.inflate(R.layout.fragment_bbox_picker, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapView = view.findViewById(R.id.map_view)
        bboxOverlay = view.findViewById(R.id.bbox_overlay)
        estimateSizeLine = view.findViewById(R.id.estimate_size_line)
        estimateFree = view.findViewById(R.id.estimate_free)
        btnBack = view.findViewById(R.id.btn_back)
        btnNext = view.findViewById(R.id.btn_next)

        val args = arguments
        prefillRegionId = args?.getString(ARG_PREFILL_REGION_ID)
        prefillDisplayName = args?.getString(ARG_PREFILL_DISPLAY_NAME)
        sourceKind = SourceKind.values().firstOrNull {
            it.wireValue == args?.getString(ARG_SOURCE_KIND)
        } ?: SourceKind.UNKNOWN
        sourceHost = args?.getString(ARG_SOURCE_HOST)
        zoomMin = args?.getInt(ARG_ZOOM_MIN, DEFAULT_ZOOM_MIN) ?: DEFAULT_ZOOM_MIN
        zoomMax = args?.getInt(ARG_ZOOM_MAX, DEFAULT_ZOOM_MAX) ?: DEFAULT_ZOOM_MAX
        val prefillBbox = args?.getDoubleArray(ARG_PREFILL_BBOX)
        if (prefillBbox != null && prefillBbox.size == 4) {
            anchorLat = (prefillBbox[0] + prefillBbox[2]) / 2.0
            anchorLon = (prefillBbox[1] + prefillBbox[3]) / 2.0
            currentBbox = runCatching {
                Bbox(prefillBbox[0], prefillBbox[1], prefillBbox[2], prefillBbox[3])
            }.getOrDefault(currentBbox)
        }

        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map

            // Resolve IIAB PMTiles URL on a background thread, then apply the
            // style. We show a solid-color fallback immediately (no delay) and
            // swap in the real tiles when the URL is ready.
            //
            // URL source: same URL RegionDownloader uses for the actual download,
            // so estimate ≈ download size and the basemap matches the data.
            viewLifecycleOwner.lifecycleScope.launch {
                // Resolve the tiles URL AND extract the bundled glyph PBFs off the
                // main thread — both touch disk (the latter copies ~10 PBFs out of
                // plugin assets on a cold cache), which trips StrictMode's
                // disk-read policy if done on the UI thread.
                val (pmtilesUrl, fontsRoot) = withContext(Dispatchers.IO) {
                    resolveIiabTilesUrl() to ensureFontsExtracted()?.absolutePath
                }
                if (view == null) return@launch
                val styleJson = buildIiabStyle(pmtilesUrl, fontsRoot)
                map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                    applyStyleReady(map, style, prefillBbox)
                }
            }

            // Geo-anchored model: the bbox is stored in lat/lon. Camera move/idle
            // does NOT change the bbox — it only re-projects the stored bbox to
            // fresh screen pixels so the overlay tracks the map's pan/zoom.
            map.uiSettings.isRotateGesturesEnabled = false
            // No annotations on the picker, so consume map clicks. This is just
            // tidy tap-consumption — the AnnotationManager dimen-resolve crash is
            // prevented by the plugin-resource ContextWrapper in onCreateView, not
            // here (that crash fires inside onTap, before click listeners run).
            map.addOnMapClickListener { true }
            // Hold the listener refs so onDestroyView can clear them; the lambdas
            // close over bboxOverlay and would otherwise leak across re-creates.
            val moveListener = org.maplibre.android.maps.MapLibreMap.OnCameraMoveListener {
                bboxOverlay.recomputePixelRect()
            }
            val idleListener = org.maplibre.android.maps.MapLibreMap.OnCameraIdleListener {
                val center = map.cameraPosition.target ?: return@OnCameraIdleListener
                anchorLat = center.latitude
                anchorLon = center.longitude
                bboxOverlay.recomputePixelRect()
                scheduleAutoShrinkBbox(map)
            }
            map.addOnCameraMoveListener(moveListener)
            map.addOnCameraIdleListener(idleListener)
            cameraMoveListener = moveListener
            cameraIdleListener = idleListener
            // Wire the projection callbacks so the overlay can convert between
            // geographic and screen coordinates.
            bboxOverlay.setProjection(
                toScreen = { lat, lon ->
                    val p = map.projection.toScreenLocation(LatLng(lat, lon))
                    p.x to p.y
                },
                toLatLon = { x, y ->
                    val ll = map.projection.fromScreenLocation(
                        android.graphics.PointF(x, y)
                    )
                    ll.latitude to ll.longitude
                },
            )
        }

        // User drag-end on a corner handle updates currentBbox + kicks the
        // slicer. Camera move/idle do NOT touch currentBbox.
        bboxOverlay.setListener { newBbox ->
            currentBbox = newBbox
            zoomMax = ZoomCap.pickZoomMax(currentBbox, zoomMin)
            realByteEstimate = null
            realEstimateFailed = false
            renderEstimate()
            updateBboxDimsLabel()
            scheduleRealEstimate()
        }

        btnBack.setOnClickListener { back() }
        btnNext.setOnClickListener { next() }

        updateBboxDimsLabel()
    }

    // ----- Map lifecycle proxying (onStart is at dialog-setup section above) -----

    override fun onResume() { super.onResume(); mapView.onResume() }
    override fun onPause() { super.onPause(); mapView.onPause() }
    override fun onStop() { super.onStop(); mapView.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onDestroyView() {
        super.onDestroyView()
        estimateJob?.cancel()
        autoShrinkJob?.cancel()
        // Unregister listeners that capture the bbox overlay before
        // disposing the map view — prevents a leak across dialog re-creates.
        if (::mapView.isInitialized) {
            runCatching {
                mapView.getMapAsync { map ->
                    cameraMoveListener?.let { map.removeOnCameraMoveListener(it) }
                    cameraIdleListener?.let { map.removeOnCameraIdleListener(it) }
                }
            }
        }
        cameraMoveListener = null
        cameraIdleListener = null
        runCatching { mapView.onDestroy() }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::mapView.isInitialized) mapView.onSaveInstanceState(outState)
    }

    // ----- Location permission + fix -----

    /**
     * Enable the MapLibre location component (GPS blue dot) using the given
     * [style]. Safe to call after the style is loaded AND location permission
     * is granted. Failures are swallowed — the dot is cosmetic; the bbox
     * picker still works without it.
     *
     * [CameraMode.NONE] keeps the camera on the user-chosen anchor rather than
     * tracking to the GPS fix (we want the user to stay in control of the
     * viewport). [RenderMode.NORMAL] draws the standard blue dot + accuracy
     * circle without the compass bearing arrow.
     */
    @SuppressLint("MissingPermission")
    private fun enableLocationComponent(map: MapLibreMap, style: Style) {
        runCatching {
            val locationComponent = map.locationComponent
            locationComponent.activateLocationComponent(
                LocationComponentActivationOptions.builder(requireContext(), style)
                    .useDefaultLocationEngine(true)
                    .build()
            )
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.NORMAL
        }.onFailure {
            MapsPlugin.pluginContext?.logger?.warn(
                "LocationComponent activation failed: ${it.message}"
            )
        }
    }

    @Suppress("DEPRECATION") // Fragment.requestPermissions is deprecated in
    // favor of ActivityResultContracts.RequestPermission, but the contract API
    // needs ComponentActivity registration that races our plugin-fragment
    // lifecycle. Stick with the legacy API; it still works on target SDK 34.
    private fun maybeRequestLocation() {
        val ctx = context ?: return
        val granted = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            fetchLastKnownLocation()
            return
        }
        // Detect "permanently denied": the user previously tapped Deny (or "Don't
        // ask again"), so any new requestPermissions call is silently denied with
        // no dialog. Route them to Settings instead of repeating our rationale,
        // which would leave the GPS dot invisible with no recovery.
        @Suppress("DEPRECATION")
        val rationaleAllowed = shouldShowRequestPermissionRationale(
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (!rationaleAllowed && permissionAsked) {
            // permissionAsked guard: shouldShowRequestPermissionRationale is
            // also false on the very first request before the user has ever
            // answered; we only treat it as "permanently denied" once we've
            // asked at least once this session.
            showPermissionPermanentlyDeniedDialog(ctx)
            return
        }
        AlertDialog.Builder(ctx)
            .setTitle(R.string.maps_location_perm_title)
            .setMessage(R.string.maps_location_perm_rationale)
            .setPositiveButton(R.string.maps_location_perm_allow) { _, _ ->
                requestPermissions(
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION_PERMISSION,
                )
            }
            .setNegativeButton(R.string.maps_location_perm_deny) { _, _ ->
                // Stay on the fallback centroid; user can pan/zoom manually.
            }
            .show()
    }

    /** Open the host CoGo app's system Settings page so the user can flip
     *  Location on. After they grant + come back, [onResume] will
     *  re-evaluate and fetch the GPS fix. */
    private fun showPermissionPermanentlyDeniedDialog(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("Location is off for Code on the Go")
            .setMessage(
                "The wizard uses your current location to center the region " +
                    "you're about to download. Turn Location on for Code on the " +
                    "Go in Settings, then come back."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                runCatching {
                    val intent = android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", ctx.packageName, null)
                    )
                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                }.onFailure {
                    android.util.Log.w("BboxPicker", "Open Settings failed: ${it.message}")
                }
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    @Deprecated("Legacy Fragment permissions API; see maybeRequestLocation note")
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
            // Enable the GPS blue dot now that the style is already loaded and
            // permission has just been granted.
            mapLibreMap?.let { map ->
                map.style?.let { style -> enableLocationComponent(map, style) }
            }
            fetchLastKnownLocation()
        }
    }

    @SuppressLint("MissingPermission")
    private fun fetchLastKnownLocation() {
        val ctx = context ?: return
        val client = LocationServices.getFusedLocationProviderClient(ctx)

        // Step 1: fused getLastLocation — fast path. Returns null when no client
        // has recently subscribed to updates (a known fused-provider quirk), even
        // if Android's LocationManager holds an aged fix. Fall back below.
        client.lastLocation
            .addOnSuccessListener { loc: Location? ->
                if (loc != null) {
                    android.util.Log.i("BboxPicker", "fused.lastLocation: ${loc.latitude},${loc.longitude}")
                    applyLocation(loc)
                } else {
                    android.util.Log.i("BboxPicker", "fused.lastLocation returned null; falling back to LocationManager")
                    fallbackToLocationManager()
                }
            }
            .addOnFailureListener {
                android.util.Log.w("BboxPicker", "fused.lastLocation failed: ${it.message}; falling back to LocationManager")
                fallbackToLocationManager()
            }
    }

    /**
     * Permission-tolerant fallback when GMS' fused provider hands back null.
     * Walks the system [LocationManager]'s passive → network → gps providers
     * in order. Passive is cheapest (returns whatever any other app last cached);
     * gps is the most stale-resistant but slowest to a first fix. Any non-null
     * result wins.
     */
    @SuppressLint("MissingPermission")
    private fun fallbackToLocationManager() {
        val ctx = context ?: return
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(
            LocationManager.PASSIVE_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
        )
        val loc = providers.firstNotNullOfOrNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
                ?.also { android.util.Log.i("BboxPicker", "LocationManager.$provider: ${it.latitude},${it.longitude}") }
        }
        if (loc != null) applyLocation(loc)
        else android.util.Log.i("BboxPicker", "all LocationManager providers returned null — staying at fallback (lat=$anchorLat lon=$anchorLon)")
    }

    /**
     * Apply a resolved [Location] to the wizard state and animate the camera.
     * Shared between the fused-success and LocationManager-fallback paths.
     */
    private fun applyLocation(loc: Location) {
        if (view == null) return
        anchorLat = loc.latitude
        anchorLon = loc.longitude
        haveRealGpsFix = true
        android.util.Log.i("BboxPicker", "applyLocation: $anchorLat,$anchorLon")
        // When GPS resolves, swap to a 50×50 km box centered on the fix, zoomed
        // to ~half the screen width. Skip if editing an existing region (prefill
        // bbox) — a late GPS fix shouldn't clobber the user's saved bounds.
        val isPrefill = arguments?.getDoubleArray(ARG_PREFILL_BBOX) != null
        if (isPrefill) return
        val map = mapLibreMap ?: return
        applyInitialBbox(map, Bbox.aroundPoint(anchorLat, anchorLon, GPS_DEFAULT_EDGE_KM))
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
        when (sourceKind) {
            SourceKind.INTERNET -> RegionDownloader.buildTilesUrl(sourceKind, null)
            SourceKind.IIAB_LAN -> {
                val host = sourceHost?.trim().orEmpty()
                if (host.isBlank()) null
                else RegionDownloader.buildTilesUrl(sourceKind, host)
            }
            else -> null
        }
    }.onFailure {
        MapsPlugin.pluginContext?.logger?.warn(
            "resolveIiabTilesUrl failed: ${it.message}"
        )
    }.getOrNull()

    /**
     * Extract bundled glyph PBFs from plugin assets to filesystem cache on first
     * picker open. MapLibre's AssetFileSource reads only the host APK's assets,
     * not the plugin's — so the `asset://fonts/...` URL never resolves. Copy each
     * PBF once via [PluginContext.resources.openPluginAsset], then style.json
     * references them via `file://` at the cache path.
     *
     * Idempotent — skips files that already exist with non-zero size. Must be
     * called off the main thread (it copies ~10 PBFs out of plugin assets on a
     * cold cache); the caller invokes it inside `withContext(Dispatchers.IO)`.
     */
    private fun ensureFontsExtracted(): File? {
        val resources = MapsPlugin.pluginContext?.resources ?: run {
            android.util.Log.w("BboxPicker", "ensureFontsExtracted: pluginContext null")
            return null
        }
        val ctx = context ?: return null
        val root = File(ctx.cacheDir, "maps-plugin-fonts")
        if (!root.exists() && !root.mkdirs()) {
            android.util.Log.w("BboxPicker", "failed to mkdir $root")
            return null
        }
        var extracted = 0
        var skipped = 0
        for ((stack, ranges) in BUNDLED_FONT_STACKS) {
            val stackDir = File(root, stack)
            if (!stackDir.exists()) stackDir.mkdirs()
            for (range in ranges) {
                val dest = File(stackDir, "$range.pbf")
                if (dest.exists() && dest.length() > 0) { skipped++; continue }
                val input = resources.openPluginAsset("fonts/$stack/$range.pbf")
                if (input == null) {
                    android.util.Log.w("BboxPicker", "missing asset fonts/$stack/$range.pbf")
                    continue
                }
                runCatching {
                    input.use { src ->
                        dest.outputStream().use { dst -> src.copyTo(dst) }
                    }
                    extracted++
                }.onFailure {
                    android.util.Log.w("BboxPicker", "extract failed: $stack/$range.pbf", it)
                    dest.delete()
                }
            }
        }
        android.util.Log.i(
            "BboxPicker",
            "ensureFontsExtracted: $extracted new, $skipped cached, root=${root.absolutePath}"
        )
        return root
    }

    /**
     * Build a MapLibre v8 style JSON for the IIAB OpenMapTiles vector PMTiles at
     * [pmtilesHttpUrl] via the `pmtiles://` protocol. Layers follow the
     * OpenMapTiles v3 schema. If [pmtilesHttpUrl] is null (no source / unreachable),
     * returns a solid-color background — the picker stays functional, just without
     * tile detail.
     */
    private fun buildIiabStyle(pmtilesHttpUrl: String?, fontsRoot: String?): String {
        if (pmtilesHttpUrl == null) {
            return """{"version":8,"layers":[{"id":"background","type":"background","paint":{"background-color":"#e8f4f8"}}]}"""
        }
        val pmtilesUrl = "pmtiles://$pmtilesHttpUrl"
        // Glyphs URL: file:// into the per-app cache dir, populated off-thread by
        // ensureFontsExtracted (the caller does that before building the style).
        // MapLibre substitutes {fontstack} (URL-encoded) and {range} at fetch
        // time; the file source decodes the URL so literal-space directory names
        // on disk resolve.
        val glyphsUrl = if (fontsRoot != null) {
            "file://$fontsRoot/{fontstack}/{range}.pbf"
        } else {
            // Fonts unavailable → labels render as tofu, but polygons/lines still
            // render. Pick an asset:// URL the host can't fulfil; MapLibre logs
            // a benign Style error and proceeds without labels.
            "asset://fonts/{fontstack}/{range}.pbf"
        }
        return """
{
  "version": 8,
  "name": "IIAB OpenMapTiles",
  "glyphs": "$glyphsUrl",
  "sources": {
    "openmaptiles": {
      "type": "vector",
      "url": "$pmtilesUrl",
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [
    {
      "id": "background",
      "type": "background",
      "paint": { "background-color": "#e8f4f8" }
    },
    {
      "id": "landcover",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "landcover",
      "paint": { "fill-color": "#d8e8c8", "fill-opacity": 0.7 }
    },
    {
      "id": "park",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "park",
      "paint": { "fill-color": "#c8dba0", "fill-opacity": 0.5 }
    },
    {
      "id": "water",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "water",
      "paint": { "fill-color": "#a8c5d3" }
    },
    {
      "id": "waterway",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "waterway",
      "paint": { "line-color": "#a8c5d3", "line-width": 1 }
    },
    {
      "id": "boundary-country",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": ["all", ["==", ["get", "admin_level"], 2], ["!=", ["get", "maritime"], 1]],
      "paint": {
        "line-color": "#6c6a76",
        "line-width": ["interpolate", ["linear"], ["zoom"], 2, 0.6, 6, 1.2, 12, 2.0],
        "line-opacity": 0.8
      }
    },
    {
      "id": "boundary-state",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "boundary",
      "filter": ["all", ["==", ["get", "admin_level"], 4], ["!=", ["get", "maritime"], 1]],
      "minzoom": 4,
      "paint": {
        "line-color": "#9a98a4",
        "line-width": ["interpolate", ["linear"], ["zoom"], 4, 0.4, 12, 1.2],
        "line-dasharray": [2, 2],
        "line-opacity": 0.7
      }
    },
    {
      "id": "transportation",
      "type": "line",
      "source": "openmaptiles",
      "source-layer": "transportation",
      "paint": { "line-color": "#ffffff", "line-width": ["interpolate", ["linear"], ["zoom"], 6, 0.5, 14, 2] }
    },
    {
      "id": "building",
      "type": "fill",
      "source": "openmaptiles",
      "source-layer": "building",
      "minzoom": 13,
      "paint": { "fill-color": "#d4c9b0", "fill-opacity": 0.7 }
    },
    {
      "id": "place-city-marker",
      "type": "circle",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["in", ["get", "class"], ["literal", ["city", "town"]]],
      "minzoom": 3,
      "paint": {
        "circle-radius": ["interpolate", ["linear"], ["zoom"], 3, 1.5, 8, 3, 12, 5],
        "circle-color": "#5a5862",
        "circle-stroke-color": "#ffffff",
        "circle-stroke-width": 1
      }
    },
    {
      "id": "place-city-label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["in", ["get", "class"], ["literal", ["city", "town"]]],
      "minzoom": 4,
      "layout": {
        "text-field": ["coalesce", ["get", "name:latin"], ["get", "name"]],
        "text-font": ["Noto Sans Regular"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 4, 10, 8, 13, 12, 16],
        "text-anchor": "top",
        "text-offset": [0, 0.6],
        "text-max-width": 8
      },
      "paint": {
        "text-color": "#2a2832",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.5
      }
    },
    {
      "id": "place-country-label",
      "type": "symbol",
      "source": "openmaptiles",
      "source-layer": "place",
      "filter": ["==", ["get", "class"], "country"],
      "minzoom": 2,
      "maxzoom": 6,
      "layout": {
        "text-field": ["coalesce", ["get", "name:latin"], ["get", "name"]],
        "text-font": ["Noto Sans Italic"],
        "text-size": ["interpolate", ["linear"], ["zoom"], 2, 9, 5, 14],
        "text-transform": "uppercase",
        "text-letter-spacing": 0.1,
        "text-max-width": 7
      },
      "paint": {
        "text-color": "#5a5862",
        "text-halo-color": "#ffffff",
        "text-halo-width": 1.5
      }
    }
  ]
}
        """.trimIndent()
    }

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
            enableLocationComponent(map, style)
            fetchLastKnownLocation()
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
        currentBbox = bbox
        zoomMax = ZoomCap.pickZoomMax(currentBbox, zoomMin)
        bboxOverlay.setBboxLatLon(bbox)
        val centerLat = (bbox.south + bbox.north) / 2.0
        val targetZoom: Double = if (worldView) {
            NO_GPS_WORLD_ZOOM
        } else {
            // Pick a zoom that puts the bbox at ~50% of the screen width.
            // Real pixel size in the bottom-sheet picker varies (~900-1080
            // px wide on the A56), but the math is the same:
            //   screenPxForBboxWidth = mapView.width * fraction
            //   metersPerPxAtZ = MERCATOR_M_PER_PX_Z0 * cos(centerLatRad) / 2^z
            //   → 2^z = MERCATOR_M_PER_PX_Z0 * cosLat * targetPx / widthMeters
            val widthMeters = bbox.widthKm() * 1000.0
            val targetPx = (mapView.width.takeIf { it > 0 } ?: 1080) * GPS_DEFAULT_SCREEN_FRACTION
            val cosLat = kotlin.math.cos(Math.toRadians(centerLat)).coerceAtLeast(0.01)
            val twoToZ = MERCATOR_M_PER_PX_Z0 * cosLat * targetPx / widthMeters
            kotlin.math.log2(twoToZ).coerceIn(0.0, 16.0)
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
        // pixel rect; the slicer estimate fires next.
        updateBboxDimsLabel()
        realByteEstimate = null
        realEstimateFailed = false
        renderEstimate()
        scheduleRealEstimate()
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
        if (!hasSliceableSource()) {
            estimateSizeLine.text = ""
            estimateSizeLine.setTextColor(ESTIMATE_TEXT_NORMAL)
            bboxOverlay.setOverBudget(false)
            btnNext.isEnabled = false
            return
        }
        val realBytes = realByteEstimate
        if (realBytes != null) {
            val totalBytes = realBytes + NON_VECTOR_ALLOWANCE_BYTES
            val mb = realBytes / (1024.0 * 1024.0)
            if (totalBytes > MAX_DOWNLOAD_BYTES) {
                // Over the 1 GB cap: text turns red AND the bbox overlay tints red.
                val totalMb = totalBytes / (1024.0 * 1024.0)
                estimateSizeLine.text = "%.0f MB · over 1 GB limit. Choose a smaller region"
                    .format(totalMb)
                estimateSizeLine.setTextColor(ESTIMATE_TEXT_ERROR)
                bboxOverlay.setOverBudget(true)
                btnNext.isEnabled = false
            } else {
                estimateSizeLine.text = "%.1f MB download size".format(mb)
                estimateSizeLine.setTextColor(ESTIMATE_TEXT_NORMAL)
                bboxOverlay.setOverBudget(false)
                btnNext.isEnabled = true
            }
            return
        }
        // Slicer hasn't returned (yet, or failed). Show only one of those two
        // states — never an unreliable synthetic number alongside.
        estimateSizeLine.text = if (realEstimateFailed) {
            "Couldn't calculate size — check connection"
        } else {
            "Calculating download size…"
        }
        estimateSizeLine.setTextColor(
            if (realEstimateFailed) ESTIMATE_TEXT_ERROR else ESTIMATE_TEXT_NORMAL,
        )
        // While slicer is in-flight we don't yet know if it's over budget —
        // reset overlay styling to normal so the box doesn't stay red after
        // the user shrinks it.
        bboxOverlay.setOverBudget(false)
        // Allow Next while calculating — user can move to Step 3 even before
        // the estimate completes; Step 3 also shows "Calculating…". But if the
        // last attempt failed, disable until we can re-estimate successfully.
        btnNext.isEnabled = !realEstimateFailed
    }

    /**
     * Kick off a debounced slicer call against the global tiles URL. The slicer
     * walks the v3 header + root directory + any overlapping leaves and returns
     * tile entries with real byte lengths; we sum those for the estimate.
     *
     * Results are cached in [SliceEstimateCache] so Step 3 (and any drag-back
     * to the same bbox) reuses without re-walking the network.
     */
    private fun scheduleRealEstimate() {
        if (!hasSliceableSource()) return
        val tilesUrl = RegionDownloader.buildTilesUrl(sourceKind, sourceHost)
        val bbox = currentBbox
        val zMin = zoomMin
        val zMax = zoomMax

        // Cache hit → render real bytes immediately, no network.
        SliceEstimateCache.get(tilesUrl, bbox, zMin, zMax)?.let { cached ->
            applyRealEstimate(cached)
            return
        }

        // New attempt — clear any stale failure state so the suffix re-shows
        // "estimating…" instead of "couldn't estimate exact size" while we wait.
        realEstimateFailed = false
        if (view != null) renderEstimate()
        estimateJob?.cancel()
        estimateJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(ESTIMATE_DEBOUNCE_MS)
            android.util.Log.i(
                "BboxPickerFragment",
                "Slicer estimate START: url=$tilesUrl bbox=$bbox z=$zMin..$zMax",
            )
            val startMs = System.currentTimeMillis()
            val tiles = try {
                // Hard timeout so the user is never stuck on "Calculating…"
                // forever — surface a failure and let them proceed without a size.
                kotlinx.coroutines.withTimeout(SLICER_TIMEOUT_MS) {
                    PmtilesRegionSlicer.tilesInRegion(
                        globalPmtilesUrl = tilesUrl,
                        bbox = bbox,
                        zoomMin = zMin,
                        zoomMax = zMax,
                        client = RegionDownloader.httpClient,
                    ).getOrThrow()
                }
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                val elapsed = System.currentTimeMillis() - startMs
                android.util.Log.w(
                    "BboxPickerFragment",
                    "Slicer estimate TIMEOUT after ${elapsed}ms — region too complex or upstream slow",
                )
                realEstimateFailed = true
                if (view != null) renderEstimate()
                return@launch
            } catch (_: kotlinx.coroutines.CancellationException) {
                // A newer estimate is already in flight (user moved the bbox);
                // quietly bow out without touching state — the newer job will
                // win and set the proper flags.
                return@launch
            } catch (e: Throwable) {
                val elapsed = System.currentTimeMillis() - startMs
                android.util.Log.w(
                    "BboxPickerFragment",
                    "Slicer estimate FAILED after ${elapsed}ms: ${e.javaClass.simpleName}: ${e.message}",
                )
                MapsPlugin.pluginContext?.logger?.warn(
                    "Slicer estimate failed (${e.javaClass.simpleName}): ${e.message}"
                )
                realEstimateFailed = true
                if (view != null) renderEstimate()
                return@launch
            }
            val elapsed = System.currentTimeMillis() - startMs
            android.util.Log.i(
                "BboxPickerFragment",
                "Slicer estimate DONE in ${elapsed}ms: ${tiles.size} tiles",
            )
            SliceEstimateCache.put(tilesUrl, bbox, zMin, zMax, tiles)
            if (currentBbox == bbox) applyRealEstimate(tiles)
        }
    }

    private fun applyRealEstimate(tiles: List<TileEntry>) {
        realByteEstimate = PmtilesRegionSlicer.estimateRegionBytes(tiles)
        if (view != null) renderEstimate()
    }

    private fun hasSliceableSource(): Boolean = when (sourceKind) {
        SourceKind.INTERNET -> true
        SourceKind.IIAB_LAN -> !sourceHost.isNullOrBlank()
        else -> false
    }

    /**
     * Debounced "shrink bbox to fit viewport", mirroring Google Maps'
     * selection-tracks-viewport behavior. Fires 1 s after camera idle (once the
     * pan/zoom has settled), and only acts when the bbox has at least one edge
     * outside the current viewport.
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
        val newBbox = AutoShrinkBbox.computeShrunkBbox(
            bbox = currentBbox,
            viewN = bounds.latitudeNorth,
            viewS = bounds.latitudeSouth,
            viewE = bounds.longitudeEast,
            viewW = bounds.longitudeWest,
            margin = AUTO_SHRINK_MARGIN,
        ) ?: return

        currentBbox = newBbox
        zoomMax = ZoomCap.pickZoomMax(currentBbox, zoomMin)
        realByteEstimate = null
        realEstimateFailed = false
        bboxOverlay.setBboxLatLon(newBbox)
        renderEstimate()
        updateBboxDimsLabel()
        scheduleRealEstimate()
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
        val width = currentBbox.widthKm().toInt()
        val height = currentBbox.heightKm().toInt()
        val label = zoomLabel(zoomMax)
        estimateFree.text =
            "${width}km × ${height}km — max zoom level $zoomMax ($label)"
        estimateFree.setTextColor(
            requireContext().getColor(
                com.google.android.material.R.color.material_on_surface_emphasis_medium
            )
        )
    }

    private fun zoomLabel(z: Int): String = when (z) {
        in 0..2 -> "world"
        in 3..5 -> "country"
        in 6..7 -> "country / state"
        8 -> "region"
        in 9..10 -> "metro area"
        11 -> "city"
        12 -> "town"
        13 -> "village"
        14 -> "streets"
        in 15..16 -> "small roads"
        else -> "buildings"
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
                sizeBytesEstimate = realByteEstimate ?: -1L,
                zoomMin = zoomMin,
                zoomMax = zoomMax,
            )
        }
        (parentFragment as? Listener)?.onBboxPickerNext(
            bbox = currentBbox,
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

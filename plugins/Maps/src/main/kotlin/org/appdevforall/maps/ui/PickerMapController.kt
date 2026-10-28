package org.appdevforall.maps.ui

import android.graphics.PointF
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.data.IiabArchiveResolver
import org.appdevforall.maps.data.MapFontExtractor
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.IiabStyleBuilder
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.ZoomFit
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.AUTO_SHRINK_DEBOUNCE_MS
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.AUTO_SHRINK_MARGIN
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.FALLBACK_LAT
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.FALLBACK_LON
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.GPS_DEFAULT_SCREEN_FRACTION
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_BBOX_EAST
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_BBOX_NORTH
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_BBOX_SOUTH
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_BBOX_WEST
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_DEFAULT_EDGE_KM
import org.appdevforall.maps.ui.BboxPickerFragment.Companion.NO_GPS_WORLD_ZOOM
import org.appdevforall.maps.util.runCatchingCancellable
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style

/**
 * Drives the bbox picker's [MapLibreMap]: loads the IIAB style, places the first camera and
 * bbox, keeps [overlay] projected through pans and zooms, and shrinks the bbox to fit after the
 * user's own pans.
 *
 * @param mapWidth the map view's current width in pixels, for fitting the first bbox
 * @param cacheDir where [MapFontExtractor] unpacks glyphs, or null once the fragment detaches
 * @param isViewAlive false once the picker's view is gone, so late callbacks do nothing
 * @param onBboxChanged re-renders whatever depends on the selection after the bbox changes
 * @param onStyleReady runs after the first camera placement, for the picker's location wiring
 */
internal class PickerMapController(
    private val map: MapLibreMap,
    private val overlay: BboxOverlayView,
    private val selectionModel: BboxSelectionModel,
    private val scope: CoroutineScope,
    private val mapWidth: () -> Int,
    private val cacheDir: () -> File?,
    private val isViewAlive: () -> Boolean,
    private val onBboxChanged: () -> Unit,
    private val onStyleReady: (Style) -> Unit,
) {

    // Held so release() can unregister them: they close over the overlay and would leak across
    // dialog re-creates otherwise.
    private var cameraMoveListener: MapLibreMap.OnCameraMoveListener? = null
    private var cameraIdleListener: MapLibreMap.OnCameraIdleListener? = null
    private var cameraMoveStartedListener: MapLibreMap.OnCameraMoveStartedListener? = null

    /** The pending auto-shrink check, restarted on every camera idle so only the last one runs. */
    private var autoShrinkJob: Job? = null

    /** Loads the style, then wires gestures, camera listeners and projection. */
    fun start(prefillBbox: DoubleArray?) {
        loadStyleThenPosition(prefillBbox)
        // The bbox is stored in lat/lon: camera moves only re-project it to fresh pixels.
        map.uiSettings.isRotateGesturesEnabled = false
        // No annotations on the picker, so consume map clicks. (The AnnotationManager dimen-resolve
        // crash is prevented by MapLibreBootstrap's plugin-resource context, not here.)
        map.addOnMapClickListener { true }
        wireCameraListeners()
        wireProjection()
    }

    /** Cancels the auto-shrink check and unregisters the camera listeners. */
    fun release() {
        autoShrinkJob?.cancel()
        runCatching {
            cameraMoveListener?.let { map.removeOnCameraMoveListener(it) }
            cameraIdleListener?.let { map.removeOnCameraIdleListener(it) }
            cameraMoveStartedListener?.let { map.removeOnCameraMoveStartedListener(it) }
        }
        cameraMoveListener = null
        cameraIdleListener = null
        cameraMoveStartedListener = null
    }

    /**
     * Sets [bbox] and moves the camera to fit it to [GPS_DEFAULT_SCREEN_FRACTION] of the map width,
     * or to [NO_GPS_WORLD_ZOOM] when [worldView] is true.
     */
    fun applyInitialBbox(bbox: Bbox, worldView: Boolean = false) {
        overlay.setBboxLatLon(bbox)
        val targetZoom =
            if (worldView) NO_GPS_WORLD_ZOOM
            else ZoomFit.fitZoom(bbox, mapWidth(), GPS_DEFAULT_SCREEN_FRACTION)
        map.cameraPosition =
            CameraPosition.Builder()
                .target(LatLng((bbox.south + bbox.north) / 2.0, (bbox.west + bbox.east) / 2.0))
                .zoom(targetZoom)
                .build()
        // The camera move re-projects the overlay through the listeners.
        selectionModel.applyBboxChange(bbox)
        onBboxChanged()
    }

    /**
     * Resolves the tiles URL and extracts glyphs on `Dispatchers.IO` (both touch disk), then applies
     * the style. The URL is the one the download reads, so the basemap and estimate match it.
     */
    private fun loadStyleThenPosition(prefillBbox: DoubleArray?) {
        scope.launch {
            val (pmtilesUrl, fontsRoot) =
                withContext(Dispatchers.IO) {
                    val resources = MapsPlugin.pluginContext?.resources
                    val cacheDir = cacheDir()
                    val fonts =
                        if (resources != null && cacheDir != null) {
                            MapFontExtractor.extract(resources, cacheDir)
                        } else {
                            null
                        }
                    // Resolve the current archive before the first estimate builds its URL.
                    when (selectionModel.sourceKind) {
                        SourceKind.INTERNET -> IiabArchiveResolver.shared.resolve()
                        // A box with no archive surfaces as an estimate error instead.
                        SourceKind.IIAB_LAN ->
                            runCatchingCancellable {
                                RegionDownloader.resolveTilesUrl(SourceKind.IIAB_LAN, selectionModel.sourceHost)
                            }
                        else -> Unit
                    }
                    resolveIiabTilesUrl() to fonts?.absolutePath
                }
            if (!isViewAlive()) return@launch
            val styleJson = IiabStyleBuilder.buildStyle(pmtilesUrl, fontsRoot)
            map.setStyle(Style.Builder().fromJson(styleJson)) { style ->
                applyStyleReady(style, prefillBbox)
            }
        }
    }

    /**
     * Places the first bbox: the saved one on the Refresh flow, else a 20-degree box at world view.
     * A location fix later swaps in its own box through [applyInitialBbox].
     */
    private fun applyStyleReady(style: Style, prefillBbox: DoubleArray?) {
        val noGpsDefault =
            runCatching { Bbox(NO_GPS_BBOX_SOUTH, NO_GPS_BBOX_WEST, NO_GPS_BBOX_NORTH, NO_GPS_BBOX_EAST) }
                .getOrDefault(Bbox.aroundPoint(FALLBACK_LAT, FALLBACK_LON, NO_GPS_DEFAULT_EDGE_KM))
        val initialBbox =
            when {
                prefillBbox != null && prefillBbox.size == 4 ->
                    runCatching { Bbox(prefillBbox[0], prefillBbox[1], prefillBbox[2], prefillBbox[3]) }
                        .getOrDefault(noGpsDefault)
                else -> noGpsDefault
            }
        applyInitialBbox(initialBbox, worldView = prefillBbox == null)
        onStyleReady(style)
    }

    private fun wireCameraListeners() {
        val moveListener = MapLibreMap.OnCameraMoveListener { overlay.recomputePixelRect() }
        val idleListener =
            MapLibreMap.OnCameraIdleListener {
                val center = map.cameraPosition.target ?: return@OnCameraIdleListener
                selectionModel.updateAnchor(center.latitude, center.longitude)
                overlay.recomputePixelRect()
                scheduleAutoShrinkBbox()
            }
        // Only a REASON_API_GESTURE move is the user driving the viewport. Without this gate the idle
        // after the Refresh flow's own saved-bounds fit collapsed a 1054x1047 km region to 98x160 km.
        val moveStartedListener =
            MapLibreMap.OnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
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

    /** Lets the overlay convert between geographic and screen coordinates. */
    private fun wireProjection() {
        overlay.setProjection(
            toScreen = { lat, lon ->
                val p = map.projection.toScreenLocation(LatLng(lat, lon))
                p.x to p.y
            },
            toLatLon = { x, y ->
                val ll = map.projection.fromScreenLocation(PointF(x, y))
                ll.latitude to ll.longitude
            },
        )
    }

    /**
     * The tiles URL for the source picked in Step 1, from [RegionDownloader.buildTilesUrl] so it is
     * the file the download reads. Null when no source is set yet, which loads a blank background.
     */
    private fun resolveIiabTilesUrl(): String? =
        runCatching {
                when (selectionModel.sourceKind) {
                    SourceKind.INTERNET -> RegionDownloader.buildTilesUrl(selectionModel.sourceKind, null)
                    SourceKind.IIAB_LAN -> {
                        val host = selectionModel.sourceHost?.trim().orEmpty()
                        if (host.isBlank()) null
                        else RegionDownloader.buildTilesUrl(selectionModel.sourceKind, host)
                    }
                    else -> null
                }
            }
            .onFailure { MapsPlugin.pluginContext?.logger?.warn("resolveIiabTilesUrl failed: ${it.message}") }
            .getOrNull()

    /**
     * Shrinks the bbox to the viewport [AUTO_SHRINK_DEBOUNCE_MS] after the camera settles, if an
     * edge left the view. The model ignores idles before the user's first gesture.
     */
    private fun scheduleAutoShrinkBbox() {
        autoShrinkJob?.cancel()
        autoShrinkJob =
            scope.launch {
                delay(AUTO_SHRINK_DEBOUNCE_MS)
                if (!isViewAlive()) return@launch
                maybeShrinkBboxToFit()
            }
    }

    private fun maybeShrinkBboxToFit() {
        val bounds = runCatching { map.projection.visibleRegion.latLngBounds }.getOrNull() ?: return
        // Null means the bbox was already fully visible, or the viewport was degenerate.
        val newBbox =
            selectionModel.maybeShrinkToFit(
                viewN = bounds.latitudeNorth,
                viewS = bounds.latitudeSouth,
                viewE = bounds.longitudeEast,
                viewW = bounds.longitudeWest,
                margin = AUTO_SHRINK_MARGIN,
            ) ?: return
        overlay.setBboxLatLon(newBbox)
        onBboxChanged()
    }
}

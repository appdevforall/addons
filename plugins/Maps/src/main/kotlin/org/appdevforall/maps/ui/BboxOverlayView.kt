package org.appdevforall.maps.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import org.appdevforall.maps.R
import org.appdevforall.maps.domain.Bbox
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Bounding-box overlay for the "Choose Region" step.
 *
 * **Geo-anchored model:** the box stores its corners in **lat/lon**, not pixels,
 * so it stays anchored to the same patch of the world as the map pans and zooms
 * underneath. To draw, the overlay re-projects the stored geo corners to screen
 * pixels via a caller-supplied projection (set via [setProjection] from
 * [BboxPickerFragment]).
 *
 * Touch model:
 *  - Corner handles (48 dp hit targets): consumed by this view — resize the box.
 *    The dragged corner's pixel position is projected back to lat/lon and the
 *    corresponding latitude AND longitude are updated, keeping the box
 *    axis-aligned in lat/lon space.
 *  - Inside the box (but not on a corner): consumed by this view — translate
 *    the box. Each move delta is converted from pixel-delta to lat/lon-delta
 *    and applied to all four corners so the box keeps its dimensions and
 *    shifts to a new geographic patch.
 *  - All other touches (outside the box entirely): returned `false` so the
 *    [MapView] underneath receives them and handles pan/pinch-zoom naturally.
 *
 * The opposite corner stays anchored; the two adjacent corners each share one
 * coordinate with the dragged corner. Result is always a valid axis-aligned
 * rectangle in lat/lon space (which renders as a near-rectangle on screen at
 * non-polar latitudes with rotation disabled).
 */
class BboxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** Listener fires when the user resizes the box. Argument is the **new
     *  geographic** bbox (the source of truth). */
    fun interface Listener {
        fun onBboxChanged(bbox: Bbox)
    }

    /** Projection callbacks from the host fragment (wired to MapLibre's
     *  `map.projection.toScreenLocation` / `fromScreenLocation`).
     *  `null` until the map is ready. */
    private var toScreen: ((lat: Double, lon: Double) -> Pair<Float, Float>)? = null
    private var toLatLon: ((x: Float, y: Float) -> Pair<Double, Double>)? = null

    /** Source-of-truth geographic bbox. Null until the fragment sets one. */
    private var geoBbox: Bbox? = null

    /** Last computed pixel rect — derived from [geoBbox] + projection on
     *  every [recomputePixelRect] call. Empty when not renderable. */
    private val rect = RectF()

    private val handleHitRadius = dp(24f)   // 48 dp diameter
    private val handleVisibleRadius = dp(10f) // 20 dp diameter
    private val borderWidth = dp(1.5f)

    private val normalFillColor = Color.argb(46, 80, 80, 80)            // 18 % grey
    private val normalBorderColor = Color.argb(153, 0, 0, 0)            // ~60 % black
    private val overBudgetFillColor = Color.argb(64, 211, 47, 47)       // ~25 % Material red 700
    private val overBudgetBorderColor = Color.argb(217, 211, 47, 47)    // ~85 % Material red 700

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = normalFillColor
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = borderWidth
        color = normalBorderColor
    }

    /** When true, the box renders error-red instead of neutral grey — set via
     *  [setOverBudget] when the estimate exceeds the 1 GB cap. */
    private var overBudget: Boolean = false
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#16A34A")
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
        color = Color.WHITE
    }

    private var listener: Listener? = null
    private var dragMode: DragMode = DragMode.NONE
    /** Last touch position during a TRANSLATE drag. Pixel-deltas between
     *  successive ACTION_MOVE events get projected to lat/lon-deltas. Null
     *  outside an active translate. */
    private var lastTouchPx: PointF? = null
    private val minSidePx = dp(48f)
    /** Smallest box we'll let the user drag down to, in degrees. Prevents a
     *  bbox from collapsing to zero size when the user drags a corner past
     *  the opposite corner; also caps the slicer's tile count cost. */
    private val minSideDeg = 0.001

    init {
        runCatching {
            handleFillPaint.color = ContextCompat.getColor(context, R.color.plugin_primary)
        }
    }

    /**
     * Wire the projection callbacks from MapLibre's `Projection`. Must be
     * called BEFORE [setBboxLatLon] (or call [setBboxLatLon] again after to
     * trigger a recompute).
     */
    fun setProjection(
        toScreen: (lat: Double, lon: Double) -> Pair<Float, Float>,
        toLatLon: (x: Float, y: Float) -> Pair<Double, Double>,
    ) {
        this.toScreen = toScreen
        this.toLatLon = toLatLon
        recomputePixelRect()
    }

    /** Set the geographic bbox. Triggers a pixel-rect recompute + redraw. */
    fun setBboxLatLon(bbox: Bbox) {
        geoBbox = bbox
        recomputePixelRect()
    }

    /**
     * Flip the overlay to "over-budget" styling — red border + tinted fill.
     * Driven by [BboxPickerFragment] when the slicer's estimate exceeds the
     * 1 GB cap. Visual reinforcement of the inline estimate text so the user
     * sees the warning on the map itself, not just below it.
     */
    fun setOverBudget(over: Boolean) {
        if (over == overBudget) return
        overBudget = over
        fillPaint.color = if (over) overBudgetFillColor else normalFillColor
        borderPaint.color = if (over) overBudgetBorderColor else normalBorderColor
        invalidate()
    }

    /** Re-project [geoBbox] to screen pixels using the current camera.
     *  Cheap; safe to call on every camera-move tick. */
    fun recomputePixelRect() {
        val bbox = geoBbox
        val project = toScreen
        if (bbox == null || project == null || width == 0 || height == 0) {
            if (!rect.isEmpty) {
                rect.setEmpty()
                invalidate()
            }
            return
        }
        // Project all 4 corners. The MapLibre projection puts north at top,
        // so (north, west) is the TL corner and (south, east) is the BR.
        val (tlX, tlY) = project(bbox.north, bbox.west)
        val (brX, brY) = project(bbox.south, bbox.east)
        rect.set(
            min(tlX, brX),
            min(tlY, brY),
            max(tlX, brX),
            max(tlY, brY),
        )
        invalidate()
    }

    fun setListener(l: Listener?) {
        listener = l
    }

    /** Current pixel-projected rect. Mostly used for hit-tests + tests. */
    fun currentBboxPx(): RectF = RectF(rect)

    /** Current geographic bbox, or null if none set. */
    fun currentBboxGeo(): Bbox? = geoBbox

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Re-project on resize (e.g., bottom sheet expand/collapse).
        recomputePixelRect()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rect.isEmpty) return
        canvas.drawRect(rect, fillPaint)
        canvas.drawRect(rect, borderPaint)
        // Corner handles
        val corners = floatArrayOf(
            rect.left, rect.top,
            rect.right, rect.top,
            rect.left, rect.bottom,
            rect.right, rect.bottom
        )
        var i = 0
        while (i < corners.size) {
            val cx = corners[i]
            val cy = corners[i + 1]
            canvas.drawCircle(cx, cy, handleVisibleRadius, handleFillPaint)
            canvas.drawCircle(cx, cy, handleVisibleRadius, handleStrokePaint)
            i += 2
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val mode = hitTest(event.x, event.y)
                if (mode == DragMode.NONE) return false
                dragMode = mode
                if (mode == DragMode.TRANSLATE) {
                    lastTouchPx = PointF(event.x, event.y)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragMode == DragMode.NONE) return false
                if (dragMode == DragMode.TRANSLATE) {
                    handleTranslateMove(event.x, event.y)
                } else {
                    handleDragMove(event.x, event.y)
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    // Fire ONE listener event at drag-end (with the final
                    // geo bbox). Per-frame fires during drag would re-trigger
                    // the slicer on every pixel, which is wasteful.
                    geoBbox?.let { listener?.onBboxChanged(it) }
                    dragMode = DragMode.NONE
                    lastTouchPx = null
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                return false
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Hit-test priority:
     *   1. Corner handles (with a size-scaled hit radius) → RESIZE_*
     *   2. Inside the box (not on a corner) → TRANSLATE
     *   3. Outside → NONE (event passes through to MapView)
     *
     * **Corner radius scales with box size** so that corners stay usable on
     * small on-screen boxes while still leaving a translate-only band in the
     * box centre. The effective radius is `min(handleHitRadius, smallerSide /
     * 3f)`. With this rule:
     *  - 60px box  → ~20px corner radius, ~20px centre band → 4 corners + a
     *    finger-tip-sized translate target.
     *  - 240px+ box → 48px corner radius (full handle size), generous centre.
     *
     * Scaling keeps corners reachable on small boxes (e.g. the world-view default)
     * while preserving a translate-only band in the centre.
     */
    private fun hitTest(x: Float, y: Float): DragMode {
        if (rect.isEmpty) return DragMode.NONE
        val smallerSide = minOf(rect.width(), rect.height())
        val effectiveRadius = minOf(handleHitRadius, smallerSide / 3f)
        // Below ~6dp the corner zones become finger-untargetable; fall back to
        // translate-only for genuinely tiny boxes.
        if (effectiveRadius < dp(6f)) {
            return if (rect.contains(x, y)) DragMode.TRANSLATE else DragMode.NONE
        }
        val tlDist = distance(x, y, rect.left, rect.top)
        val trDist = distance(x, y, rect.right, rect.top)
        val blDist = distance(x, y, rect.left, rect.bottom)
        val brDist = distance(x, y, rect.right, rect.bottom)
        val nearest = minOf(tlDist, trDist, blDist, brDist)
        if (nearest <= effectiveRadius) {
            return when (nearest) {
                tlDist -> DragMode.RESIZE_TL
                trDist -> DragMode.RESIZE_TR
                blDist -> DragMode.RESIZE_BL
                brDist -> DragMode.RESIZE_BR
                else -> DragMode.NONE
            }
        }
        if (rect.contains(x, y)) {
            return DragMode.TRANSLATE
        }
        return DragMode.NONE
    }

    /**
     * Apply a translate drag. Project the previous and current touch points
     * to lat/lon, compute the delta, and shift all four corners of the geo
     * bbox by that delta. Preserves the box's lat/lon dimensions exactly
     * (until clamped at the Web-Mercator pole limit).
     */
    private fun handleTranslateMove(pxX: Float, pxY: Float) {
        val current = geoBbox ?: return
        val unproject = toLatLon ?: return
        val last = lastTouchPx ?: return
        val (lastLat, lastLon) = unproject(
            last.x.coerceIn(0f, (width - 1).toFloat()),
            last.y.coerceIn(0f, (height - 1).toFloat()),
        )
        val (newLat, newLon) = unproject(
            pxX.coerceIn(0f, (width - 1).toFloat()),
            pxY.coerceIn(0f, (height - 1).toFloat()),
        )
        if (!lastLat.isFinite() || !lastLon.isFinite() ||
            !newLat.isFinite() || !newLon.isFinite()
        ) return
        val dLat = newLat - lastLat
        val dLon = newLon - lastLon
        // Clamp at Web-Mercator's valid range. If a shift would push past the
        // pole, snap back so the box shrinks rather than wraps — gentler UX
        // than a sudden disappearance.
        val newSouth = (current.south + dLat).coerceIn(-85.0511, 85.0511)
        val newNorth = (current.north + dLat).coerceIn(-85.0511, 85.0511)
        if (newNorth - newSouth < minSideDeg) return
        val updated = runCatching {
            Bbox(
                south = newSouth,
                west = current.west + dLon,
                north = newNorth,
                east = current.east + dLon,
            )
        }.getOrNull() ?: return
        geoBbox = updated
        lastTouchPx = PointF(pxX, pxY)
        recomputePixelRect()
    }

    /**
     * Apply a corner drag. Projects the new pixel position back to lat/lon,
     * mutates the dragged corner of [geoBbox], and recomputes the pixel rect.
     * Keeps the box axis-aligned in geographic space.
     */
    private fun handleDragMove(pxX: Float, pxY: Float) {
        val current = geoBbox ?: return
        val unproject = toLatLon ?: return
        val (newLat, newLon) = unproject(
            pxX.coerceIn(0f, (width - 1).toFloat()),
            pxY.coerceIn(0f, (height - 1).toFloat()),
        )
        if (!newLat.isFinite() || !newLon.isFinite()) return
        // Clamp lat to Web-Mercator's valid range.
        val clampedLat = newLat.coerceIn(-85.0511, 85.0511)
        val updated = when (dragMode) {
            DragMode.RESIZE_TL -> {
                // Top-left = (north, west). Update lat→new north, lon→new west.
                val newNorth = clampedLat.coerceAtLeast(current.south + minSideDeg)
                val newWest = newLon.coerceAtMost(current.east - minSideDeg)
                runCatching {
                    Bbox(south = current.south, west = newWest, north = newNorth, east = current.east)
                }.getOrNull()
            }
            DragMode.RESIZE_TR -> {
                // Top-right = (north, east).
                val newNorth = clampedLat.coerceAtLeast(current.south + minSideDeg)
                val newEast = newLon.coerceAtLeast(current.west + minSideDeg)
                runCatching {
                    Bbox(south = current.south, west = current.west, north = newNorth, east = newEast)
                }.getOrNull()
            }
            DragMode.RESIZE_BL -> {
                // Bottom-left = (south, west).
                val newSouth = clampedLat.coerceAtMost(current.north - minSideDeg)
                val newWest = newLon.coerceAtMost(current.east - minSideDeg)
                runCatching {
                    Bbox(south = newSouth, west = newWest, north = current.north, east = current.east)
                }.getOrNull()
            }
            DragMode.RESIZE_BR -> {
                // Bottom-right = (south, east).
                val newSouth = clampedLat.coerceAtMost(current.north - minSideDeg)
                val newEast = newLon.coerceAtLeast(current.west + minSideDeg)
                runCatching {
                    Bbox(south = newSouth, west = current.west, north = current.north, east = newEast)
                }.getOrNull()
            }
            DragMode.NONE, DragMode.TRANSLATE -> null
        } ?: return
        geoBbox = updated
        recomputePixelRect()
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float =
        sqrt((x1 - x2).pow(2) + (y1 - y2).pow(2))

    private enum class DragMode { NONE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR, TRANSLATE }
}

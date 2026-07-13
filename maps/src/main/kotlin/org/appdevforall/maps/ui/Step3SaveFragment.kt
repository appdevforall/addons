package org.appdevforall.maps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.domain.RegionId
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import org.appdevforall.maps.domain.TileEstimate
import org.appdevforall.maps.data.RegionCache
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wizard step 3 — Save Region.
 *
 * Collects a region name and shows a summary card (source + total download size).
 * Back returns to Step 2 with the bbox preserved; Save delegates to the host
 * fragment, which starts the download.
 */
class Step3SaveFragment : Fragment() {

    interface Listener {
        fun onSaveRegionConfirmed(
            displayName: String,
            regionId: String,
        )

        fun onSaveRegionBack()
    }

    companion object {
        const val ARG_SOURCE_KIND = "sourceKindWire"
        const val ARG_SOURCE_HOST = "sourceHost"
        const val ARG_BBOX = "bbox"
        const val ARG_TILE_COUNT = "tileCount"
        const val ARG_SIZE_BYTES = "sizeBytesEstimate"
        const val ARG_ZOOM_MIN = "zoomMin"
        const val ARG_ZOOM_MAX = "zoomMax"
        const val ARG_PREFILL_REGION_ID = "prefillRegionId"
        const val ARG_PREFILL_DISPLAY_NAME = "prefillDisplayName"

        /**
         * @param estimate slicer-derived size estimate, or null if the slicer
         *                 hasn't returned yet. Step 3 shows "Calculating
         *                 download size…" until a real estimate arrives.
         */
        fun newInstance(
            sourceKind: SourceKind,
            sourceHost: String?,
            bbox: Bbox,
            estimate: TileEstimate?,
            prefillRegionId: String? = null,
            prefillDisplayName: String? = null,
        ): Step3SaveFragment = Step3SaveFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SOURCE_KIND, sourceKind.wireValue)
                if (sourceHost != null) putString(ARG_SOURCE_HOST, sourceHost)
                putDoubleArray(ARG_BBOX, bbox.toBoundsArray())
                // -1 sentinel encodes "not yet estimated"; Step 3 reads this
                // as the "calculating" state.
                putLong(ARG_SIZE_BYTES, estimate?.sizeBytesEstimate ?: -1L)
                putLong(ARG_TILE_COUNT, estimate?.tileCount ?: 0L)
                putInt(ARG_ZOOM_MIN, estimate?.zoomMin ?: 6)
                putInt(ARG_ZOOM_MAX, estimate?.zoomMax ?: 14)
                if (prefillRegionId != null) putString(ARG_PREFILL_REGION_ID, prefillRegionId)
                if (prefillDisplayName != null) putString(ARG_PREFILL_DISPLAY_NAME, prefillDisplayName)
            }
        }
    }

    private lateinit var edtRegionName: TextInputEditText
    private lateinit var collisionWarning: TextView
    private lateinit var btnBack: MaterialButton
    private lateinit var btnSave: MaterialButton

    // Cached args.
    private var sourceKind: SourceKind = SourceKind.UNKNOWN
    private var sourceHost: String? = null
    private lateinit var bbox: Bbox
    private var sizeBytesEstimate: Long = 0L
    private var prefillRegionId: String? = null

    // Live state.
    private var regionName: String = ""

    /**
     * Cache of existing cache regionIds, loaded once off the main thread in
     * [onViewCreated] (and refreshed on resume). [refreshCollisionWarning] reads
     * this set synchronously rather than walking `/sdcard/CodeOnTheGo/maps/` on
     * every keystroke — the latter trips StrictMode's disk-read policy and janks
     * past ~10 cached regions. Null = not yet loaded (treat as "no known
     * collisions" until the load lands).
     */
    @Volatile
    private var existingRegionIds: Set<String>? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater =
        themedPluginInflater(super.onGetLayoutInflater(savedInstanceState))

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_save_region, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val args = arguments
        sourceKind = SourceKind.values().firstOrNull {
            it.wireValue == args?.getString(ARG_SOURCE_KIND)
        } ?: SourceKind.UNKNOWN
        sourceHost = args?.getString(ARG_SOURCE_HOST)
        val bboxArr = args?.getDoubleArray(ARG_BBOX)
            ?: doubleArrayOf(0.0, 0.0, 0.0, 0.0)
        bbox = runCatching {
            Bbox(bboxArr[0], bboxArr[1], bboxArr[2], bboxArr[3])
        }.getOrDefault(Bbox(0.0, 0.0, 0.01, 0.01))
        // -1 = slicer hadn't returned when the user tapped Next (the bbox
        // picker forwards null in that case). The total-size row renders
        // "Calculating download size…" until the user goes back or proceeds.
        sizeBytesEstimate = args?.getLong(ARG_SIZE_BYTES, -1L) ?: -1L
        prefillRegionId = args?.getString(ARG_PREFILL_REGION_ID)
        val prefillName = args?.getString(ARG_PREFILL_DISPLAY_NAME)

        edtRegionName = view.findViewById(R.id.edt_region_name)
        collisionWarning = view.findViewById(R.id.collision_warning)
        btnBack = view.findViewById(R.id.btn_back)
        btnSave = view.findViewById(R.id.btn_save)

        // Default name = prefill (Refresh flow) OR blank — the user types a place
        // name. Save stays disabled until the field is non-blank.
        val defaultName = prefillName.orEmpty()
        edtRegionName.setText(defaultName)
        regionName = defaultName

        edtRegionName.doAfterTextChanged {
            regionName = it?.toString().orEmpty().trim()
            refreshCollisionWarning()
            refreshSaveEnabled()
        }

        renderSummaryCard(view)
        refreshCollisionWarning()
        refreshSaveEnabled()

        btnBack.setOnClickListener {
            (parentFragment as? Listener)?.onSaveRegionBack()
                ?: defaultPopBack()
        }
        btnSave.setOnClickListener { save() }

        loadExistingRegionIds()
    }

    override fun onResume() {
        super.onResume()
        // The user may have navigated to the Region Manager and added/removed a
        // region; re-load the collision set so the warning stays accurate.
        loadExistingRegionIds()
    }

    /**
     * Populate [existingRegionIds] off the main thread, then re-evaluate the
     * collision warning on the main thread. Walking the cache is disk I/O and
     * must not run on a `doAfterTextChanged` keystroke callback.
     */
    private fun loadExistingRegionIds() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ids = withContext(Dispatchers.IO) {
                runCatching { RegionCache.list().map { it.regionId }.toSet() }
                    .getOrDefault(emptySet())
            }
            existingRegionIds = ids
            refreshCollisionWarning()
            refreshSaveEnabled()
        }
    }

    // ----- Summary card rendering -----

    private fun renderSummaryCard(root: View) {
        fun setRow(rowId: Int, k: String, v: String) {
            val row = root.findViewById<View>(rowId)
            row.findViewById<TextView>(R.id.k).text = k
            row.findViewById<TextView>(R.id.v).text = v
        }
        val sourceLabel = when (sourceKind) {
            SourceKind.IIAB_LAN -> "📡 ${sourceHost ?: "LAN"} (LAN)"
            SourceKind.INTERNET -> "🌐 iiab.switnet.org"
            else -> "Unknown"
        }
        setRow(R.id.sum_source, getString(R.string.maps_save_summary_source), sourceLabel)

        // Total size estimate: the slicer's vector tile byte sum drives most of
        // it, plus ~7 MB Natural Earth basemap.
        // sizeBytesEstimate < 0 means the slicer hadn't returned when the user
        // tapped Next; show "Calculating…" rather than a misleading basemap-only
        // total.
        val totalText = if (sizeBytesEstimate < 0) {
            "Calculating download size…"
        } else {
            val vectorMb = (sizeBytesEstimate / (1024.0 * 1024.0)).coerceAtLeast(0.0)
            val neMb = 7.0
            val totalMb = vectorMb + neMb
            "%.1f MB".format(totalMb)
        }
        setRow(
            R.id.sum_total,
            getString(R.string.maps_save_summary_total),
            totalText,
        )
    }

    // ----- Collision check -----

    private fun refreshCollisionWarning() {
        val rid = slugifyRegionId(regionName)
        if (rid.isBlank() || regionName.isBlank()) {
            collisionWarning.visibility = View.GONE
            return
        }
        // Read the pre-loaded set (populated off-thread); null means the load
        // hasn't landed yet, so don't warn — the load callback re-runs this.
        val existing = existingRegionIds ?: return
        val collides = rid != prefillRegionId && existing.contains(rid)
        if (collides) {
            collisionWarning.text =
                getString(R.string.maps_save_collision_message, regionName)
            collisionWarning.visibility = View.VISIBLE
        } else {
            collisionWarning.visibility = View.GONE
        }
    }

    private fun refreshSaveEnabled() {
        // Save needs a non-blank, valid id that doesn't collide with an existing
        // region — a collision would silently overwrite it. Re-downloading the same
        // region (rid == prefillRegionId) is allowed. existingRegionIds may be null
        // until the off-thread load lands; the load callback re-runs this.
        val rid = if (regionName.isBlank()) "" else slugifyRegionId(regionName)
        val collides = rid.isNotBlank() && rid != prefillRegionId &&
            existingRegionIds?.contains(rid) == true
        btnSave.isEnabled = rid.isNotBlank() && RegionCache.isValidRegionId(rid) && !collides
    }

    // ----- Save -----

    private fun save() {
        if (regionName.isBlank()) return
        val rid = prefillRegionId ?: slugifyRegionId(regionName)
        if (rid.isBlank() || !RegionCache.isValidRegionId(rid)) return
        (parentFragment as? Listener)?.onSaveRegionConfirmed(
            displayName = regionName,
            regionId = rid,
        ) ?: defaultPopBack()
    }

    private fun defaultPopBack() {
        if (parentFragmentManager.backStackEntryCount > 0) {
            parentFragmentManager.popBackStack()
        }
    }

    /** Slugify the user-typed name into a cache-safe regionId; see [RegionId.slugify]. */
    private fun slugifyRegionId(name: String): String = RegionId.slugify(name)
}

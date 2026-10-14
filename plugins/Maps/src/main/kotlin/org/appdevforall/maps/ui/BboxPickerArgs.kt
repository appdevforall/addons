package org.appdevforall.maps.ui

import android.os.Bundle
import org.appdevforall.maps.domain.SourceKind

/**
 * The bbox-picker's launch contract in one place.
 *
 * Previously the Fragment-argument keys, their defaults, the write (in `newInstance`) and the
 * read (in `onViewCreated`) were spread across three spots — a classic source of silent
 * "forgot to read a key" bugs. This type owns the keys + the round-trip ([toBundle] / [fromBundle]),
 * so the contract is defined once.
 */
data class BboxPickerArgs(
    val prefillRegionId: String?,
    val prefillDisplayName: String?,
    val prefillBbox: DoubleArray?,
    val sourceKind: SourceKind,
    val sourceHost: String?,
    val zoomMin: Int,
    val zoomMax: Int,
) {

    fun toBundle(): Bundle = Bundle().apply {
        if (prefillRegionId != null) putString(ARG_PREFILL_REGION_ID, prefillRegionId)
        if (prefillDisplayName != null) putString(ARG_PREFILL_DISPLAY_NAME, prefillDisplayName)
        if (prefillBbox != null) putDoubleArray(ARG_PREFILL_BBOX, prefillBbox)
        putString(ARG_SOURCE_KIND, sourceKind.wireValue)
        if (sourceHost != null) putString(ARG_SOURCE_HOST, sourceHost)
        putInt(ARG_ZOOM_MIN, zoomMin)
        putInt(ARG_ZOOM_MAX, zoomMax)
    }

    companion object {
        /** Default zoom range (matches RegionDownloader defaults). */
        const val DEFAULT_ZOOM_MIN = 6
        const val DEFAULT_ZOOM_MAX = 14

        private const val ARG_PREFILL_REGION_ID = "prefillRegionId"
        private const val ARG_PREFILL_DISPLAY_NAME = "prefillDisplayName"
        private const val ARG_PREFILL_BBOX = "prefillBbox"
        private const val ARG_SOURCE_KIND = "sourceKindWire"
        private const val ARG_SOURCE_HOST = "sourceHost"
        private const val ARG_ZOOM_MIN = "zoomMin"
        private const val ARG_ZOOM_MAX = "zoomMax"

        fun fromBundle(args: Bundle?): BboxPickerArgs = BboxPickerArgs(
            prefillRegionId = args?.getString(ARG_PREFILL_REGION_ID),
            prefillDisplayName = args?.getString(ARG_PREFILL_DISPLAY_NAME),
            prefillBbox = args?.getDoubleArray(ARG_PREFILL_BBOX),
            sourceKind = SourceKind.values().firstOrNull {
                it.wireValue == args?.getString(ARG_SOURCE_KIND)
            } ?: SourceKind.UNKNOWN,
            sourceHost = args?.getString(ARG_SOURCE_HOST),
            zoomMin = args?.getInt(ARG_ZOOM_MIN, DEFAULT_ZOOM_MIN) ?: DEFAULT_ZOOM_MIN,
            zoomMax = args?.getInt(ARG_ZOOM_MAX, DEFAULT_ZOOM_MAX) ?: DEFAULT_ZOOM_MAX,
        )
    }

    // DoubleArray makes the generated equals/hashCode reference-based; this is a transient
    // launch holder that's never compared, so structural equality isn't needed.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

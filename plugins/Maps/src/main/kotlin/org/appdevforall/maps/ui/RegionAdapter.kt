package org.appdevforall.maps.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.appdevforall.maps.R
import org.appdevforall.maps.data.RegionInfo
import org.appdevforall.maps.util.formatByteSize
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.text.DateFormat
import java.util.Date

/**
 * Diffing list adapter for [RegionRow].
 *
 * Each row exposes:
 *  - Active/inactive toggle (per-project active state; only one region active in
 *    a given project at a time)
 *  - Refresh (re-download)
 *  - Delete
 *
 * Active rows get a green border + "● Active in this project" badge. The fragment
 * owns all action handling (including the delete-confirmation dialog), so the
 * adapter stays view-only.
 */
class RegionAdapter(
    private val listener: Listener? = null
) : RecyclerView.Adapter<RegionAdapter.VH>() {

    interface Listener {
        /**
         * User flipped the active toggle. The fragment writes the per-project
         * active.txt + (when activating) copies the region's files into the
         * project, then refreshes the list.
         */
        fun onRegionToggleActive(info: RegionInfo, newActive: Boolean)

        fun onRegionDelete(info: RegionInfo)
        fun onRegionRedownload(info: RegionInfo)
    }

    private val items = mutableListOf<RegionRow>()

    fun submit(newItems: List<RegionRow>) {
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = items.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos].info.regionId == newItems[newPos].info.regionId
            override fun areContentsTheSame(oldPos: Int, newPos: Int) =
                items[oldPos] == newItems[newPos]
        })
        items.clear()
        items.addAll(newItems)
        diff.dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_region, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) = holder.bind(items[position])
    override fun getItemCount(): Int = items.size

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        private val rowRoot = view.findViewById<View>(R.id.row_root)
        private val name = view.findViewById<TextView>(R.id.region_name)
        private val size = view.findViewById<TextView>(R.id.region_size)
        private val meta = view.findViewById<TextView>(R.id.region_meta)
        private val activeBadge = view.findViewById<TextView>(R.id.active_badge)
        private val sourceBadge = view.findViewById<TextView>(R.id.source_badge)
        private val progress = view.findViewById<CircularProgressIndicator>(R.id.region_progress)
        private val swActive = view.findViewById<MaterialSwitch>(R.id.sw_active)
        private val redownload = view.findViewById<MaterialButton>(R.id.btn_redownload)
        private val delete = view.findViewById<MaterialButton>(R.id.btn_delete)

        fun bind(row: RegionRow) {
            val info = row.info
            name.text = info.displayName
            size.text = formatByteSize(info.sizeBytes)
            meta.text = formatMeta(info)

            // Active background + badge.
            rowRoot.setBackgroundResource(
                if (row.isActiveInProject) R.drawable.region_row_bg_active
                else R.drawable.region_row_bg
            )
            activeBadge.visibility = if (row.isActiveInProject) View.VISIBLE else View.GONE

            // Source badge hidden — the source isn't surfaced to the user. The
            // View stays in the layout in case LAN selection returns as a
            // power-user toggle.
            sourceBadge.visibility = View.GONE

            // Toggle — reflect current state without firing the listener.
            swActive.setOnCheckedChangeListener(null)
            swActive.isChecked = row.isActiveInProject
            // Suppress the listener during isDownloading to avoid racing with
            // the in-flight IO. The toggle stays interactive otherwise.
            val controlsEnabled = listener != null && !row.isDownloading
            swActive.isEnabled = controlsEnabled
            redownload.isEnabled = controlsEnabled
            delete.isEnabled = controlsEnabled
            swActive.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked == row.isActiveInProject) return@setOnCheckedChangeListener
                // Optimistic UI — flip the badge + row background the moment the
                // toggle moves, rather than waiting on the fragment's IO + refresh
                // round-trip (~200ms+).
                activeBadge.visibility = if (isChecked) View.VISIBLE else View.GONE
                rowRoot.setBackgroundResource(
                    if (isChecked) R.drawable.region_row_bg_active
                    else R.drawable.region_row_bg
                )
                listener?.onRegionToggleActive(info, isChecked)
            }

            progress.visibility = if (row.isDownloading) View.VISIBLE else View.GONE

            redownload.setOnClickListener { listener?.onRegionRedownload(info) }
            delete.setOnClickListener { listener?.onRegionDelete(info) }
        }
    }

    /** Meta line: "downloaded <date>" or "stub placeholder". (Size is already
     *  shown top-right in `region_size`.) */
    private fun formatMeta(info: RegionInfo): String {
        val isStubSize = info.sizeBytes < STUB_SIZE_THRESHOLD_BYTES
        val sourceStub = info.source == "openmaptiles-stub"
        if (isStubSize || sourceStub) return "stub placeholder"
        val downloaded = info.downloadedAt?.let { DateFormat.getDateInstance().format(Date(it)) }
        return if (downloaded != null) "downloaded $downloaded" else ""
    }

    private companion object {
        const val STUB_SIZE_THRESHOLD_BYTES = 100L * 1024L
    }
}

/**
 * View-model row wrapping a [RegionInfo] with two transient flags the fragment
 * tracks per render: whether this region is the project's active region (a region
 * is bundled in the project only if it is the active one) and whether a fresh
 * download is running for it.
 */
data class RegionRow(
    val info: RegionInfo,
    val isActiveInProject: Boolean = false,
    val isDownloading: Boolean = false
)

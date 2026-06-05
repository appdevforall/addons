package org.appdevforall.maps.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import org.appdevforall.maps.MapsPlugin
import org.appdevforall.maps.R
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.domain.Bbox
import org.appdevforall.maps.domain.SourceKind
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.itsaky.androidide.plugins.base.PluginFragmentHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Surfaces real download progress after Step 3 Save.
 *
 * Drives [RegionDownloader.download] on a viewLifecycleOwner-scoped coroutine,
 * reflects per-phase progress in the basemap / tiles cards, and emits
 * one of three terminal callbacks:
 *  - [Listener.onDownloadComplete] — success
 *  - [Listener.onDownloadCancelled] — user tapped Cancel
 *  - [Listener.onDownloadFailed] — IO / HTTP error
 *
 * Cancel deletes the partial directory (handled inside [RegionDownloader]'s
 * catch + finally), so a discarded download leaves no orphan bytes.
 */
class DownloadProgressFragment : Fragment() {

    interface Listener {
        fun onDownloadComplete(regionId: String)
        fun onDownloadCancelled()
        fun onDownloadFailed(message: String)
    }

    companion object {
        const val ARG_REGION_ID = "regionId"
        const val ARG_DISPLAY_NAME = "displayName"
        const val ARG_BBOX = "bbox"
        const val ARG_SOURCE_KIND = "sourceKind"
        const val ARG_SOURCE_HOST = "sourceHost"
        // Zoom range the picker selected (its pickZoomMax auto-caps zoomMax). Must
        // be threaded to RegionDownloader.download, or the downloader's z=6..14
        // default pulls 4×–16× more tiles than the user agreed to.
        const val ARG_ZOOM_MIN = "zoomMin"
        const val ARG_ZOOM_MAX = "zoomMax"

        fun newInstance(
            regionId: String,
            displayName: String,
            bbox: Bbox,
            sourceKind: SourceKind,
            sourceHost: String?,
            zoomMin: Int,
            zoomMax: Int,
        ): DownloadProgressFragment = DownloadProgressFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_REGION_ID, regionId)
                putString(ARG_DISPLAY_NAME, displayName)
                putDoubleArray(ARG_BBOX, bbox.toBoundsArray())
                putString(ARG_SOURCE_KIND, sourceKind.wireValue)
                if (sourceHost != null) putString(ARG_SOURCE_HOST, sourceHost)
                putInt(ARG_ZOOM_MIN, zoomMin)
                putInt(ARG_ZOOM_MAX, zoomMax)
            }
        }
    }

    private lateinit var regionNameLabel: TextView
    private lateinit var headerLabel: TextView
    private lateinit var statusBasemap: TextView
    private lateinit var statusTiles: TextView
    private lateinit var progressBasemap: LinearProgressIndicator
    private lateinit var progressTiles: LinearProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private var downloadJob: Job? = null

    override fun onGetLayoutInflater(savedInstanceState: Bundle?): LayoutInflater {
        val inflater = super.onGetLayoutInflater(savedInstanceState)
        return PluginFragmentHelper.getPluginInflater(MapsPlugin.PLUGIN_ID, inflater)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_download_progress, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        regionNameLabel = view.findViewById(R.id.region_name)
        headerLabel = view.findViewById(R.id.header)
        statusBasemap = view.findViewById(R.id.status_basemap)
        statusTiles = view.findViewById(R.id.status_tiles)
        progressBasemap = view.findViewById(R.id.progress_basemap)
        progressTiles = view.findViewById(R.id.progress_tiles)
        btnCancel = view.findViewById(R.id.btn_cancel)

        val args = arguments
        val regionId = args?.getString(ARG_REGION_ID) ?: run {
            (parentFragment as? Listener)?.onDownloadFailed("Missing regionId")
            return
        }
        val displayName = args.getString(ARG_DISPLAY_NAME) ?: regionId
        val bboxArr = args.getDoubleArray(ARG_BBOX) ?: doubleArrayOf(0.0, 0.0, 0.01, 0.01)
        val bbox = runCatching {
            Bbox(bboxArr[0], bboxArr[1], bboxArr[2], bboxArr[3])
        }.getOrElse {
            (parentFragment as? Listener)?.onDownloadFailed("Bad bbox: ${it.message}")
            return
        }
        val sourceKind = SourceKind.values().firstOrNull {
            it.wireValue == args.getString(ARG_SOURCE_KIND)
        } ?: SourceKind.INTERNET
        val sourceHost = args.getString(ARG_SOURCE_HOST)
        val zoomMin = args.getInt(ARG_ZOOM_MIN, 6)
        val zoomMax = args.getInt(ARG_ZOOM_MAX, 14)

        regionNameLabel.text = displayName
        val sourceLabel = when (sourceKind) {
            SourceKind.IIAB_LAN -> sourceHost ?: "LAN"
            SourceKind.INTERNET -> "iiab.switnet.org"
            else -> "unknown"
        }
        headerLabel.text = "From $sourceLabel"

        statusBasemap.text = getString(R.string.maps_download_status_queued)
        statusTiles.text = getString(R.string.maps_download_status_queued)

        btnCancel.setOnClickListener {
            downloadJob?.cancel()
            // Listener fires from the catch block below.
        }

        downloadJob = viewLifecycleOwner.lifecycleScope.launch {
            val ctx = requireContext().applicationContext
            try {
                RegionDownloader.download(
                    context = ctx,
                    regionId = regionId,
                    displayName = displayName,
                    bbox = bbox,
                    sourceKind = sourceKind,
                    sourceHost = sourceHost,
                    zoomMin = zoomMin,
                    zoomMax = zoomMax,
                ) { phase, bytes, total ->
                    val rootView = view ?: return@download
                    val (status, progress) = when (phase) {
                        RegionDownloader.Phase.BASEMAP -> statusBasemap to progressBasemap
                        RegionDownloader.Phase.TILES -> statusTiles to progressTiles
                    }
                    rootView.post {
                        if (total > 0) {
                            val pct = ((bytes.toDouble() / total.toDouble()) * 100).toInt()
                                .coerceIn(0, 100)
                            progress.progress = pct
                            status.text = "${humanBytes(bytes)} / ${humanBytes(total)} · $pct%"
                        } else {
                            progress.isIndeterminate = true
                            status.text = "${humanBytes(bytes)} downloaded"
                        }
                        if (bytes == total && total > 0) {
                            status.text =
                                "${humanBytes(total)} · ${getString(R.string.maps_download_status_done)}"
                        }
                    }
                }
                (parentFragment as? Listener)?.onDownloadComplete(regionId)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                (parentFragment as? Listener)?.onDownloadCancelled()
                // Re-throw to allow structured cancellation to propagate.
                throw cancel
            } catch (t: Throwable) {
                val msg = t.message ?: t.javaClass.simpleName
                (parentFragment as? Listener)?.onDownloadFailed(msg)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Cancelling the job triggers RegionDownloader's finally to delete the
        // partial dir, so a discarded download leaves no orphan bytes.
        downloadJob?.cancel()
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 0) return "—"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb < 1) "${bytes / 1024} KB" else "%.1f MB".format(mb)
    }
}

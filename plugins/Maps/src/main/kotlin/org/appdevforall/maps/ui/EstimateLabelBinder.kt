package org.appdevforall.maps.ui

import android.view.View
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import org.appdevforall.maps.R
import org.appdevforall.maps.data.RegionDownloader
import org.appdevforall.maps.data.RegionSizeEstimator
import org.appdevforall.maps.domain.EstimateDisplay
import org.appdevforall.maps.domain.ZoomLabel

/**
 * Renders the bbox picker's size and dimensions lines and the Next button from [selectionModel],
 * and runs the debounced size estimate that feeds them.
 *
 * @param scope the picker's view-lifecycle scope, so the estimate dies with the view
 * @param isViewAlive false once the picker's view is gone, so a late estimate renders nothing
 */
internal class EstimateLabelBinder(
    private val selectionModel: BboxSelectionModel,
    private val sizeLine: TextView,
    private val dimsLine: TextView,
    private val overlay: BboxOverlayView,
    private val nextButton: View,
    scope: CoroutineScope,
    private val isViewAlive: () -> Boolean,
) {

    private var sizeEstimator: RegionSizeEstimator? =
        RegionSizeEstimator(
            scope = scope,
            httpClient = RegionDownloader.httpClient,
            onState = ::onEstimateState,
        )

    /** Re-renders both lines for a changed bbox and restarts the estimate. */
    fun onBboxChanged() {
        renderEstimate()
        renderDimensions()
        scheduleRealEstimate()
    }

    /** Stops the estimate; call from `onDestroyView`. */
    fun cancel() {
        sizeEstimator?.cancel()
        sizeEstimator = null
    }

    /**
     * The size line, its colour, the overlay's over-budget tint and Next's enabled state. The
     * decisions (1 GB cap, calculating / failed / done) are [EstimateDisplay]'s.
     */
    private fun renderEstimate() {
        val state =
            EstimateDisplay.compute(
                realBytes = selectionModel.realByteEstimate,
                estimateFailed = selectionModel.realEstimateFailed,
                hasSliceableSource = selectionModel.hasSliceableSource(),
                noTiles = selectionModel.realEstimateHasNoTiles,
            )
        sizeLine.text = state.text
        // The view's own context is the themed plugin one: the host context cannot resolve plugin
        // colour ids, and only the plugin context picks up the values-night variants.
        sizeLine.setTextColor(
            sizeLine.context.getColor(
                if (state.isError) R.color.plugin_error else R.color.plugin_on_surface_variant
            )
        )
        overlay.setOverBudget(state.overBudget)
        nextButton.isEnabled = state.nextEnabled
    }

    /**
     * The "2220km × 2140km — max zoom level 12 (town)" line. Zoom names follow the OpenStreetMap
     * wiki's "Zoom levels" page, with Mapbox / Bing names where it has none.
     */
    fun renderDimensions() {
        val width = selectionModel.currentBbox.widthKm().toInt()
        val height = selectionModel.currentBbox.heightKm().toInt()
        val label = ZoomLabel.forZoom(selectionModel.zoomMax)
        dimsLine.text = "${width}km × ${height}km — max zoom level ${selectionModel.zoomMax} ($label)"
        dimsLine.setTextColor(dimsLine.context.getColor(R.color.plugin_on_surface_variant))
    }

    private fun scheduleRealEstimate() {
        if (!selectionModel.hasSliceableSource()) return
        sizeEstimator?.estimate(
            tilesUrl =
                RegionDownloader.buildTilesUrl(selectionModel.sourceKind, selectionModel.sourceHost),
            bbox = selectionModel.currentBbox,
            zoomMin = selectionModel.zoomMin,
            zoomMax = selectionModel.zoomMax,
        )
    }

    private fun onEstimateState(state: RegionSizeEstimator.State) {
        selectionModel.onEstimateState(state)
        if (isViewAlive()) renderEstimate()
    }
}

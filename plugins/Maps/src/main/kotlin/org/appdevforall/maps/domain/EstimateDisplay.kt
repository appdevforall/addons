package org.appdevforall.maps.domain

/**
 * Pure decision logic for the bbox-picker's "download size" line: given the current estimate
 * state, what text to show, whether it's an error, whether the region is over budget, and whether
 * the **Next** button should be enabled.
 *
 * Extracted from `BboxPickerFragment.renderEstimate` so the rules (the 1 GB cap, the non-vector
 * allowance, the calculating/failed/done state machine) are testable on the JVM — the Fragment is
 * left to map a [State] onto its views.
 */
object EstimateDisplay {

    /**
     * Hard cap on total download size (1 GiB). Applied to the slicer-derived vector estimate plus
     * [DEFAULT_NON_VECTOR_ALLOWANCE_BYTES]. Step 2 disables Next over the cap; Step 3 mirrors it.
     */
    const val DEFAULT_MAX_DOWNLOAD_BYTES: Long = 1L * 1024L * 1024L * 1024L // 1 GiB

    /**
     * Headroom beyond the slicer's vector estimate for per-tile overhead in the sliced archive.
     * (The Natural Earth basemap is copied from the bundle, not downloaded, so it doesn't count.)
     */
    const val DEFAULT_NON_VECTOR_ALLOWANCE_BYTES: Long = 4L * 1024L * 1024L

    /**
     * @param text the line to display (empty before a source is picked).
     * @param isError true when [text] should use the error color.
     * @param overBudget true when the region exceeds [maxDownloadBytes] (the overlay tints red).
     * @param nextEnabled whether the wizard's Next button should be enabled.
     */
    data class State(
        val text: String,
        val isError: Boolean,
        val overBudget: Boolean,
        val nextEnabled: Boolean,
    )

    /**
     * Compute the display state.
     *
     * @param realBytes the slicer's measured vector-tile byte total, or null while it's still
     *   running / has failed.
     * @param estimateFailed true when the last slicer attempt failed (only meaningful when
     *   [realBytes] is null).
     * @param hasSliceableSource whether a source is chosen yet — when false, nothing is shown.
     * @param nonVectorAllowanceBytes headroom added for the basemap + metadata when checking the cap.
     * @param maxDownloadBytes the hard download cap (1 GB in production).
     */
    fun compute(
        realBytes: Long?,
        estimateFailed: Boolean,
        hasSliceableSource: Boolean,
        nonVectorAllowanceBytes: Long = DEFAULT_NON_VECTOR_ALLOWANCE_BYTES,
        maxDownloadBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    ): State {
        if (!hasSliceableSource) {
            // No source picked yet — blank line, Next disabled.
            return State(text = "", isError = false, overBudget = false, nextEnabled = false)
        }
        if (realBytes != null) {
            val totalBytes = realBytes + nonVectorAllowanceBytes
            val mb = realBytes / (1024.0 * 1024.0)
            return if (totalBytes > maxDownloadBytes) {
                val totalMb = totalBytes / (1024.0 * 1024.0)
                State(
                    text = "%.0f MB · over 1 GB limit. Choose a smaller region".format(totalMb),
                    isError = true,
                    overBudget = true,
                    nextEnabled = false,
                )
            } else {
                State(
                    text = "%.1f MB download size".format(mb),
                    isError = false,
                    overBudget = false,
                    nextEnabled = true,
                )
            }
        }
        // Slicer hasn't returned (yet, or failed). Show one of those two states — never a synthetic
        // number. Allow Next while calculating; disable it after a failure until we can re-estimate.
        return State(
            text = if (estimateFailed) "Couldn't calculate size — check connection"
            else "Calculating download size…",
            isError = estimateFailed,
            overBudget = false,
            nextEnabled = !estimateFailed,
        )
    }
}

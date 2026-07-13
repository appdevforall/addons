package org.appdevforall.maps.data

/**
 * Pure mapping from a [FirstRegionAutoActivator.Result] to the post-download
 * Snackbar's content, extracted from `RegionManagerFragment.onDownloadComplete`
 * — "formatting data for the view" is exactly the kind of logic a Fragment
 * shouldn't own, and as a pure function the four branches are trivially
 * JVM-testable.
 *
 * Lives in `data/` (not `domain/`) because it consumes the data-layer
 * [FirstRegionAutoActivator.Result]; `domain/` must never depend upward on
 * `data/`. No Android imports — the Fragment maps [Spec] onto a real Snackbar
 * (message, LENGTH_INDEFINITE + "Apply" action iff [Spec.showApplyAction]).
 */
internal object DownloadCompleteMessage {

    /**
     * What the post-download Snackbar should show.
     *
     * @param message the exact user-facing text
     * @param showApplyAction true when the Snackbar should offer a one-tap
     *   "Apply" action (and stay up indefinitely so the user can take it) —
     *   the "project already had an active region" case, where the download
     *   deliberately did NOT change the project and the action is the escape
     *   hatch for users who wanted it applied.
     */
    data class Spec(
        val message: String,
        val showApplyAction: Boolean = false,
    )

    /**
     * Map the auto-activation [result] for [regionId] onto the Snackbar spec.
     *
     * Branch behavior (mirrors [FirstRegionAutoActivator]'s decision table):
     *   - First region (no active.txt) → confirm apply + activation.
     *   - Subsequent region (active.txt already set) → active region untouched,
     *     but surface an "Apply" action for a one-tap switch.
     *   - Apply-failed / region-not-found → surface the reason so the user
     *     knows why nothing happened.
     */
    fun forResult(result: FirstRegionAutoActivator.Result, regionId: String): Spec = when (result) {
        is FirstRegionAutoActivator.Result.Activated -> Spec(
            message = "Region downloaded and applied to project: ${result.displayName}",
        )
        is FirstRegionAutoActivator.Result.NoOpAlreadyActive -> Spec(
            message = "Region downloaded: $regionId. Project's active region is unchanged.",
            showApplyAction = true,
        )
        is FirstRegionAutoActivator.Result.NoOpRegionNotFound -> Spec(
            message = "Region downloaded but couldn't be located in cache: $regionId",
        )
        is FirstRegionAutoActivator.Result.ApplyFailed -> Spec(
            message = "Region downloaded; apply failed: ${result.reason}",
        )
    }
}

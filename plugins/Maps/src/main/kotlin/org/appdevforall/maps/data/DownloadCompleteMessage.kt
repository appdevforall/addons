package org.appdevforall.maps.data

import androidx.annotation.StringRes
import org.appdevforall.maps.R

/**
 * Pure mapping from a [FirstRegionAutoActivator.Result] to the post-download
 * Snackbar's content, extracted from `RegionManagerFragment.onDownloadComplete`
 * — "formatting data for the view" is exactly the kind of logic a Fragment
 * shouldn't own, and as a pure function the four branches are trivially
 * JVM-testable.
 *
 * Lives in `data/` (not `domain/`) because it consumes the data-layer
 * [FirstRegionAutoActivator.Result]; `domain/` must never depend upward on
 * `data/`. It names the string resource and its argument; the Fragment renders the text and maps
 * [Spec] onto a real Snackbar (LENGTH_INDEFINITE + "Apply" action iff [Spec.showApplyAction]).
 */
internal object DownloadCompleteMessage {

    /**
     * What the post-download Snackbar should show.
     *
     * @param messageRes the string resource to show; it takes one `%1$s` argument
     * @param messageArg the argument for [messageRes]
     * @param showApplyAction true when the Snackbar should offer a one-tap
     *   "Apply" action (and stay up indefinitely so the user can take it) —
     *   the "project already had an active region" case, where the download
     *   deliberately did NOT change the project and the action is the escape
     *   hatch for users who wanted it applied.
     */
    data class Spec(
        @param:StringRes val messageRes: Int,
        val messageArg: String,
        val showApplyAction: Boolean = false,
    )

    /**
     * Map the auto-activation [result] for the region named [displayName] onto the Snackbar spec.
     *
     * Branch behavior (mirrors [FirstRegionAutoActivator]'s decision table):
     *   - First region (no active.txt) → confirm apply + activation.
     *   - Subsequent region (active.txt already set) → active region untouched,
     *     but surface an "Apply" action for a one-tap switch.
     *   - Apply-failed / region-not-found → surface the reason so the user
     *     knows why nothing happened.
     */
    fun forResult(result: FirstRegionAutoActivator.Result, displayName: String): Spec = when (result) {
        is FirstRegionAutoActivator.Result.Activated ->
            Spec(R.string.maps_download_done_applied, result.displayName)
        is FirstRegionAutoActivator.Result.NoOpAlreadyActive ->
            Spec(R.string.maps_download_done_unchanged, displayName, showApplyAction = true)
        is FirstRegionAutoActivator.Result.NoOpRegionNotFound ->
            Spec(R.string.maps_download_done_not_found, displayName)
        is FirstRegionAutoActivator.Result.ApplyFailed ->
            Spec(R.string.maps_download_done_apply_failed, result.reason)
    }
}

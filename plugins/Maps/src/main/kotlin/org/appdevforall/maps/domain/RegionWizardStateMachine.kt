package org.appdevforall.maps.domain

/**
 * The region wizard's step-sequencing state machine, extracted from
 * `RegionManagerFragment` so the *decisions* — which step comes next, what
 * state each step launches with, how Refresh prefill propagates, when wizard
 * state resets — are pure Kotlin and JVM-unit-testable.
 *
 * Split of responsibilities:
 *  - **This class** holds the wizard state (source, bbox, estimate, prefill)
 *    and answers "given this [Event], what [Step] should the user see next,
 *    launched with what data?".
 *  - **The Fragment** stays the executor: it turns each [Step] into the
 *    matching FragmentTransaction / DialogFragment show / container
 *    visibility flip, and routes its listener callbacks back in as events.
 *
 * Step sequencing (matches the pre-extraction Fragment exactly):
 *
 * ```mermaid
 * flowchart LR
 *     List -->|NewDownload / Redownload| Source[SourcePicker]
 *     Source -->|SourceConfirmed| Bbox[BboxPicker]
 *     Bbox -->|BboxPicked| Save[SaveStep]
 *     Save -->|SaveBackRequested| Bbox
 *     Save -->|SaveConfirmed| Download[DownloadProgress]
 *     Download -->|Done / Failed / Cancelled| List
 *     Source & Bbox -->|Exit| List
 * ```
 *
 * Note the bbox picker's Back is an **Exit** (to the list), not a step back to
 * the source picker — the source step is effectively bypassed today (Internet
 * default; `SourcePickerFragment` is kept for a possible LAN-select return),
 * so backing "past" it would strand the user on a step they never chose from.
 *
 * Every list-returning event ([Event.DownloadDone] / Failed / Cancelled /
 * [Event.Exit]) resets ALL wizard state, so a later wizard run can't inherit a
 * stale bbox or prefill from an abandoned one.
 */
class RegionWizardStateMachine {

    /** Everything that can happen to the wizard, routed in by the Fragment's listeners. */
    sealed interface Event {
        /** "Download new region" tapped — start a fresh wizard with no prefill. */
        object NewDownloadRequested : Event

        /**
         * Refresh/redownload of an existing region — start the wizard
         * pre-filled with the region's saved name + id + bounds so the user
         * doesn't have to re-draw the same area. [bbox] is the raw
         * `[south, west, north, east]` tuple from the region's metadata; a
         * malformed tuple (wrong size, inverted bounds) is dropped rather than
         * crashing the wizard open.
         */
        data class RedownloadRequested(
            val regionId: String,
            val displayName: String,
            val bbox: DoubleArray?,
        ) : Event

        /** Step 1 confirmed a tile source. */
        data class SourceConfirmed(
            val sourceKind: SourceKind,
            val sourceHost: String?,
        ) : Event

        /**
         * Step 2 picked a region. Refresh-flow ids/names propagate only when
         * non-null (they won't normally change at Step 2).
         */
        data class BboxPicked(
            val bbox: Bbox,
            val estimate: TileEstimate?,
            val prefillRegionId: String?,
            val prefillRegionName: String?,
        ) : Event

        /** Step 3's Back — re-open the bbox picker with the current selection. */
        object SaveBackRequested : Event

        /** Step 3 confirmed a name + id — start the download. */
        data class SaveConfirmed(
            val displayName: String,
            val regionId: String,
        ) : Event

        /** Download finished successfully. */
        object DownloadDone : Event

        /** Download failed (the Fragment owns showing the error message). */
        object DownloadFailed : Event

        /** Download cancelled by the user. */
        object DownloadCancelled : Event

        /** Any other way out of the wizard: close button, Android BACK, a step's Cancel/Back-to-list. */
        object Exit : Event
    }

    /** What the Fragment should render next, carrying the data that step launches with. */
    sealed interface Step {
        /** The region list (wizard closed, state reset). */
        object RegionList : Step

        data class SourcePicker(
            val prefillRegionId: String?,
            val prefillRegionName: String?,
        ) : Step

        data class BboxPicker(
            val prefillRegionId: String?,
            val prefillRegionName: String?,
            val prefillBbox: Bbox?,
            val sourceKind: SourceKind,
            val sourceHost: String?,
        ) : Step

        /**
         * Step 3. [bbox] is nullable to mirror the pre-extraction guard: the
         * Fragment renders the wizard chrome but skips the fragment swap when
         * no bbox exists (can't happen in a normal sequence).
         */
        data class SaveStep(
            val sourceKind: SourceKind,
            val sourceHost: String?,
            val bbox: Bbox?,
            val estimate: TileEstimate?,
            val prefillRegionId: String?,
            val prefillRegionName: String?,
        ) : Step

        data class DownloadProgress(
            val regionId: String,
            val displayName: String,
            val bbox: Bbox?,
            val sourceKind: SourceKind,
            val sourceHost: String?,
            val zoomMin: Int,
            val zoomMax: Int,
        ) : Step
    }

    // ----- Wizard state (held across step transitions) -----
    // Source selection isn't user-visible today: always download from the
    // Internet IIAB mirror. SourcePickerFragment is bypassed but kept for a
    // possible LAN-select return.
    private var sourceKind: SourceKind = SourceKind.INTERNET
    private var sourceHost: String? = DEFAULT_INTERNET_HOST
    private var bbox: Bbox? = null
    private var estimate: TileEstimate? = null
    private var prefillRegionId: String? = null
    private var prefillRegionName: String? = null

    /** Apply [event] to the wizard state and return the step the Fragment should render. */
    fun onEvent(event: Event): Step = when (event) {
        Event.NewDownloadRequested -> {
            prefillRegionId = null
            prefillRegionName = null
            Step.SourcePicker(prefillRegionId = null, prefillRegionName = null)
        }

        is Event.RedownloadRequested -> {
            prefillRegionId = event.regionId
            prefillRegionName = event.displayName
            bbox = event.bbox?.takeIf { it.size == 4 }?.let {
                runCatching { Bbox(it[0], it[1], it[2], it[3]) }.getOrNull()
            }
            Step.SourcePicker(
                prefillRegionId = event.regionId,
                prefillRegionName = event.displayName,
            )
        }

        is Event.SourceConfirmed -> {
            sourceKind = event.sourceKind
            sourceHost = event.sourceHost
            bboxPickerStep()
        }

        is Event.BboxPicked -> {
            bbox = event.bbox
            estimate = event.estimate
            // Refresh-flow ids/names propagate (won't normally change at Step 2).
            if (event.prefillRegionId != null) prefillRegionId = event.prefillRegionId
            if (event.prefillRegionName != null) prefillRegionName = event.prefillRegionName
            Step.SaveStep(
                sourceKind = sourceKind,
                sourceHost = sourceHost,
                bbox = bbox,
                estimate = estimate,
                prefillRegionId = prefillRegionId,
                prefillRegionName = prefillRegionName,
            )
        }

        Event.SaveBackRequested -> bboxPickerStep()

        is Event.SaveConfirmed -> {
            prefillRegionName = event.displayName
            prefillRegionId = event.regionId
            Step.DownloadProgress(
                regionId = event.regionId,
                displayName = event.displayName,
                bbox = bbox,
                sourceKind = sourceKind,
                sourceHost = sourceHost,
                // Thread the picker's auto-capped zoom range to the downloader.
                // Without it the downloader's z=6..14 default kicks in,
                // downloading ~16× more tiles per extra zoom level.
                zoomMin = estimate?.zoomMin ?: DEFAULT_ZOOM_MIN,
                zoomMax = estimate?.zoomMax ?: DEFAULT_ZOOM_MAX,
            )
        }

        Event.DownloadDone, Event.DownloadFailed, Event.DownloadCancelled, Event.Exit -> {
            reset()
            Step.RegionList
        }
    }

    /** Step 2 launched from the current wizard state (both the forward path and Step 3's Back). */
    private fun bboxPickerStep(): Step.BboxPicker = Step.BboxPicker(
        prefillRegionId = prefillRegionId,
        prefillRegionName = prefillRegionName,
        prefillBbox = bbox,
        sourceKind = sourceKind,
        sourceHost = sourceHost,
    )

    /** Reset all wizard state (matches the pre-extraction `showList()` reset block). */
    private fun reset() {
        sourceKind = SourceKind.UNKNOWN
        sourceHost = null
        bbox = null
        estimate = null
        prefillRegionId = null
        prefillRegionName = null
    }

    companion object {
        /** Hardcoded Internet tile source. The source-picker UI is bypassed —
         *  users don't see or select a source. */
        const val DEFAULT_INTERNET_HOST = "iiab.switnet.org"

        /** Downloader's default zoom range, used when Step 2 forwarded no estimate. */
        private const val DEFAULT_ZOOM_MIN = 6
        private const val DEFAULT_ZOOM_MAX = 14
    }
}

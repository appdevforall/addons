package com.itsaky.androidide.plugins.aicore.fragments

import android.content.res.Resources
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview

/**
 * How [ApprovalDialogFragment] presents a request: its [message], and each [ApprovalPreview] a tool's handler
 * declares. Exhaustive `when`s with no `else`, so a new preview fails to compile until it has a label and a formatter.
 */
internal object ApprovalPresentation {

    /** The heading above the formatted arguments of a [preview] call. */
    @StringRes
    fun argsLabel(preview: ApprovalPreview): Int = when (preview) {
        ApprovalPreview.EDIT -> R.string.approval_proposed_change
        ApprovalPreview.SHELL_COMMAND -> R.string.approval_command
        ApprovalPreview.STOP_COMMAND -> R.string.approval_command_to_stop
        ApprovalPreview.ARGS -> R.string.approval_args
    }

    /** The call's arguments as the dialog shows them; [resources] words any label among them. */
    fun format(preview: ApprovalPreview, args: Map<String, Any?>, resources: Resources): String =
        when (preview) {
            ApprovalPreview.EDIT -> ApprovalTextFormatter.formatEdit(args)
            ApprovalPreview.SHELL_COMMAND -> ApprovalTextFormatter.formatShellCommand(args) { directory ->
                resources.getString(R.string.approval_working_directory, directory)
            }
            ApprovalPreview.STOP_COMMAND -> ApprovalTextFormatter.formatStopCommand(
                args,
                sessionLabel = { resources.getString(R.string.approval_terminal_session, it) },
                unknownCommand = { resources.getString(R.string.approval_unknown_command, it) },
            )
            ApprovalPreview.ARGS -> ApprovalTextFormatter.formatArgs(args)
        }

    /**
     * The approval message, top to bottom: the tool's full registered name and where it came from, as one
     * block, then the [description], then the [preview]'s heading over the formatted [argsText].
     *
     * The name opens the message rather than the title, which is a short question. The stock title is one
     * line, two once `DialogTitle` shrinks it, and it is cut off past that; at font scale 2.0 it cut a long
     * name short. The message scrolls, so the whole name is always there to read.
     *
     * @param toolName the registered name: the tool that will actually run, which a remote source cannot choose.
     * @param source the contributing plugin's label, or null for one of the agent's own tools.
     * @param providerName what the source calls the tool, or null when that is [toolName]; shown only with a [source].
     * @param readOnlyClaim whether the dialog offers "Always Allow" on the providing plugin's word that the tool only reads.
     * @param resources words each line.
     */
    fun message(
        toolName: String,
        source: String?,
        providerName: String?,
        readOnlyClaim: Boolean,
        description: String,
        preview: ApprovalPreview,
        argsText: String,
        resources: Resources,
    ): String = buildString {
        append(resources.getString(R.string.approval_tool, toolName)).append("\n")
        // Provenance before the description: a tool that leaves the device is a different
        // decision from a local edit, and only the source says which this is.
        if (!source.isNullOrBlank()) {
            append(resources.getString(R.string.approval_source, source)).append("\n")
            // Both names, when they differ: the first line says what runs, this says what the
            // source calls it, so neither can be passed off as the other.
            if (!providerName.isNullOrBlank()) {
                append(resources.getString(R.string.approval_provider_name, providerName)).append("\n")
            }
        }
        // "Always Allow" rests on the plugin's own word here, so the user is told whose it is.
        if (readOnlyClaim) append(resources.getString(R.string.approval_read_only_claim)).append("\n")
        append("\n")
        append(description)
        append("\n\n")
        append(resources.getString(argsLabel(preview)))
        append("\n")
        append(argsText)
    }

    /** The preview saved as [name] in the dialog's arguments; [ApprovalPreview.ARGS] when it is missing or unknown. */
    fun named(name: String?): ApprovalPreview =
        ApprovalPreview.entries.firstOrNull { it.name == name } ?: ApprovalPreview.ARGS

    /**
     * The tallest the approval message may grow, so the stock button panel keeps its full height.
     *
     * AppCompat's `AlertDialogLayout` measures the title, then the button panel at its minimum
     * height, then offers the middle panel (this message) what remains ([offeredPx]); only after
     * that does it hand leftover height back to the buttons. A long message therefore leaves the
     * stacked buttons just their minimum. The first measure reserves the difference between the buttons' full
     * and minimum heights, which is the same as `window - title - full buttons`.
     *
     * The result never exceeds half the window ([windowHeightPx]), which keeps the dialog compact on
     * a tall screen, and never drops below [floorPx], so the message cannot collapse to nothing; when
     * even the floor leaves no room, the stock layout scrolls the buttons as it always did.
     *
     * @param windowHeightPx the window height, for the half-window cap.
     * @param offeredPx the height the parent offers this view (the AT_MOST size).
     * @param buttonsFullPx the button panel's natural height, or null when it cannot be found, in which
     *   case the cap is half the window alone.
     * @param buttonsMinPx the button panel's minimum height, already counted in [offeredPx].
     * @param floorPx the least height the message may be given.
     * @param firstOfferedPx the offer of the first measure of this layout sequence (see [sequenceFirstOfferPx]),
     *   or null when this is the first. After that measure the layout hands the buttons their height and
     *   measures the message again with `offered = first - (full - min)` or less, so a later measure at or
     *   below that has the buttons already counted and reserves nothing; reserving again would cut the
     *   message short. Passing this measure's own offer for the first one reserves just as null does.
     */
    fun messageMaxHeightPx(
        windowHeightPx: Int,
        offeredPx: Int,
        buttonsFullPx: Int?,
        buttonsMinPx: Int,
        floorPx: Int,
        firstOfferedPx: Int? = null,
    ): Int {
        val half = (windowHeightPx / 2).coerceAtLeast(0)
        if (buttonsFullPx == null) return half
        val extra = (buttonsFullPx - buttonsMinPx).coerceAtLeast(0)
        val buttonsCounted = firstOfferedPx != null && offeredPx <= firstOfferedPx - extra
        val reserved = if (buttonsCounted) 0 else extra
        return minOf(offeredPx - reserved, half).coerceAtLeast(floorPx)
    }

    /**
     * The first offer of the measure sequence that [offeredPx] belongs to, given the [storedPx] one remembered
     * from earlier measures (null after a layout). Inside one `AlertDialogLayout.tryOnMeasure` the later offers
     * never exceed the first, so a larger offer starts a new sequence and becomes the first. That happens with
     * no layout in between when `ViewRootImpl.measureHierarchy` measures the window again at a greater width.
     */
    fun sequenceFirstOfferPx(storedPx: Int?, offeredPx: Int): Int =
        storedPx?.takeIf { offeredPx <= it } ?: offeredPx
}

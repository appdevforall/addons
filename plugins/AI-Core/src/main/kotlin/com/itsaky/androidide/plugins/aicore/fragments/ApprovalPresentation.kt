package com.itsaky.androidide.plugins.aicore.fragments

import android.content.res.Resources
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview

/**
 * How [ApprovalDialogFragment] presents each [ApprovalPreview] a tool's handler declares. Exhaustive
 * `when`s with no `else`, so a new preview fails to compile until it has a label and a formatter.
 */
internal object ApprovalPresentation {

    /** The heading above the formatted arguments of a [preview] call. */
    @StringRes
    fun argsLabel(preview: ApprovalPreview): Int = when (preview) {
        ApprovalPreview.EDIT -> R.string.approval_proposed_change
        ApprovalPreview.SHELL_COMMAND -> R.string.approval_command
        ApprovalPreview.ARGS -> R.string.approval_args
    }

    /** The call's arguments as the dialog shows them; [resources] words any label among them. */
    fun format(preview: ApprovalPreview, args: Map<String, Any?>, resources: Resources): String =
        when (preview) {
            ApprovalPreview.EDIT -> ApprovalTextFormatter.formatEdit(args)
            ApprovalPreview.SHELL_COMMAND -> ApprovalTextFormatter.formatShellCommand(args) { directory ->
                resources.getString(R.string.approval_working_directory, directory)
            }
            ApprovalPreview.ARGS -> ApprovalTextFormatter.formatArgs(args)
        }

    /** The preview saved as [name] in the dialog's arguments; [ApprovalPreview.ARGS] when it is missing or unknown. */
    fun named(name: String?): ApprovalPreview =
        ApprovalPreview.entries.firstOrNull { it.name == name } ?: ApprovalPreview.ARGS
}

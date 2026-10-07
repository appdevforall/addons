package com.itsaky.androidide.plugins.aicore.fragments

import android.content.res.Resources
import androidx.annotation.StringRes
import com.itsaky.androidide.plugins.aicore.R
import com.itsaky.androidide.plugins.aicore.tool.ApprovalPreview

/**
 * How [ApprovalDialogFragment] presents a pending tool call, one per [ApprovalPreview] the tool's
 * handler declares. Whether "Always Allow" is offered is the request's, not the kind's.
 *
 * @property argsLabel the heading above the formatted arguments.
 */
internal enum class ApprovalKind(@StringRes val argsLabel: Int) {
    EDIT(R.string.approval_proposed_change) {
        override fun format(args: Map<String, Any?>, resources: Resources) =
            ApprovalTextFormatter.formatEdit(args)
    },
    SHELL_COMMAND(R.string.approval_command) {
        override fun format(args: Map<String, Any?>, resources: Resources) =
            ApprovalTextFormatter.formatShellCommand(args) { directory ->
                resources.getString(R.string.approval_working_directory, directory)
            }
    },
    OTHER(R.string.approval_args) {
        override fun format(args: Map<String, Any?>, resources: Resources) =
            ApprovalTextFormatter.formatArgs(args)
    };

    /** The call's arguments as the dialog shows them; [resources] words any label among them. */
    abstract fun format(args: Map<String, Any?>, resources: Resources): String

    companion object {
        /** The kind that renders [preview]. */
        fun of(preview: ApprovalPreview): ApprovalKind = when (preview) {
            ApprovalPreview.EDIT -> EDIT
            ApprovalPreview.SHELL_COMMAND -> SHELL_COMMAND
            ApprovalPreview.ARGS -> OTHER
        }

        /** The kind saved as [name] in the dialog's arguments; [OTHER] when it is missing or unknown. */
        fun named(name: String?): ApprovalKind = entries.firstOrNull { it.name == name } ?: OTHER
    }
}

package com.itsaky.androidide.plugins.aicore.tool

/**
 * How the approval dialog shows a pending call's arguments, declared by the tool's handler so the
 * dialog never matches tool names.
 */
enum class ApprovalPreview {
    /** The arguments as JSON, each value cut short. */
    ARGS,

    /** A diff-style before/after of one edit. */
    EDIT,

    /** The whole shell command, uncut, under the directory it runs in. */
    SHELL_COMMAND,
}

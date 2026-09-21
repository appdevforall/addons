package com.itsaky.androidide.plugins.aicore.tool

import com.itsaky.androidide.plugins.aicore.models.ToolResult

/**
 * Interface for handling tool execution.
 */
interface ToolHandler {
    /**
     * The unique name of this tool.
     */
    val toolName: String

    /**
     * Description of what this tool does.
     */
    val description: String

    /**
     * Execute the tool with the given arguments.
     *
     * @param args Map of argument names to values
     * @return ToolResult indicating success or failure
     */
    suspend fun execute(args: Map<String, Any?>): ToolResult

    /**
     * Whether this tool requires user approval before execution.
     */
    val requiresApproval: Boolean
        get() = false

    /**
     * Whether this tool changes the project, which is what the progress guard reads. Defaults to
     * [requiresApproval], true of exactly the built-ins that act on the project; a source whose
     * approval flag is forced true overrides this instead.
     */
    val mutatesProject: Boolean
        get() = requiresApproval

    /**
     * JSON Schema for the arguments, in the shape the backend's tool definitions take. Empty means
     * untyped, flat string arguments, which is what the tool-call protocol supports today.
     */
    val parametersSchema: Map<String, Any>
        get() = emptyMap()

    /**
     * The tool's name as a person should read it, for the approval dialog and anywhere else a tool
     * is named on screen. Defaults to the registered name, which is what a built-in wants.
     */
    val displayName: String
        get() = toolName

    /**
     * Where this tool came from, shown next to [displayName] so consent is informed: approving a
     * tool that reaches a remote server is a different decision from approving a local edit. Null
     * for the agent's own tools, which need no provenance.
     */
    val sourceLabel: String?
        get() = null

    /**
     * Whether an "Always Allow" answer may cover this tool for the rest of the session. False for
     * anything running outside the agent's own path containment, where the dialog is the only
     * control there is.
     */
    val allowsSessionApproval: Boolean
        get() = true

    /**
     * Arg keys whose values are filesystem paths. The Executor verifies each of
     * these resolves within the project root before the tool runs, so no handler
     * can forget the containment check. Empty by default (non-filesystem tools).
     */
    val pathArgs: List<String>
        get() = emptyList()

    val resolvesPathsInternally: Boolean
        get() = false

    /**
     * Values a [pathArgs] key falls back to when the call omits it, keyed the same way. Declared
     * here so the progress guard sees the file a defaulted write actually touches.
     */
    val pathDefaults: Map<String, String>
        get() = emptyMap()

    /**
     * Alternative argument names, alias → canonical. Small models reliably invent near-miss keys
     * ("old" for "old_string") and the grammar constrains none of them, so the choice is remapping
     * or burning a turn. Applied before the required-argument check; a supplied canonical key wins.
     */
    val argAliases: Map<String, String>
        get() = emptyMap()

    /**
     * Cheap, side-effect-free check run **before** the user is asked to approve the call, so no
     * dialog is spent on an edit that cannot succeed — which wastes the one moment of attention the
     * safety design rests on. Must not mutate: the call may still be denied, and [execute] re-checks.
     * @param args the normalized call arguments.
     * @return acceptance, carrying the arguments to run, or the failure to report instead.
     */
    suspend fun validate(args: Map<String, Any?>): Validation = Validation.Accepted(args)
}

/** Outcome of [ToolHandler.validate]. */
sealed interface Validation {
    /**
     * The call is applicable and should be approved and run.
     * @property args the arguments to actually use; a handler may return a corrected copy, and these
     *   are what the approval dialog shows, so the user reviews what will run.
     */
    data class Accepted(val args: Map<String, Any?>) : Validation

    /** The call cannot succeed; [result] goes back to the model and the user is never prompted. */
    data class Rejected(val result: ToolResult) : Validation
}

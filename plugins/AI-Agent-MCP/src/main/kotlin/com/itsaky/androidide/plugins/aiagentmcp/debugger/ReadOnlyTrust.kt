package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool

/**
 * Which tools of the automatic debugger entry start switched on, and which this plugin declares read-only.
 *
 * Declaring a tool read-only does not skip the approval dialog today: AI-Core overrides
 * `requiresApproval` to true for every contributed tool (`ContributedToolHandler`), so the Agent still
 * asks on each call until automatic approval exists (ADFA-7147).
 *
 * Only that entry may use this: the hint is the server's own claim, and the debugger endpoint is the
 * one server whose code the IDE itself installed. A server the user adds by hand is never asked.
 */
object ReadOnlyTrust {

    /**
     * @param tools what `tools/list` returned.
     * @return the names of the tools that declared `annotations.readOnlyHint` as boolean true.
     */
    fun enabledTools(tools: List<McpTool>): Set<String> =
        tools.filter { it.readOnlyHint }.mapTo(linkedSetOf()) { it.name }

    /**
     * Reads the hint from one `tools/list` entry's `annotations`.
     *
     * Exactly boolean true counts. The string "true", 1, false, null and a missing key do not: an
     * approval skipped on a loose reading (once AI-Core honours the declaration) cannot be taken back.
     *
     * @param hint the value under `readOnlyHint`, or null when absent.
     */
    fun isReadOnlyHint(hint: Any?): Boolean = hint == true
}

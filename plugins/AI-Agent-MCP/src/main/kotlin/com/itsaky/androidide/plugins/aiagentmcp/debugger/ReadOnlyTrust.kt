package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool

/**
 * Which tools of an automatic SQLite debugger entry start switched on, and which this plugin declares
 * read-only.
 *
 * Declaring a tool read-only does not skip the approval dialog today: AI-Core overrides
 * `requiresApproval` to true for every contributed tool (`ContributedToolHandler`), so the Agent still
 * asks on each call until automatic approval exists (ADFA-7147).
 *
 * Only that entry may use this: the hint is the server's own claim, and the SQLite debugger endpoint
 * is the one server whose code the IDE itself installed. A server the user adds by hand is never asked.
 */
object ReadOnlyTrust {

    /**
     * The only tools of an SQLite debugger entry that start switched on, and only when the server
     * advertises them with `readOnlyHint: true`. Its other tools (today `list_connections`,
     * `explain_query_plan`, `get_stats` and `integrity_check`) stay known but off.
     *
     * Why four: AI-Core describes at most 12 contributed tools per message
     * (`PromptToolBudget.MAX_CONTRIBUTED_TOOLS`) and only 4 in compact mode
     * (`COMPACT_MAX_CONTRIBUTED_TOOLS`), counting them in order and dropping the rest. Every app
     * gets its own entry, so with all 8 read-only tools on, two apps alone would use the whole
     * normal budget. With four each, two apps use 8 of 12 and in compact mode the first app's four
     * fill the budget. A hand-added server's tools are listed before any app's, so one with ten or
     * more switched-on tools leaves room for only two of an app's four (none in compact mode).
     *
     * Phase 2 adds the SQLite debugger entry to AI-Agent-MCP's settings, so a user can switch the
     * others on.
     */
    val CORE_TOOLS: Set<String> = linkedSetOf("list_databases", "get_schema", "query", "get_events")

    /**
     * @param tools what `tools/list` returned.
     * @return the names of the [CORE_TOOLS] that declared `annotations.readOnlyHint` as boolean true.
     */
    fun enabledTools(tools: List<McpTool>): Set<String> =
        tools.filter { it.readOnlyHint && it.name in CORE_TOOLS }.mapTo(linkedSetOf()) { it.name }

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

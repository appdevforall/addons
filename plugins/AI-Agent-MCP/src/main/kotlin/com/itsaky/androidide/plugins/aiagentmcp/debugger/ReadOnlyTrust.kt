package com.itsaky.androidide.plugins.aiagentmcp.debugger

import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool

/**
 * Which tools of an automatic SQLite debugger entry start switched on, and which this plugin declares
 * read-only.
 *
 * Declaring a tool read-only does not skip the approval dialog: AI-Core overrides `requiresApproval`
 * to true for every contributed tool (`ContributedToolHandler`). It does let AI-Core 3.6.0 or later
 * offer "Always Allow" for that tool, for the rest of the chat (ADFA-7147). Each app's tool is its own
 * grant, and the grant ignores the arguments. On an older AI-Core the Agent asks on each call.
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
     * The fifth, `read_sql_template` (ADFA-7302), is this plugin's own, not the server's: it renders a
     * template of the project through Code on the Go and returns the SQL without running it, so it reads
     * only. Listed last, so in compact mode the four below keep their places and it is the one dropped.
     *
     * Why only these: AI-Core describes at most 12 contributed tools per message
     * (`PromptToolBudget.MAX_CONTRIBUTED_TOOLS`) and only 4 in compact mode
     * (`COMPACT_MAX_CONTRIBUTED_TOOLS`), counting them in order and dropping the rest. Every app
     * gets its own entry, so with all 8 of the server's read-only tools on, two apps alone would use
     * the whole normal budget. With five each, two apps use 10 of 12 and in compact mode the first
     * app's first four fill the budget. A hand-added server's tools are listed before any app's, so
     * one with ten or more switched-on tools leaves room for only two of an app's five (none in
     * compact mode).
     *
     * Phase 2 adds the SQLite debugger entry to AI-Agent-MCP's settings, so a user can switch the
     * others on.
     */
    val CORE_TOOLS: Set<String> =
        linkedSetOf("list_databases", "get_schema", "query", "get_events", SqlTemplateRead.TOOL_NAME)

    /**
     * The SQLite debugger's tools that change an app's database (ADFA-7300). Switched on only by the
     * settings switch [com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore.allowDatabaseWrites],
     * and never read-only, whatever hint the server sends: "Always Allow" must never cover a write.
     */
    val WRITE_TOOLS: Set<String> = linkedSetOf("execute")

    /**
     * @param tools what `tools/list` returned.
     * @param allowWrites the user switched on database writes in this plugin's settings.
     * @return the names of the [CORE_TOOLS] that declared `annotations.readOnlyHint` as boolean true,
     *   then, when [allowWrites], the listed [WRITE_TOOLS].
     */
    fun enabledTools(tools: List<McpTool>, allowWrites: Boolean = false): Set<String> {
        val on = tools.filter { isReadOnly(it) && it.name in CORE_TOOLS }.mapTo(linkedSetOf()) { it.name }
        if (allowWrites) tools.filter { it.name in WRITE_TOOLS }.mapTo(on) { it.name }
        return on
    }

    /**
     * Whether this plugin declares [tool] of an SQLite debugger entry read-only to AI-Core, which then
     * offers "Always Allow" for it. A [WRITE_TOOLS] tool never is, even if its server claims the hint.
     */
    fun isReadOnly(tool: McpTool): Boolean = tool.readOnlyHint && tool.name !in WRITE_TOOLS

    /**
     * Whether [toolName] of an SQLite debugger entry should tell the model how writes get allowed: it
     * is `query`, whose own description says "Writes are refused: use execute", and the entry's server
     * offers a [WRITE_TOOLS] tool that the settings switch keeps off. Told only to use a tool it does
     * not have, the model looked for another way: on a Note 20 it asked to run `sqlite3` in the shell
     * (ADFA-7300, 2026-11-25).
     *
     * @param knownTools every tool the server listed.
     * @param enabledTools the tools offered now.
     */
    fun needsWriteSwitchHint(toolName: String, knownTools: Collection<String>, enabledTools: Collection<String>): Boolean =
        toolName == "query" && WRITE_TOOLS.any { it in knownTools && it !in enabledTools }

    /**
     * Whether [message], a failed `query`'s text, is SQLite refusing a write on the server's read-only
     * connection: "not authorized" from its authorizer, or "attempt to write a readonly database" from
     * `query_only`. On a Note 20 the model sent an INSERT through `query`, and that refusal was the last
     * thing the chat showed (ADFA-7300, 2026-11-25).
     *
     * It also matches a refused BEGIN, SAVEPOINT, ATTACH or setting PRAGMA, which `execute` refuses
     * too, so the hint then names a switch that would not help. Accepted: the server reports every
     * refusal as SQLite's plain "not authorized", and copying its authorizer's rules here would drift.
     */
    fun isWriteRefusal(message: String?): Boolean =
        message != null && (message.contains("not authorized", ignoreCase = true) ||
            message.contains("readonly database", ignoreCase = true))

    /**
     * Reads the hint from one `tools/list` entry's `annotations`.
     *
     * Exactly boolean true counts. The string "true", 1, false, null and a missing key do not: an
     * "Always Allow" offered on a loose reading cannot be taken back for the rest of the chat.
     *
     * @param hint the value under `readOnlyHint`, or null when absent.
     */
    fun isReadOnlyHint(hint: Any?): Boolean = hint == true
}

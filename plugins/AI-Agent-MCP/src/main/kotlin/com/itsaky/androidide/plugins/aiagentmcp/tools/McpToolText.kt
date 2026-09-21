package com.itsaky.androidide.plugins.aiagentmcp.tools

/**
 * Sanitises the text an MCP server supplies before it leaves this plugin.
 *
 * Only the exposed *name* is built here. A name outside `[a-z0-9_]` is one the model can read but
 * never call, and the prefix and disambiguation rules below need a sanitised name to work on.
 * Descriptions are left alone on purpose: the agent flattens and caps every contributed description
 * itself, on its own side of the plugin boundary, and a second cap here only gave the two constants
 * room to diverge — whereupon the agent's truncation counter would quietly report nothing.
 */
object McpToolText {

    /** Max characters kept from a server label used as a tool-name prefix. */
    private const val MAX_PREFIX_LENGTH = 10

    /** Max characters kept from a tool name; the agent caps the namespaced form again. */
    private const val MAX_NAME_LENGTH = 24

    /** Numbered variants tried for a name two tools truncated onto; past this the tool is dropped. */
    private const val MAX_VARIANTS = 20

    /**
     * The exposed name for one remote tool: a short server prefix plus the tool's own name.
     * @param serverName the user's label for the server.
     * @param toolName the tool's own name.
     * @return the exposed name, or null when nothing usable survives sanitising.
     */
    fun exposedName(serverName: String, toolName: String): String? {
        val tool = identifier(toolName).take(MAX_NAME_LENGTH).trim('_')
        if (tool.isEmpty()) return null
        val prefix = identifier(serverName).take(MAX_PREFIX_LENGTH).trim('_')
        return if (prefix.isEmpty()) tool else "${prefix}_$tool"
    }

    /**
     * A free variant of [name], numbered when two tools ended up sharing one exposed name.
     *
     * Truncation is what makes this necessary: `get_pull_request_comments` and
     * `get_pull_request_committers` are distinct tools that both survive [MAX_NAME_LENGTH] as the
     * same string, and dropping the second would silently take away a tool the user switched on.
     *
     * The numbered form still fits the agent's own cap: the longest name this builds is
     * [MAX_PREFIX_LENGTH] + [MAX_NAME_LENGTH] + a two-digit suffix.
     *
     * @param name the name [exposedName] produced.
     * @param taken the names already published by this source.
     * @return the name to publish, or null when even the numbered forms are spoken for.
     */
    fun disambiguate(name: String, taken: Set<String>): String? {
        if (name !in taken) return name
        return (2..MAX_VARIANTS).map { "${name}_$it" }.firstOrNull { it !in taken }
    }

    /** Lowercases and reduces to `[a-z0-9_]`, collapsing runs of separators into one `_`. */
    private fun identifier(raw: String): String {
        val mapped = raw.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
        return buildString {
            for (char in mapped) {
                if (char == '_' && endsWith("_")) continue
                append(char)
            }
        }.trim('_')
    }
}

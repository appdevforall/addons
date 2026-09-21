package com.itsaky.androidide.plugins.aiagentmcp.settings

/**
 * One configured MCP server.
 *
 * The token is deliberately not a field: it lives encrypted in the Keystore-backed store, keyed by
 * [id], so it is never carried around in a value object that gets logged or put in a Bundle.
 *
 * @property id stable identity, kept across renames so toggles and the token survive an edit.
 * @property name the user's label; also the prefix the server's tools are namespaced with.
 * @property url the server's MCP endpoint.
 * @property enabled whether the server contributes tools at all.
 * @property knownTools the tool names last seen on this server, for the toggle list.
 * @property enabledTools the tools the user switched on; empty is the default, so a new server
 *   contributes nothing until its tools are chosen deliberately.
 */
data class McpServer(
    val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean = true,
    val knownTools: List<String> = emptyList(),
    val enabledTools: Set<String> = emptySet(),
) {
    /** The tools that should actually be offered to the agent right now. */
    val activeTools: List<String>
        get() = if (!enabled) emptyList() else knownTools.filter { it in enabledTools }
}

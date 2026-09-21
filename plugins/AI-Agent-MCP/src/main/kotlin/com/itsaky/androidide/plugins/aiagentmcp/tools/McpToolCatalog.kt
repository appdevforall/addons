package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonRpc
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "$LOG_PREFIX.McpToolCatalog"

/**
 * What each configured server last said its tools are.
 *
 * The agent reads the tool list on a UI-adjacent path and the contract forbids blocking on the
 * network there, so the list has to be answered from memory. This is that memory: filled by an
 * explicit [connect] — on activation, on Connect, after an edit — and read by the source.
 */
object McpToolCatalog {

    private val toolsByServer = ConcurrentHashMap<String, List<McpTool>>()

    /**
     * The cached tools for a server.
     * @param serverId the server.
     * @return its tools, empty before the first successful connection.
     */
    fun tools(serverId: String): List<McpTool> = toolsByServer[serverId].orEmpty()

    /**
     * What one handshake-and-list answered.
     *
     * @property serverName what the server called itself, or null when it gave no name; the
     *   settings pane says it back so the user can tell they reached what they meant to.
     * @property tools the tools it listed.
     */
    data class Listing(val serverName: String?, val tools: List<McpTool>)

    /**
     * Handshakes with one server, reads its tool list and caches it.
     *
     * Blocking, so call it off the main thread. The stored known-tool names are updated too, which
     * is what drops toggles for tools the server no longer offers.
     *
     * @param server the server to ask.
     * @return its name and the tools it listed, empty for a server that offers no catalogue.
     * @throws java.io.IOException when the server cannot be reached or refuses the handshake.
     */
    fun connect(server: McpServer): Listing {
        // Kept, not just its tools: the name the handshake returned lands on the session.
        val session = McpConnections.session(server)
        // Explicit, so a catalogue refused below is told apart from a refused connection.
        session.initialize()
        val tools = try {
            session.listTools()
        } catch (e: McpProtocolException) {
            // No tools capability: some servers expose only prompts or resources, and still work.
            if (e.code != JsonRpc.METHOD_NOT_FOUND) throw e
            emptyList()
        }
        toolsByServer[server.id] = tools
        McpServerStore.setKnownTools(server.id, tools.map { it.name })
        Log.i(TAG, "Server '${server.name}' listed ${tools.size} tool(s)")
        return Listing(session.serverName, tools)
    }

    /**
     * Refreshes every enabled server, tolerating the ones that fail.
     *
     * [keepGoing] is checked before each server because [connect] blocks on a socket with no
     * suspension point in it: cancelling the caller's coroutine cannot interrupt a read already in
     * flight, so between servers is the only place a deactivating plugin can be noticed. Without it
     * a cancelled refresh carries on filling this cache and [McpConnections] after both were
     * cleared, leaving sockets nothing can reach.
     *
     * @param keepGoing false once the caller has stopped caring; the walk stops there.
     * @return how many servers answered.
     */
    fun refreshAll(keepGoing: () -> Boolean = { true }): Int {
        var refreshed = 0
        for (server in McpServerStore.servers().filter { it.enabled }) {
            if (!keepGoing()) {
                Log.i(TAG, "Stopped refreshing tool lists; the caller is gone")
                break
            }
            try {
                connect(server)
                refreshed++
            } catch (e: Exception) {
                // One unreachable server must not cost the user the tools of the others.
                Log.w(TAG, "Could not list tools for '${server.name}': ${e.message}")
            }
        }
        return refreshed
    }

    /** Forgets a server's tools, for one that was removed or edited. */
    fun forget(serverId: String) {
        toolsByServer.remove(serverId)
    }

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        toolsByServer.clear()
    }
}

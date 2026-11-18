package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpProtocolException
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.debugger.AutomaticServers
import com.itsaky.androidide.plugins.aiagentmcp.debugger.EndpointRetry
import com.itsaky.androidide.plugins.aiagentmcp.debugger.McpServers
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpErrorFormatter
import com.itsaky.androidide.plugins.aiagentmcp.errors.McpFailure
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.plugin.McpPlugin
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.transport.JsonRpc
import com.itsaky.androidide.plugins.aiagentmcp.transport.McpExchangeListener
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

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

    /** Re-reads an automatic entry's endpoint and tries once more when the stored one is stale. */
    val retry = EndpointRetry(
        refresh = AutomaticServers::refresh,
        current = { id -> AutomaticServers.servers().firstOrNull { it.id == id } },
        token = AutomaticServers::token,
    )

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
     * is what drops toggles for tools the server no longer offers, and so is [McpServerHealth],
     * which is how the agent's tag for this server learns it went down or came back.
     *
     * @param server the server to ask.
     * @param exchangeListener receives each HTTP exchange the handshake and listing make.
     * @return its name and the tools it listed, empty for a server that offers no catalogue.
     * @throws java.io.IOException when the server cannot be reached or refuses the handshake.
     */
    fun connect(
        server: McpServer,
        exchangeListener: McpExchangeListener = McpExchangeListener.NONE,
    ): Listing {
        McpServerHealth.connectingIfUnknown(server.id)
        val listing = try {
            retry.call(server) { list(it, exchangeListener) }
        } catch (e: Throwable) {
            recordFailure(server, e)
            throw e
        }
        // Deleted while the handshake was in flight: recording it would resurrect its entry.
        if (isConfigured(server.id)) McpServerHealth.available(server.id)
        return listing
    }

    /**
     * Marks [server] degraded for [error], unless the error is a call being cancelled — the server
     * did nothing wrong, and a stopped run must not paint it broken, nor leave it connecting.
     *
     * @param server the server that failed.
     * @param error what it failed with.
     */
    fun recordFailure(server: McpServer, error: Throwable) {
        val failure = McpErrorFormatter.classify(error)
        if (failure == McpFailure.Cancelled) {
            McpServerHealth.cancelConnecting(server.id)
            return
        }
        if (!isConfigured(server.id)) return
        val context = McpPlugin.getContext()?.androidContext
        val refused = failure is McpFailure.TokenRefused || failure is McpFailure.Forbidden
        McpServerHealth.degraded(server.id, McpErrorFormatter.format(context, server.name, error), refused)
    }

    private fun isConfigured(serverId: String): Boolean =
        McpServers.all().any { it.id == serverId }

    /** The handshake-and-list itself; see [connect]. */
    private fun list(server: McpServer, exchangeListener: McpExchangeListener): Listing {
        // Taken before the request: if the endpoint is replaced while this list is in flight, the
        // list is the old process's and must not be attached to the new one.
        val origin = if (server.automatic) AutomaticServers.endpoint(server.id) else null
        // Kept, not just its tools: the name the handshake returned lands on the session.
        val session = McpConnections.session(server)
        val tools = session.recording(exchangeListener) {
            // Explicit, so a catalogue refused below is told apart from a refused connection.
            session.initialize()
            try {
                session.listTools()
            } catch (e: McpProtocolException) {
                // No tools capability: some servers expose only prompts or resources, and still work.
                if (e.code != JsonRpc.METHOD_NOT_FOUND) throw e
                emptyList()
            }
        }
        if (server.automatic) {
            val current = origin != null && AutomaticServers.urlFor(origin) == server.url &&
                AutomaticServers.recordTools(server.id, origin, tools)
            if (!current) {
                Log.i(TAG, "Dropping a tool list from an endpoint '${server.name}' has since left")
                return Listing(session.serverName, tools)
            }
            toolsByServer[server.id] = tools
        } else {
            toolsByServer[server.id] = tools
            McpServerStore.setKnownTools(server.id, tools.map { it.name })
        }
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
     * A server that fails with an Exception or a LinkageError is skipped; any other Error, an
     * OutOfMemoryError above all, ends the walk and propagates.
     *
     * @param keepGoing false once the caller has stopped caring; the walk stops there.
     * @return how many servers answered.
     */
    fun refreshAll(keepGoing: () -> Boolean = { true }): Int {
        var refreshed = 0
        for (server in McpServers.all().filter { it.enabled }) {
            if (!keepGoing()) {
                Log.i(TAG, "Stopped refreshing tool lists; the caller is gone")
                break
            }
            try {
                connect(server)
                refreshed++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // One unreachable server must not cost the user the tools of the others.
                Log.w(TAG, "Could not list tools for '${server.name}': $e")
            } catch (e: LinkageError) {
                // Costs only this server too: connect calls the host (its Keystore, its ServerUrl),
                // and a plugin-api that does not match the host's fails there with a LinkageError,
                // which would otherwise end the activation's coroutine and crash Code on the Go.
                // Nothing broader: an OutOfMemoryError is the process failing, not this server, and
                // has to reach the IDE's uncaught-exception handler.
                Log.w(TAG, "Could not list tools for '${server.name}': $e")
            }
        }
        return refreshed
    }

    /** Forgets a server's tools and health, for one that was removed. */
    fun forget(serverId: String) {
        toolsByServer.remove(serverId)
        McpServerHealth.forget(serverId)
    }

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        toolsByServer.clear()
        McpServerHealth.clear()
    }
}

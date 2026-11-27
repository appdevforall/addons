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
 *
 * A SQLite debugger entry's list is not held here but beside its endpoint in [AutomaticServers],
 * which checks the endpoint and stores the list under one lock: a copy here, written after that
 * lock was let go, could be overwritten by a listing of an endpoint that has since been replaced.
 */
object McpToolCatalog {

    /** The tools of the user's own servers; an automatic entry's are in [AutomaticServers]. */
    private val toolsByServer = ConcurrentHashMap<String, List<McpTool>>()

    /**
     * Taken by [forget] and [clear], and around the check and the write that store a listing of one
     * of the user's servers. The deletion removes the server from the store before it forgets its
     * tools, so under this lock either the listing is stored first and forgotten with the server, or
     * the check sees the server gone; written with no check, a listing that outlived the deletion
     * brought the tools of a server that no longer exists back.
     */
    private val lock = Any()

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
    fun tools(serverId: String): List<McpTool> =
        if (AutomaticServers.isAutomatic(serverId)) AutomaticServers.tools(serverId)
        else toolsByServer[serverId].orEmpty()

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
        // Each checked with its write, under the lock a removal takes: a server deleted while the
        // handshake was in flight, even just after a check, gets no row back, nor one whose URL was
        // edited since [server] was read.
        val configured = { _: String -> McpServers.isConfigured(server) }
        McpServerHealth.connectingIfUnknown(server.id, configured)
        val listing = try {
            retry.call(server) { list(it, exchangeListener) }
        } catch (e: Throwable) {
            recordFailure(server, e)
            throw e
        }
        McpServerHealth.available(server.id, configured)
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
        val context = McpPlugin.getContext()?.androidContext
        val refused = failure is McpFailure.TokenRefused || failure is McpFailure.Forbidden
        // Not for a server removed meanwhile, or moved to another URL, checked with the write as in
        // connect: a stale caller's 401 from the old URL must not mark the moved server refused.
        McpServerHealth.degraded(
            server.id,
            McpErrorFormatter.format(context, server, error),
            refused,
        ) { McpServers.isConfigured(server) }
    }

    /** The handshake-and-list itself; see [connect]. */
    private fun list(server: McpServer, exchangeListener: McpExchangeListener): Listing {
        // Taken before the request: if the endpoint is replaced while this list is in flight, the
        // list is the old process's and must not be attached to the new one.
        val origin = if (server.automatic) AutomaticServers.endpoint(server.id) else null
        // The name too: the handshake returns it on the session, which is ended here for a server
        // removed meanwhile.
        val (serverName, tools) = McpConnections.withSession(server) { session ->
            val listed = session.recording(exchangeListener) {
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
            session.serverName to listed
        }
        if (server.automatic) {
            // Stored by recordTools alone, beside the endpoint it checks, under the same lock.
            val current = origin != null && AutomaticServers.urlFor(origin) == server.url &&
                AutomaticServers.recordTools(server.id, origin, tools)
            if (!current) {
                Log.i(TAG, "Dropping a tool list from an endpoint '${server.name}' has since left")
                return Listing(serverName, tools)
            }
        } else {
            val stored = synchronized(lock) {
                McpServers.isConfigured(server).also { if (it) toolsByServer[server.id] = tools }
            }
            if (!stored) {
                Log.i(TAG, "Dropping a tool list from '${server.name}', removed or moved while it listed")
                return Listing(serverName, tools)
            }
            McpServerStore.setKnownTools(server.id, server.url, tools.map { it.name })
        }
        Log.i(TAG, "Server '${server.name}' listed ${tools.size} tool(s)")
        return Listing(serverName, tools)
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

    /**
     * Forgets a server's tools and health, for one of the user's own that was removed. A SQLite
     * debugger entry that went away is cleaned up by `AutomaticServers.refresh` itself, under its
     * lock, so the entry that replaces it under the same ID keeps its own.
     */
    fun forget(serverId: String) {
        synchronized(lock) { toolsByServer.remove(serverId) }
        McpServerHealth.forget(serverId)
    }

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        synchronized(lock) { toolsByServer.clear() }
        McpServerHealth.clear()
    }
}

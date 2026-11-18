package com.itsaky.androidide.plugins.aiagentmcp.debugger

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServer
import com.itsaky.androidide.plugins.aiagentmcp.settings.McpServerStore
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpServerHealth
import com.itsaky.androidide.plugins.aiagentmcp.tools.McpToolCatalog
import com.itsaky.androidide.plugins.services.DebuggerEndpoint
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "$LOG_PREFIX.AutomaticServers"

/** One endpoint as this plugin needs it, kept apart from the host type so tests need no jar. */
data class AutomaticEndpoint(val applicationId: String, val port: Int, val token: String) {
    // The token is a credential: it stays out of logs.
    override fun toString() = "AutomaticEndpoint($applicationId, port=$port)"
}

/**
 * Reports the first failure in full and every later one briefly, so a failure that repeats on a timer
 * does not fill the log. One instance per process.
 */
class RepeatedFailureLog(
    private val first: (Throwable) -> Unit,
    private val later: (Throwable) -> Unit,
) {
    private val reported = java.util.concurrent.atomic.AtomicBoolean(false)

    fun report(e: Throwable) {
        if (reported.compareAndSet(false, true)) first(e) else later(e)
    }
}

/**
 * The server entries Code on the Go supplies for the open project's debuggable apps.
 *
 * Held in memory only: port and token change on every app start, so nothing here is worth
 * persisting, and the token stays out of the Keystore-backed store the user's own servers use.
 * Read from the host on [refresh]; an app that has not reported yields no entry at all.
 */
object AutomaticServers {

    const val ID_PREFIX = "debugger:"

    private class State(val endpoint: AutomaticEndpoint, val tools: List<McpTool> = emptyList())

    @Volatile private var states: Map<String, State> = emptyMap()

    @Volatile private var source: (() -> List<AutomaticEndpoint>)? = null

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private val endpointListeners = CopyOnWriteArrayList<(String) -> Unit>()

    /**
     * The probe calls [refresh] every 20 s, so a refused permission or a plugin-api mismatch would
     * otherwise log a stack trace forever.
     */
    private val readFailures = RepeatedFailureLog(
        first = { e -> Log.w(TAG, "Could not read the debugger endpoints; keeping the previous entries", e) },
        later = { e -> Log.d(TAG, "Still cannot read the debugger endpoints: $e") },
    )

    /**
     * Where endpoints come from; null (deactivation) clears every entry.
     */
    fun install(source: (() -> List<AutomaticEndpoint>)?) {
        if (source == null) {
            // Under the lock the other overload takes, so a late install cannot set its source after this.
            synchronized(this) { this.source = null }
            clear()
        } else {
            install(source) { true }
        }
    }

    /**
     * As [install], for a source that may be superseded while its first read is blocked.
     *
     * @param keepGoing false once the activation installing [source] is over. The first read blocks
     *   and cannot be cancelled, so an activation that ended during it must neither keep [source]
     *   nor publish what it read: deactivation has already cleared everything, and a later
     *   activation owns the state now.
     */
    fun install(source: () -> List<AutomaticEndpoint>, keepGoing: () -> Boolean) {
        synchronized(this) {
            if (!keepGoing()) return
            this.source = source
        }
        refresh(keepGoing)
    }

    /** Adapts the host's service. A host without the service, or without the permission, is no source. */
    fun fromHost(read: () -> List<DebuggerEndpoint>): () -> List<AutomaticEndpoint> = {
        read().map { AutomaticEndpoint(it.applicationId, it.port, it.token) }
    }

    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Registers [listener], called with the id of an entry that appeared or whose port or token
     * changed, so the owner can list its tools at once instead of at the next probe.
     */
    fun addEndpointListener(listener: (String) -> Unit) {
        endpointListeners.add(listener)
    }

    fun removeEndpointListener(listener: (String) -> Unit) {
        endpointListeners.remove(listener)
    }

    /** How many endpoint listeners are registered; a test seam for a listener nothing removes. */
    internal val endpointListenerCount: Int get() = endpointListeners.size

    /**
     * Re-reads the endpoints. Blocking host call: use a background thread.
     * Nothing is stored when the source that was read is no longer installed, so a refresh in
     * flight when deactivation cleared the entries (or a later activation replaced the source)
     * cannot restore them, whichever caller started it.
     *
     * @param keepGoing checked after the read, before anything is stored.
     * @return true when the set of entries, a port or a token changed.
     */
    fun refresh(keepGoing: () -> Boolean = { true }): Boolean {
        val read = source ?: return false
        val fresh = try {
            read().associateBy { idFor(it.applicationId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // A failed read is not "no apps": the entries stay as they were, since wiping them
            // would take every debugger tool away from the agent on a transient database error.
            // Throwable, not Exception: a plugin-api that does not match the host's fails here with
            // an AbstractMethodError or another LinkageError, and one that escaped would end the
            // caller's coroutine in the IDE's uncaught-exception handler and crash Code on the Go.
            readFailures.report(e)
            return false
        }
        val moved = mutableListOf<String>()
        val removed: List<String>
        val changed = synchronized(this) {
            if (source !== read || !keepGoing()) return false
            val old = states
            val next = fresh.mapValues { (id, endpoint) ->
                val previous = old[id]
                when {
                    previous == null -> State(endpoint).also { moved += id }
                    previous.endpoint == endpoint -> previous
                    // The previous list stays until the new process answers: the tool names almost
                    // never change on a relaunch, and an empty list would take every tool away from
                    // the agent for as long as the re-list takes.
                    else -> State(endpoint, previous.tools).also { moved += id }
                }
            }
            states = next
            removed = (old.keys - next.keys).toList()
            old.mapValues { it.value.endpoint } != next.mapValues { it.value.endpoint }
        }
        for (id in removed) {
            McpToolCatalog.forget(id)
            McpConnections.invalidate(id)
        }
        for (id in moved) {
            // A refused credential is not final for this entry: the new one has not been tried.
            McpServerHealth.credentialChanged(id)
            for (l in endpointListeners) runCatching { l(id) }
        }
        if (changed) fireChanged()
        return changed
    }

    /** The entries to offer, with their tool switches already decided by [ReadOnlyTrust]. */
    fun servers(): List<McpServer> = states.map { (id, state) ->
        McpServer(
            id = id,
            name = "SQLite debugger (${state.endpoint.applicationId})",
            url = urlFor(state.endpoint),
            enabled = true,
            knownTools = state.tools.map { it.name },
            enabledTools = ReadOnlyTrust.enabledTools(state.tools),
            automatic = true,
        )
    }

    fun isAutomatic(serverId: String): Boolean = serverId.startsWith(ID_PREFIX)

    /** The bearer token for [serverId], or null when it has no entry (any more). */
    fun token(serverId: String): String? = states[serverId]?.endpoint?.token

    /** The endpoint now stored for [serverId], or null when it has no entry. */
    fun endpoint(serverId: String): AutomaticEndpoint? = states[serverId]?.endpoint

    fun urlFor(endpoint: AutomaticEndpoint) = "http://127.0.0.1:${endpoint.port}/mcp"

    /**
     * Remembers what the server listed.
     *
     * @param origin the endpoint the list was fetched from. A list from an endpoint that has since
     *   been replaced belongs to the old process and is dropped; the check and the write share one
     *   lock, so a refresh cannot slip between them.
     * @return false when the list was dropped (no entry any more, or [origin] is out of date).
     */
    fun recordTools(serverId: String, origin: AutomaticEndpoint, tools: List<McpTool>): Boolean {
        synchronized(this) {
            val state = states[serverId] ?: return false
            if (state.endpoint != origin) return false
            // The probe re-lists every cycle; an unchanged list must not make the agent rebuild.
            if (state.tools == tools) return true
            states = states + (serverId to State(state.endpoint, tools))
        }
        fireChanged()
        return true
    }

    fun clear() {
        val had = synchronized(this) { states.isNotEmpty().also { states = emptyMap() } }
        if (had) fireChanged()
    }

    fun idFor(applicationId: String) = ID_PREFIX + applicationId

    /** The application ID an automatic entry's id was made from. */
    fun applicationIdOf(serverId: String) = serverId.removePrefix(ID_PREFIX)

    private fun fireChanged() {
        for (l in listeners) runCatching { l() }
    }
}

/** Every server the agent may use: the user's own plus the automatic entries. */
object McpServers {
    fun all(): List<McpServer> = McpServerStore.servers() + AutomaticServers.servers()
}

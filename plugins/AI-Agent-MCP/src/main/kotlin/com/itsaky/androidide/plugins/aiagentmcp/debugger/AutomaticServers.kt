package com.itsaky.androidide.plugins.aiagentmcp.debugger

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.client.McpConnections
import com.itsaky.androidide.plugins.aiagentmcp.client.McpTool
import com.itsaky.androidide.plugins.aiagentmcp.errors.runCatchingRecoverable
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

    /**
     * Numbers each [refresh]'s read in the order the reads start, and the number of the read now
     * stored. Both are guarded by this object's lock.
     */
    private var readsStarted = 0L
    private var storedRead = 0L

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
     * The first read tells no endpoint listener (see [addEndpointListener]): whoever installs a
     * source lists every entry straight after, as the plugin's activation does with
     * `McpToolCatalog.refreshAll`, and a listener told of each entry would list it a second time.
     * Change listeners are told as on any read.
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
        readEndpoints(keepGoing, tellEndpointListeners = false)
    }

    /** Adapts the host's service. A host without the service, or without the permission, is no source. */
    fun fromHost(read: () -> List<DebuggerEndpoint>): () -> List<AutomaticEndpoint> = {
        read().map { AutomaticEndpoint(it.applicationId, it.port, it.token) }
    }

    /**
     * Registers [listener], called after the set of entries, a port, a token or a tool list
     * changed. Adding one already registered does nothing, so a host that activates the plugin
     * twice still gets one call per change, and one [removeChangeListener] takes it out.
     */
    fun addChangeListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** How many change listeners are registered; a test seam for a listener registered twice. */
    internal val changeListenerCount: Int get() = listeners.size

    /**
     * Registers [listener], called with the id of an entry that appeared or whose port or token
     * changed, so the owner can list its tools at once instead of at the next probe. Adding one
     * already registered does nothing, as for [addChangeListener].
     *
     * Guarded like the change listeners: an Exception or a LinkageError from [listener] costs only
     * that call, and any other Error propagates to [refresh]'s caller; see [runCatchingRecoverable].
     */
    fun addEndpointListener(listener: (String) -> Unit) {
        endpointListeners.addIfAbsent(listener)
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
     * cannot restore them, whichever caller started it. Nor is it stored when a refresh that
     * started later has already stored its own: the probe's refresh and a failing call's retry can
     * overlap, and the read that started first may finish last with the endpoint the other has
     * already replaced.
     *
     * A read that fails with an Exception or a LinkageError leaves the entries as they were; any
     * other Error, an OutOfMemoryError above all, propagates to the caller. So does one from a
     * change or endpoint listener, or from closing the session of an entry that went away. Such an
     * Error comes after the new entries are stored, so it skips what was still to run for this
     * read: the remaining endpoint listeners, the reset of a refused credential for the remaining
     * moved entries, and the change listeners. No later read repeats them, since it finds those
     * entries unchanged. That is accepted rather than guarded against: the Error is the process
     * failing, and the IDE's uncaught-exception handler, which it is on its way to, ends it.
     *
     * @param keepGoing checked after the read, before anything is stored.
     * @return true when the set of entries, a port or a token changed.
     */
    fun refresh(keepGoing: () -> Boolean = { true }): Boolean =
        readEndpoints(keepGoing, tellEndpointListeners = true)

    /**
     * [refresh] itself.
     *
     * @param tellEndpointListeners false for [install]'s first read; see there.
     */
    private fun readEndpoints(keepGoing: () -> Boolean, tellEndpointListeners: Boolean): Boolean {
        val (read, ticket) = synchronized(this) {
            val current = source ?: return false
            current to ++readsStarted
        }
        val fresh = try {
            read().associateBy { idFor(it.applicationId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A failed read is not "no apps": the entries stay as they were, since wiping them
            // would take every debugger tool away from the agent on a transient database error.
            readFailures.report(e)
            return false
        } catch (e: LinkageError) {
            // Treated as a failed read too: a plugin-api that does not match the host's fails here
            // with an AbstractMethodError or another LinkageError, and one that escaped would end
            // the caller's coroutine in the IDE's uncaught-exception handler and crash Code on the
            // Go. Nothing broader than these two: an OutOfMemoryError or a StackOverflowError is
            // the process failing, not the read, and has to reach that handler rather than be
            // logged here every 20 s as a read that failed.
            readFailures.report(e)
            return false
        }
        val moved = mutableListOf<String>()
        val removed: List<String>
        val changed = synchronized(this) {
            if (source !== read || !keepGoing() || ticket < storedRead) return false
            storedRead = ticket
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
            if (tellEndpointListeners) for (l in endpointListeners) runCatchingRecoverable { l(id) }
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

    /** A listener that tells the host may fail like any host call; see [runCatchingRecoverable]. */
    private fun fireChanged() {
        for (l in listeners) runCatchingRecoverable { l() }
    }
}

/** Every server the agent may use: the user's own plus the automatic entries. */
object McpServers {
    fun all(): List<McpServer> = McpServerStore.servers() + AutomaticServers.servers()
}

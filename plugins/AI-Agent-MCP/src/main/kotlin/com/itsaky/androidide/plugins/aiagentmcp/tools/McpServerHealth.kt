package com.itsaky.androidide.plugins.aiagentmcp.tools

import android.util.Log
import com.itsaky.androidide.plugins.aiagentmcp.logging.LOG_PREFIX
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "$LOG_PREFIX.McpServerHealth"

/**
 * Whether each configured server answered the last time it was asked.
 *
 * The agent's tag row reads this on the UI thread, and the contract forbids probing a server
 * there, so the answer has to come from memory. It is written wherever this plugin already talks
 * to a server — a handshake, a tool listing, a tool call — and by the plugin's periodic probe.
 */
object McpServerHealth {

    /** How a server is doing, in this plugin's own terms; [McpToolSource] maps it onto the host's. */
    enum class State { CONNECTING, AVAILABLE, DEGRADED }

    /**
     * @property state the server's state.
     * @property message one sentence for the user when [state] is [State.DEGRADED], else null.
     * @property refused the server refused the credential, which only the user can fix.
     */
    data class Health(val state: State, val message: String? = null, val refused: Boolean = false)

    private val byServer = ConcurrentHashMap<String, Health>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * @param serverId the server.
     * @return what is known of it, or null before it was ever asked.
     */
    fun of(serverId: String): Health? = byServer[serverId]

    /**
     * Records a first attempt under way. Only for a server with no answer yet: a re-probe of a
     * known server keeps its last answer until the new one lands, so the tag does not flicker.
     */
    fun connectingIfUnknown(serverId: String) {
        if (byServer.putIfAbsent(serverId, Health(State.CONNECTING)) == null) fireChanged()
    }

    /**
     * Undoes [connectingIfUnknown] for a first attempt that was cancelled, so the server reads as
     * never asked. An answer recorded meanwhile is kept.
     */
    fun cancelConnecting(serverId: String) {
        if (byServer.remove(serverId, Health(State.CONNECTING))) fireChanged()
    }

    /** Records that the server answered. */
    fun available(serverId: String) = set(serverId, Health(State.AVAILABLE))

    /**
     * Records that the server could not be used.
     * @param message why, for the user, in the words the settings pane would use.
     * @param refused whether it refused the credential; see [Health.refused].
     */
    fun degraded(serverId: String, message: String, refused: Boolean = false) =
        set(serverId, Health(State.DEGRADED, message, refused))

    /** Lets the probe ask a server that refused its credential again, once the user changed it. */
    fun credentialChanged(serverId: String) {
        byServer.computeIfPresent(serverId) { _, health -> health.copy(refused = false) }
    }

    /** Forgets a server that was removed. */
    fun forget(serverId: String) {
        if (byServer.remove(serverId) != null) fireChanged()
    }

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        if (byServer.isEmpty()) return
        byServer.clear()
        fireChanged()
    }

    /** Registers [listener], called after any server's health changes. */
    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Removes a listener added by [addChangeListener]. */
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Stores [health], telling the listeners only when it differs from what was there. */
    private fun set(serverId: String, health: Health) {
        if (byServer.put(serverId, health) != health) fireChanged()
    }

    private fun fireChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Throwable) {
                Log.e(TAG, "A health change listener threw", e)
            }
        }
    }
}

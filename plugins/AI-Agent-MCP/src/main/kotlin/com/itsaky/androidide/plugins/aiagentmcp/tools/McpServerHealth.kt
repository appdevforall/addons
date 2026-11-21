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
     * Taken by every removal ([detach], [forget], [clear]), and by a write made only while its
     * server is configured, around the check and the write together. A removal landing between the
     * two would otherwise be undone by the write, bringing back the row of a server that is gone. A
     * removal takes its server out of the list first and forgets its row after, so under this lock
     * either the write comes first and the removal takes its row, or the check sees the server gone.
     * Nothing is called under it but that check, and never a listener.
     */
    private val lock = Any()

    /** Writes a row whatever the list holds; the default of every `configured` below. */
    private val always: (String) -> Boolean = { true }

    /**
     * @param serverId the server.
     * @return what is known of it, or null before it was ever asked.
     */
    fun of(serverId: String): Health? = byServer[serverId]

    /**
     * Records a first attempt under way. Only for a server with no answer yet: a re-probe of a
     * known server keeps its last answer until the new one lands, so the tag does not flicker.
     *
     * @param configured whether [serverId] is still configured; checked with the write, under the
     *   lock a removal takes, and false skips the write.
     */
    fun connectingIfUnknown(serverId: String, configured: (String) -> Boolean = always) {
        val added = synchronized(lock) {
            configured(serverId) && byServer.putIfAbsent(serverId, Health(State.CONNECTING)) == null
        }
        if (added) fireChanged()
    }

    /**
     * Undoes [connectingIfUnknown] for a first attempt that was cancelled, so the server reads as
     * never asked. An answer recorded meanwhile is kept.
     */
    fun cancelConnecting(serverId: String) {
        if (byServer.remove(serverId, Health(State.CONNECTING))) fireChanged()
    }

    /**
     * Records that the server answered.
     * @param configured as for [connectingIfUnknown].
     */
    fun available(serverId: String, configured: (String) -> Boolean = always) =
        set(serverId, Health(State.AVAILABLE), configured)

    /**
     * Records that the server could not be used.
     * @param message why, for the user, in the words the settings pane would use.
     * @param refused whether it refused the credential; see [Health.refused].
     * @param configured as for [connectingIfUnknown].
     */
    fun degraded(
        serverId: String,
        message: String,
        refused: Boolean = false,
        configured: (String) -> Boolean = always,
    ) = set(serverId, Health(State.DEGRADED, message, refused), configured)

    /** Lets the probe ask a server that refused its credential again, once the user changed it. */
    fun credentialChanged(serverId: String) {
        byServer.computeIfPresent(serverId) { _, health -> health.copy(refused = false) }
    }

    /** Forgets a server that was removed. */
    fun forget(serverId: String) {
        if (detach(serverId)) fireChanged()
    }

    /**
     * As [forget], without telling the listeners: for a caller that forgets a server under a lock
     * of its own, which a listener calling the host must not run under. That caller calls
     * [notifyChanged] once it has let go, when this returned true.
     *
     * @return whether anything was known of [serverId].
     */
    internal fun detach(serverId: String): Boolean = synchronized(lock) { byServer.remove(serverId) != null }

    /** Tells the listeners of a change [detach] made. */
    internal fun notifyChanged() = fireChanged()

    /** Forgets everything, for the plugin shutting down. */
    fun clear() {
        val had = synchronized(lock) { byServer.isNotEmpty().also { byServer.clear() } }
        if (had) fireChanged()
    }

    /**
     * Registers [listener], called after any server's health changes. Adding one already
     * registered does nothing, so a host that activates the plugin twice still gets one call per
     * change, and one [removeChangeListener] takes it out.
     */
    fun addChangeListener(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    /** Removes a listener added by [addChangeListener]. */
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** How many change listeners are registered; a test seam for a listener registered twice. */
    internal val changeListenerCount: Int get() = listeners.size

    /**
     * Stores [health] while [configured] holds, telling the listeners, after the lock is let go,
     * only when it differs from what was there.
     */
    private fun set(serverId: String, health: Health, configured: (String) -> Boolean) {
        val changed = synchronized(lock) {
            if (!configured(serverId)) return
            byServer.put(serverId, health) != health
        }
        if (changed) fireChanged()
    }

    /**
     * Tells every listener, one failing listener costing only itself. A listener may call the
     * host, so a LinkageError is survived as well as an Exception; anything else, an
     * OutOfMemoryError above all, propagates to whoever made the change.
     */
    private fun fireChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Exception) {
                Log.e(TAG, "A health change listener threw", e)
            } catch (e: LinkageError) {
                Log.e(TAG, "A health change listener threw", e)
            }
        }
    }
}

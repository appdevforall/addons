package com.itsaky.androidide.plugins.aicore.tool.sources

import android.util.Log
import com.itsaky.androidide.plugins.aicore.logging.LOG_PREFIX
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "$LOG_PREFIX.ToolSourceStore"

/**
 * The tool sources currently registered, keyed by provider.
 *
 * Registration replaces by [ContributedToolSource.providerId], which is how a provider recovers
 * after this plugin restarts, and every mutation fires [addChangeListener] so whatever derives from
 * the tool list — router, executor, grammar — is rebuilt rather than left stale.
 */
class ToolSourceStore {

    companion object {
        /**
         * The process-wide store. The registry is published once per process while the chat
         * ViewModel is per screen, so the two meet here rather than through the ViewModel.
         */
        val shared = ToolSourceStore()
    }

    private val lock = Any()
    private val sources = LinkedHashMap<String, ContributedToolSource>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /**
     * Adds [source], replacing any source already registered under the same provider id.
     * @param source the source to register; one with a blank provider id is refused.
     */
    fun register(source: ContributedToolSource) {
        val providerId = source.providerId.trim()
        if (providerId.isEmpty()) {
            Log.w(TAG, "Refusing a tool source with a blank providerId")
            return
        }
        synchronized(lock) { sources[providerId] = source }
        Log.i(TAG, "Registered tool source '$providerId'")
        fireChanged()
    }

    /**
     * Removes the source registered under [providerId]; unknown ids are ignored.
     * @param providerId the provider to drop.
     */
    fun unregister(providerId: String) {
        val removed = synchronized(lock) { sources.remove(providerId.trim()) != null }
        if (removed) {
            Log.i(TAG, "Unregistered tool source '$providerId'")
            fireChanged()
        }
    }

    /**
     * Drops every registered source, for the agent plugin shutting down. Providers re-register
     * from their own lifecycle listener when it comes back, which is also how they survive a
     * cold start that loaded them first.
     */
    fun clear() {
        val had = synchronized(lock) {
            val count = sources.size
            sources.clear()
            count
        }
        if (had > 0) {
            Log.i(TAG, "Cleared $had tool source(s)")
            fireChanged()
        }
    }

    /**
     * Every registered source, in registration order.
     * @return a snapshot of the sources.
     */
    fun sources(): List<ContributedToolSource> = synchronized(lock) { sources.values.toList() }

    /**
     * Signals that a provider's tool list has changed and must be read again.
     * @param providerId the provider whose tools changed.
     */
    fun toolsChanged(providerId: String) {
        val known = synchronized(lock) { sources.containsKey(providerId.trim()) }
        if (!known) {
            Log.d(TAG, "Ignoring a tools-changed signal from unregistered '$providerId'")
            return
        }
        Log.i(TAG, "Tool list changed for '$providerId'")
        fireChanged()
    }

    /** Registers [listener], called after every change to the registered sources. */
    fun addChangeListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    /** Removes a listener added by [addChangeListener]. */
    fun removeChangeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Builds one handler per contributed tool, namespaced into the agent's global tool namespace.
     *
     * A source that throws from [ContributedToolSource.listTools] is skipped whole and the others
     * survive; a tool whose name collides with [reservedNames] is dropped outright, and one that
     * collides with an earlier contributed tool is qualified with its provider's alias. So a source
     * can neither shadow a built-in like `edit_file` nor reach one under a longer name.
     *
     * @param reservedNames names already taken: the built-in tools and the terminal tool.
     * @return the contributed handlers, in registration order.
     */
    fun handlers(reservedNames: Set<String>): List<ContributedToolHandler> {
        val taken = reservedNames.toMutableSet()
        val handlers = mutableListOf<ContributedToolHandler>()

        for (source in sources()) {
            val tools = try {
                source.listTools()
            } catch (e: Throwable) {
                Log.e(TAG, "Source '${source.providerId}' failed to list its tools; skipping it", e)
                continue
            }

            for (tool in tools) {
                val candidates = ContributedToolNames.candidates(source.providerId, tool.name)
                if (candidates.isEmpty()) {
                    Log.w(TAG, "Dropping tool '${tool.name}' from '${source.providerId}': unusable name")
                    continue
                }
                // Dropped rather than qualified: `respond` published as `<alias>_respond` is still
                // reachable through the router's suffix pass, which would hand a model's final
                // answer to a remote server instead of to the user.
                if (candidates.first() in reservedNames) {
                    Log.w(
                        TAG,
                        "Dropping tool '${tool.name}' from '${source.providerId}': the name is reserved"
                    )
                    continue
                }
                // The plain name first, its qualified form second; `add` returns false for a name
                // already spoken for, which is exactly the fall-through this wants.
                val name = candidates.firstOrNull { taken.add(it) }
                if (name == null) {
                    Log.w(TAG, "Dropping tool '${tool.name}' from '${source.providerId}': name already taken")
                    continue
                }
                if (name != candidates.first()) {
                    Log.i(TAG, "Tool '${tool.name}' from '${source.providerId}' registered as '$name'")
                }
                handlers += ContributedToolHandler(source, tool, name)
            }
        }
        return handlers
    }

    /** Notifies listeners outside the lock, tolerating one that throws. */
    private fun fireChanged() {
        for (listener in listeners) {
            try {
                listener()
            } catch (e: Throwable) {
                Log.e(TAG, "A tool-source change listener threw", e)
            }
        }
    }
}

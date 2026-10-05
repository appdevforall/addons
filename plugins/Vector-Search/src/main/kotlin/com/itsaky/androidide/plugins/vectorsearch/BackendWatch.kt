package com.itsaky.androidide.plugins.vectorsearch

import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.PluginLifecycleListener
import com.itsaky.androidide.plugins.ai.LlmBackendRegistration
import com.itsaky.androidide.plugins.services.LlmInferenceService
import com.itsaky.androidide.plugins.services.LlmInferenceService.BackendChangeListener

/**
 * Keeps one [BackendChangeListener] on AI Core's inference service while this plugin is active,
 * and calls [onChanged] when the selected backend, or the selection, may have changed. Added again
 * each time AI Core activates, since AI Core restarts on its own and takes its listeners with it.
 *
 * @param context this plugin's context
 * @param resolveService AI Core's inference service, or null while it is not published
 * @param onChanged called on whichever thread reported the change; must not block
 */
class BackendWatch(
    private val context: PluginContext,
    private val resolveService: () -> LlmInferenceService?,
    private val onChanged: () -> Unit,
) {

    /** The service the listener is on, so [stop] removes it from that one, not a newer one. */
    @Volatile private var watched: LlmInferenceService? = null

    /** The selection at the last reported change, so a change of selection is never missed. */
    @Volatile private var lastSelected: String? = null

    /** A field, not a lambda at the call, so [stop] removes the very instance [watch] added. */
    private val listener = BackendChangeListener { backendId ->
        if (concernsSelection(backendId)) onChanged()
    }

    private val providerLifecycle = object : PluginLifecycleListener {
        override fun onPluginActivated(pluginId: String) {
            if (pluginId != LlmBackendRegistration.AI_CORE_PLUGIN_ID) return
            watch()
            onChanged()
        }

        override fun onPluginDeactivated(pluginId: String) = forget(pluginId)

        override fun onPluginUninstalled(pluginId: String) = forget(pluginId)
    }

    /** Starts watching; AI Core need not be active yet. Pairs with the plugin's `activate`. */
    fun start() {
        context.addPluginLifecycleListener(providerLifecycle)
        watch()
    }

    /** Removes the listener and stops following AI Core. Idempotent; pairs with `deactivate`. */
    fun stop() {
        runCatching { context.removePluginLifecycleListener(providerLifecycle) }
        unwatch()
    }

    /**
     * Whether a change to [backendId] matters to the screen: it is the selected backend, it was the
     * selected one, or the selection itself moved. Other backends' status changes do not.
     */
    private fun concernsSelection(backendId: String): Boolean {
        val selected = runCatching { watched?.preferredBackendId }.getOrNull()
        val previous = lastSelected
        lastSelected = selected
        return backendId == selected || backendId == previous || selected != previous
    }

    @Synchronized
    private fun watch() {
        val service = runCatching { resolveService() }.getOrNull() ?: return
        if (service === watched) return
        unwatch()
        // Set before adding, so a change reported during the add already reads this service.
        watched = service
        lastSelected = runCatching { service.preferredBackendId }.getOrNull()
        try {
            service.addBackendChangeListener(listener)
        } catch (e: Exception) {
            watched = null
            context.logger.warn("BackendWatch: could not listen for backend changes", e)
        }
    }

    @Synchronized
    private fun unwatch() {
        val service = watched ?: return
        watched = null
        runCatching { service.removeBackendChangeListener(listener) }
    }

    /** AI Core went away with its listeners; drop ours and say so, since the screen now differs. */
    private fun forget(pluginId: String) {
        if (pluginId != LlmBackendRegistration.AI_CORE_PLUGIN_ID) return
        unwatch()
        onChanged()
    }
}

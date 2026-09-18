package com.itsaky.androidide.plugins.aicore.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.itsaky.androidide.plugins.PluginContext
import com.itsaky.androidide.plugins.aicore.logging.AgentTrace
import com.itsaky.androidide.plugins.aicore.plugin.AiCorePlugin

/**
 * Owns the Agent chat's [ChatViewModel] for as long as this plugin is loaded.
 *
 * The host mounts the Agent tab in a FragmentStateAdapter with no offscreen page limit, so
 * switching to any other bottom-sheet tab removes ChatFragment outright. A fragment-scoped
 * ViewModel is cleared with it, which cancelled the run in flight — for `run_app` that is a wait
 * of up to ten minutes — and left the reply with nowhere to land. Keeping the store here means the
 * fragment the user comes back to re-attaches to the same run.
 */
object ChatViewModelStore {

    private val store = ViewModelStore()

    /** The instance last handed out, so [get] can log whether a tab switch cost us the run. */
    @Volatile
    private var current: ChatViewModel? = null

    /**
     * The one [ChatViewModel], created on first call. Main thread only, as every caller is a
     * fragment lifecycle callback.
     *
     * @return the retained ViewModel.
     */
    fun get(): ChatViewModel {
        val resolved = ViewModelProvider(
            store,
            // The plugin context is resolved through the companion, never through a fragment: this
            // lambda is held for the ViewModel's whole life, and a captured fragment would leak.
            ChatViewModelFactory { AiCorePlugin.getContext() }
        )[ChatViewModel::class.java]
        if (resolved !== current) {
            current = resolved
            AgentTrace.stage("UI", "chat viewModel created")
        } else {
            // Reopening the tab mid-run must land here: "created" instead means the run is gone.
            AgentTrace.stage("UI", "chat viewModel reused")
        }
        return resolved
    }

    /** Clears the ViewModel, which persists the transcript and stops whatever is still running. */
    fun clear() {
        AgentTrace.stage("UI", "chat viewModel cleared")
        store.clear()
        current = null
    }
}

/**
 * Builds the [ChatViewModel] with its plugin-context getter.
 *
 * @param getContext resolves the plugin context lazily, so a ViewModel created before the plugin
 *   finished initializing still sees it later.
 */
private class ChatViewModelFactory(
    private val getContext: () -> PluginContext?
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(getContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

package com.itsaky.androidide.plugins.vectorsearch.settings

import com.itsaky.androidide.plugins.PluginLogger
import com.itsaky.androidide.plugins.services.LlmInferenceService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow

/**
 * What the Semantic Search screen reads and resets, so its view model depends on these calls
 * rather than on the whole plugin, and tests can stand in for it.
 */
interface SemanticSearchSource {

    /** Ticks when the selected backend, its models or AI Core itself may have changed. */
    val backendChanges: StateFlow<Long>

    /** Ticks when an index build ended or the index was cleared. */
    val indexChanges: StateFlow<Long>

    /** AI Core's inference service, or null when it is absent or not yet published. */
    fun inferenceService(): LlmInferenceService?

    /** What the index holds for the open project. Reads the database, so not on the main thread. */
    fun indexStatus(): IndexStatus

    /** Whether any project has rows, so a clear would delete something. Reads the database too. */
    fun indexHoldsAnything(): Boolean

    /** Clears every project's index; the returned job completes once the index is empty. */
    fun clearIndex(): Job

    /** This plugin's log, or null before it has a context. */
    fun logger(): PluginLogger?
}
